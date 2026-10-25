import { css, html, LitElement, nothing, type PropertyValues, svg } from 'lit';
import { repeat } from 'lit/directives/repeat.js';
import { announce } from '@vaadin/a11y-base/src/announce.js';
import { Iconset } from '@vaadin/icon/src/vaadin-iconset.js';

/**
 * An item as the server sends it: the key to refer to it by, its label, its icon and
 * tooltip if any, whether it has child items, whether it is expanded, whether it is
 * the selected item, whether it is on the selection path, whether it is disabled,
 * whether the user can edit its label, in checkbox selection mode whether it is
 * checked, whether the user can drag it, and whether the user can drop items on it.
 */
export interface TreeItem {
  key: string;
  label: string;
  /** The name of an icon in a Vaadin icon set, such as `vaadin:folder`. */
  icon: string | null;
  tooltip: string | null;
  expandable: boolean;
  expanded: boolean;
  selected: boolean;
  onSelectionPath: boolean;
  /** A disabled item can be focused and expanded, but not selected. */
  disabled: boolean;
  /** Whether the user can edit the label of the item. */
  editable: boolean;
  /** Whether the item is checked, in checkbox selection mode; `null` in single selection mode. */
  checked: CheckState | null;
  draggable: boolean;
  droppable: boolean;
}

/** Where an item is dropped, relative to the item it is dropped on; see DropLocation.java. */
export type DropLocation = 'BEFORE' | 'AFTER' | 'INTO';

/** The state of an item's checkbox: `mixed` when some of its descendants are checked, but not all. */
export type CheckState = 'true' | 'false' | 'mixed';

/** A page of child items, with the number of children the parent has in all. */
export interface ItemPage {
  items: TreeItem[];
  size: number;
}

/** The server side of the tree, see Tree.java. */
export interface TreeServer {
  fetchChildren(parentKey: string | null, offset: number, limit: number): Promise<ItemPage>;
  setExpanded(expanded: boolean, ...keys: string[]): unknown;
  select(key: string): unknown;
  edit(key: string, label: string): unknown;
  setChecked(key: string, checked: boolean): unknown;
  drop(draggedKey: string, targetKey: string, location: DropLocation): unknown;
  cut(key: string | null): unknown;
}

/**
 * How item icons are shown: `svg` draws each icon once in the tree, as an SVG symbol
 * each item showing it refers to; `vaadin-icon` gives each item a `<vaadin-icon>`.
 */
export type ItemIconMode = 'svg' | 'vaadin-icon';

/** The static side of Vaadin's icon set, which its type declarations leave out. */
const Iconsets = Iconset as unknown as {
  getIconset(name: string): HTMLElement | undefined;
  getIconSvg(icon: string): { svg: unknown; size?: number; viewBox?: string | null; preserveAspectRatio?: string | null };
};

/** An icon drawn once in the tree's shadow root, from the icon set it was found in. */
interface IconSymbol {
  id: string;
  iconset: HTMLElement;
  viewBox: string;
  preserveAspectRatio: string;
  shapes: unknown;
}

/** How many items are fetched at once, unless the server sets `pageSize`. */
export const PAGE_SIZE = 50;

/** How long type-ahead waits for the next character before starting a new search, in milliseconds. */
export const TYPE_AHEAD_TIMEOUT = 500;

/**
 * The parts of `<vaadin-tooltip>` the tree uses. Its `_stateController` opens and
 * closes it with the same delays as other tooltips, as `<vaadin-grid>` does.
 */
interface Tooltip extends HTMLElement {
  target: HTMLElement | null;
  text: string | null;
  opened: boolean;
  _stateController?: {
    open(options: { hover?: boolean; focus?: boolean }): void;
    close(immediate?: boolean): void;
  };
}

/** The texts the tree shows by itself, see TreeI18n.java. */
export interface TreeI18n {
  /** Shown while the items of a level are being fetched. */
  loading: string;
  /** Shown when the tree has no items, and its accessible description then. */
  empty: string;
  /** Shown, and announced, when fetching items fails. */
  loadError: string;
  /** The accessible description of each ancestor of the selected item. */
  selectionPath: string;
  /** The accessible name of the text field that edits the label of an item. */
  editor: string;
  /** Announced when an item is cut, to be moved; `{0}` stands for its label. */
  cut: string;
  /** Announced after `cut` when the user cuts an item with Ctrl+X: how to paste it. */
  cutHint: string;
}

export const DEFAULT_I18N: TreeI18n = {
  loading: 'Loading…',
  empty: 'No items',
  loadError: 'Could not load the items',
  selectionPath: 'Contains the selected item',
  editor: 'Label',
  cut: '{0} cut.',
  cutHint: 'Ctrl+V moves it into the focused item, Ctrl+Shift+V before it, Escape cancels.',
};

/** The id of the hidden text that describes each ancestor of the selected item. */
const SELECTION_PATH_DESCRIPTION_ID = 'selection-path-description';

/** How long the group of an item whose child items failed to load shows the error, in milliseconds. */
export const LOAD_ERROR_DURATION = 3000;

/** The child items of a parent (or the root items) fetched so far. */
interface Level {
  parent: Node | null;
  /** The items fetched so far: always the first ones, as pages are fetched in order. */
  nodes: Node[];
  /** How many items the level has in all, or -1 before its first page arrives. */
  size: number;
  /** The page being fetched, if any. */
  loading: Promise<void> | null;
  /** Whether fetching a page failed: no more pages are fetched until the level is fetched again. */
  failed: boolean;
}

/** An item fetched from the server, where it sits in the tree. */
interface Node {
  item: TreeItem;
  parent: Node | null;
  /** 1 for a root item. */
  depth: number;
  expanded: boolean;
  /** Created when the item is first expanded. */
  children?: Level;
}

/** The ancestors of a node, its parent first. */
function ancestors(node: Node): Node[] {
  const found: Node[] = [];
  for (let parent = node.parent; parent; parent = parent.parent) {
    found.push(parent);
  }
  return found;
}

/**
 * Scrolls an item into view, again and again until it stays put: the items brought into view on the way
 * are only laid out then (content-visibility), with their real size in place of the guessed one, which
 * moves the item. Gives up after a few frames.
 */
async function scrollIntoViewSettled(element: HTMLElement): Promise<void> {
  for (let i = 0; i < 5; i++) {
    element.scrollIntoView({ block: 'nearest' });
    const top = element.getBoundingClientRect().top;
    await new Promise((resolve) => requestAnimationFrame(resolve));
    if (element.getBoundingClientRect().top === top) {
      return;
    }
  }
}

/** Has the tooltip show the given text for the given item. */
function pointTooltip(tooltip: Tooltip, element: HTMLElement, text: string): void {
  tooltip.target = element;
  tooltip.text = text;
}

/**
 * A row of the tree: a visible item, the row standing for the items of a level not
 * fetched yet, or a message in place of items: that there are none, or that they
 * failed to load.
 */
type Row =
  | { kind: 'item'; node: Node; position: number; setSize: number }
  | { kind: 'loading'; level: Level; depth: number }
  | { kind: 'empty' | 'error'; parentKey: string; depth: number };

/** What renders in place of rows out of view, in virtual scrolling: their height, from the index of the first. */
type Spacer = { kind: 'spacer'; from: number; height: number };

/** How many visible items the tree renders all of, unless the server sets `virtualScrollingThreshold`. */
export const VIRTUAL_SCROLLING_THRESHOLD = 2000;

/** The smallest index whose offset is at least `y`, or the length if none is. */
function lowerBound(offsets: Float64Array, y: number): number {
  let low = 0;
  let high = offsets.length;
  while (low < high) {
    const middle = (low + high) >> 1;
    if (offsets[middle] < y) {
      low = middle + 1;
    } else {
      high = middle;
    }
  }
  return low;
}

function newLevel(parent: Node | null): Level {
  return { parent, nodes: [], size: -1, loading: null, failed: false };
}

/** Tells whether there are items of the level still to fetch. */
function hasMore(level: Level): boolean {
  return !level.failed && (level.size < 0 || level.nodes.length < level.size);
}

/**
 * An accessible tree: the element is the ARIA `tree` and each visible item a `treeitem`.
 *
 * Items are fetched from the server a page at a time through `$server.fetchChildren`
 * (ADR-0001): the root items first, the child items of an item when it is first
 * expanded, and the next page of a level when its end scrolls into view. Every item
 * fetched stays in the DOM, flat, with its level and position in `aria-level`,
 * `aria-posinset` and `aria-setsize`, unless there are more visible items than
 * `virtualScrollingThreshold`: then only the rows near the view render, and the
 * focused item, the item in the tab order, the item being edited, dragged or dropped
 * on, with spacers of the height of the rows in between (ADR-0003). Every item is
 * as high as the first one rendered, and every loading or message row as the first
 * of those.
 *
 * One item at a time is in the tab order (roving tabindex): the focused item, or when
 * focus enters the tree, the selected item if visible, else the first item. The keys
 * follow the ARIA tree pattern, selection not following focus: Up and Down move focus
 * between visible items, Home and End to the first and last; Right expands an item or
 * moves to its first child item, Left collapses it or moves to its parent (swapped
 * right to left); Enter and Space select the focused item; `*` expands its siblings;
 * typing moves to the next visible item whose label starts with what was typed.
 *
 * Clicking an item selects it, and tells `$server.select`, unless the item is
 * disabled: a disabled item is `aria-disabled`, and can be focused and expanded but
 * not selected. The server sets the
 * selection with `setSelection`. The ancestors of the selected item have the
 * `selection-path` attribute and a `selection-path-marker` part, and are described
 * as containing it, for assistive technology.
 *
 * An item can have an icon, by its name in a Vaadin icon set, decorative and hidden
 * from assistive technology. By default (`itemIconMode` `svg`) each icon is drawn once,
 * as an SVG symbol, and the items showing it are an `<svg>` referring to it; an icon
 * whose icon set is not loaded yet is a `<vaadin-icon>`, which shows it once loaded.
 * In the `vaadin-icon` mode, every item icon is a `<vaadin-icon>`, for what only it
 * supports. An item can also have a tooltip: one `<vaadin-tooltip>` follows the item hovered or
 * focused with the keyboard. The tooltip is hidden from assistive technology, as a
 * tree only holds treeitems; its text is the item's `aria-describedby` instead, from a
 * hidden copy, so the item is described whether or not the tooltip shows.
 *
 * In checkbox selection mode, each item has a checkbox (part `checkbox`, hidden from
 * assistive technology) and `aria-checked`, `mixed` when partially checked, in place
 * of `aria-selected`. Clicking an item, Space or Enter asks `$server.setChecked` to
 * check or uncheck it; the server answers with `setChecked`, the keys of the top-most
 * checked items and of the partially checked ones, and the tree checks the
 * descendants of a checked item itself (ADR-0002).
 *
 * A draggable item can be dragged and dropped before, into or after another, by where
 * the pointer is over it (states `drop-before`, `drop-into`, `drop-after` on the target,
 * `dragged` on the item dragged), or cut with Ctrl+X and pasted into the focused item
 * with Ctrl+V, before it with Ctrl+Shift+V (state `cut`). Either calls `$server.drop`;
 * the server learns of the cut item with `$server.cut`, and cuts one with `cutItem`;
 * an item is never dropped on itself, into its descendants, or on an item that takes
 * no drops.
 *
 * The label of an editable item is edited in a text field (part `editor`) in place of
 * the label: on F2, on double-click, or when the server calls `editItem`. Enter, or
 * focus leaving the field, saves it with `$server.edit`, which sends the item back
 * with the label the app gives it; Escape cancels. Either gives focus back to the item.
 *
 * A Vaadin context menu targeting the tree learns the item it opens on from
 * `getContextMenuBeforeOpenDetail`: the item right-clicked, long-pressed, or focused
 * when Shift+F10 or the Menu key is pressed.
 *
 * The server updates an item with `refreshItem`, and has the tree fetch everything
 * again with `refresh`. If fetching the child items of an item fails, the item
 * collapses back, the server is told, and its group shows the error for
 * `LOAD_ERROR_DURATION`; if fetching root items fails, the error stays until the next
 * refresh. Errors are announced through Vaadin's live region. An empty tree shows the
 * empty text, which is also its `aria-description` while empty. The texts come from
 * `i18n`.
 */
export class TfvTree extends LitElement {
  static override properties = {
    disabled: { type: Boolean, reflect: true },
    readonly: { type: Boolean },
    pageSize: { type: Number },
    virtualScrollingThreshold: { type: Number },
    itemIconMode: { type: String },
    i18n: { type: Object, attribute: false },
    _focusedKey: { state: true },
    _focusRing: { state: true },
    _selectedKey: { state: true },
    _selectionPath: { state: true },
    _editingKey: { state: true },
    _checkedKeys: { state: true },
    _partialKeys: { state: true },
    _draggedKey: { state: true },
    _cutKey: { state: true },
    _dropTarget: { state: true },
    _viewport: { state: true },
    _itemHeight: { state: true },
    _messageHeight: { state: true },
  };

  /*
   * Every --tfv-tree-* property falls back to Aura's tokens, then Lumo's, then the
   * --vaadin-* base tokens, then a literal: Lumo comes before the base tokens, which
   * Vaadin always defines, so that a Lumo app gets Lumo's spacing and colors.
   */
  static override styles = css`
    :host {
      --tfv-tree-font-size: var(--aura-font-size-m, var(--lumo-font-size-m, inherit));
      --tfv-tree-line-height: var(--aura-line-height-m, var(--lumo-line-height-m, inherit));
      --tfv-tree-text-color: var(--lumo-body-text-color, var(--vaadin-text-color, inherit));
      --tfv-tree-secondary-text-color: var(--lumo-secondary-text-color, var(--vaadin-text-color-secondary, inherit));
      --tfv-tree-indent: var(--lumo-space-l, var(--vaadin-padding-l, 1.25em));
      --tfv-tree-item-gap: var(--lumo-space-xs, var(--vaadin-gap-xs, 0.25em));
      --tfv-tree-item-padding-block: var(--lumo-space-xs, var(--vaadin-padding-xs, 0.25em));
      --tfv-tree-item-padding-inline: var(--lumo-space-s, var(--vaadin-padding-s, 0.5em));
      --tfv-tree-item-border-radius: var(--lumo-border-radius-m, var(--vaadin-radius-m, 0.25em));
      --tfv-tree-item-hover-background: var(
        --lumo-contrast-5pct,
        var(--vaadin-background-container, color-mix(in srgb, currentColor 6%, transparent))
      );
      /* A solid fill in the accent text color, which the themes keep in contrast with the background */
      --tfv-tree-selected-background: var(--aura-accent-text-color, var(--lumo-primary-text-color, Highlight));
      --tfv-tree-selected-text-color: var(--aura-background-color, var(--lumo-base-color, HighlightText));
      --tfv-tree-selection-path-color: var(--aura-accent-text-color, var(--lumo-primary-text-color, currentColor));
      /* Lumo's primary text color, as its primary color is too faint for a ring in the dark variant */
      --tfv-tree-focus-ring-color: var(--lumo-primary-text-color, var(--vaadin-focus-ring-color, Highlight));
      --tfv-tree-focus-ring-width: var(--vaadin-focus-ring-width, 2px);
      --tfv-tree-icon-size: var(--lumo-icon-size-s, var(--vaadin-icon-size, 1.125em));
      --tfv-tree-icon-color: var(--tfv-tree-secondary-text-color);
      --tfv-tree-toggle-color: var(--tfv-tree-secondary-text-color);
      --tfv-tree-error-text-color: var(--aura-red-text, var(--lumo-error-text-color, var(--tfv-tree-text-color)));
      --tfv-tree-disabled-text-color: var(
        --lumo-disabled-text-color,
        var(--vaadin-text-color-disabled, color-mix(in srgb, currentColor 50%, transparent))
      );
      --tfv-tree-checkbox-color: var(--aura-accent-text-color, var(--lumo-primary-text-color, Highlight));
      --tfv-tree-checkbox-mark-color: var(--aura-background-color, var(--lumo-base-color, HighlightText));
      --tfv-tree-drop-indicator-color: var(--tfv-tree-focus-ring-color);
      /* The width of the toggle, and of the spacer standing for it */
      --_toggle-width: 1em;

      display: block;
      overflow: auto;
      outline: none;
      font-size: var(--tfv-tree-font-size);
      line-height: var(--tfv-tree-line-height);
      color: var(--tfv-tree-text-color);
    }

    :host([hidden]) {
      display: none !important;
    }

    :host([disabled]) {
      opacity: 0.5;
      pointer-events: none;
    }

    [part~='item'] {
      display: flex;
      align-items: center;
      gap: var(--tfv-tree-item-gap);
      padding-block: var(--tfv-tree-item-padding-block);
      padding-inline: calc(var(--_level) * var(--tfv-tree-indent) + var(--tfv-tree-item-padding-inline))
        var(--tfv-tree-item-padding-inline);
      border-radius: var(--tfv-tree-item-border-radius);
      cursor: default;
      /*
       * The browser skips style, layout and paint of the items out of view, which stay in the page and the
       * accessibility tree: a tree of thousands of items renders and scrolls to an item twice as fast. Until
       * an item is first shown, the height of its content is guessed as one line, to which its padding and
       * min-height apply; that is its height unless its icon is taller. A wrong guess makes items jump as they
       * come into view.
       */
      content-visibility: auto;
      contain-intrinsic-size: auto 1lh;
    }

    /* Virtual scrolling places the rows by their real height: no guess, and only the rows near the view render */
    :host([virtual]) [part~='item'] {
      content-visibility: visible;
    }

    @media (any-hover: hover) {
      [part~='item']:not([selected]):not([disabled]):hover {
        background: var(--tfv-tree-item-hover-background);
      }
    }

    [part~='item'][disabled] {
      color: var(--tfv-tree-disabled-text-color);
      --tfv-tree-icon-color: currentColor;
      --tfv-tree-toggle-color: currentColor;
    }

    [part~='item'][selected] {
      background: var(--tfv-tree-selected-background);
      color: var(--tfv-tree-selected-text-color);
      --tfv-tree-icon-color: currentColor;
      --tfv-tree-toggle-color: currentColor;
    }

    [part~='item']:focus-visible {
      outline: var(--tfv-tree-focus-ring-width) solid var(--tfv-tree-focus-ring-color);
      outline-offset: calc(var(--tfv-tree-focus-ring-width) * -1);
    }

    /* On the selected fill, the ring takes the text color, inset from the edge so the fill frames it */
    [part~='item'][selected]:focus-visible {
      outline-color: var(--tfv-tree-selected-text-color);
      outline-offset: calc(var(--tfv-tree-focus-ring-width) * -2);
    }

    [part='toggle'],
    [part='toggle-spacer'] {
      flex: none;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: var(--_toggle-width);
      height: 1em;
      color: var(--tfv-tree-toggle-color);
    }

    [part='toggle'] {
      position: relative;
      cursor: pointer;
    }

    /* What takes clicks: at least 24px square around the chevron (WCAG 2.5.8), however small the font */
    [part='toggle']::after {
      content: '';
      position: absolute;
      top: 50%;
      left: 50%;
      width: max(100%, 24px);
      height: max(100%, 24px);
      translate: -50% -50%;
    }

    /* On a touch screen, items and their toggle are at least 44px, the size of a fingertip */
    @media (pointer: coarse) {
      :host {
        --_toggle-width: max(2.5em, 44px);
      }

      [part~='item'] {
        min-height: 44px;
        box-sizing: border-box;
      }

      /* As tall as its item */
      [part='toggle'] {
        align-self: stretch;
        height: auto;
        margin-block: calc(var(--tfv-tree-item-padding-block) * -1);
      }
    }

    /* A chevron pointing to the inline end, or down once expanded */
    [part='toggle']::before {
      content: '';
      width: 0.35em;
      height: 0.35em;
      border-inline-end: 2px solid currentColor;
      border-block-end: 2px solid currentColor;
      transform: rotate(-45deg);
    }

    [part='toggle']:dir(rtl)::before {
      transform: rotate(45deg);
    }

    [part~='item'][expanded] > [part='toggle']::before {
      transform: rotate(45deg);
    }

    /* A checkbox drawn in CSS: hidden from assistive technology, which reads aria-checked */
    [part='checkbox'] {
      flex: none;
      position: relative;
      box-sizing: border-box;
      width: 1em;
      height: 1em;
      border: 1px solid var(--tfv-tree-secondary-text-color);
      border-radius: 0.2em;
    }

    [part~='item'][checked] > [part='checkbox'],
    [part~='item'][partially-checked] > [part='checkbox'] {
      background: var(--tfv-tree-checkbox-color);
      border-color: var(--tfv-tree-checkbox-color);
    }

    /* A check mark, or a dash when partially checked */
    [part~='item'][checked] > [part='checkbox']::after,
    [part~='item'][partially-checked] > [part='checkbox']::after {
      content: '';
      position: absolute;
      border-color: var(--tfv-tree-checkbox-mark-color);
      border-style: solid;
    }

    [part~='item'][checked] > [part='checkbox']::after {
      inset: 0.12em 0.3em 0.22em;
      border-width: 0 0.12em 0.12em 0;
      transform: rotate(45deg);
    }

    [part~='item'][partially-checked] > [part='checkbox']::after {
      inset: calc(50% - 0.06em) 0.2em auto;
      border-width: 0.12em 0 0;
    }

    /* Where a dragged item would go: a line before or after the target, or a frame around it */
    [part~='item'][drop-before] {
      box-shadow: inset 0 var(--tfv-tree-focus-ring-width) 0 0 var(--tfv-tree-drop-indicator-color);
    }

    [part~='item'][drop-after] {
      box-shadow: inset 0 calc(var(--tfv-tree-focus-ring-width) * -1) 0 0 var(--tfv-tree-drop-indicator-color);
    }

    [part~='item'][drop-into] {
      outline: var(--tfv-tree-focus-ring-width) dashed var(--tfv-tree-drop-indicator-color);
      outline-offset: calc(var(--tfv-tree-focus-ring-width) * -1);
    }

    [part~='item'][dragged],
    [part~='item'][cut] {
      opacity: 0.5;
    }

    [part='icon'] {
      flex: none;
      width: var(--tfv-tree-icon-size);
      height: var(--tfv-tree-icon-size);
      color: var(--tfv-tree-icon-color);
      fill: currentColor;
      /* As vaadin-icon does, for icons drawn past their view box */
      overflow: visible;
    }

    #icons {
      position: absolute;
      width: 0;
      height: 0;
      overflow: hidden;
    }

    [part='editor'] {
      flex: 1;
      min-width: 0;
      margin: 0;
      padding: 0 var(--tfv-tree-item-gap);
      font: inherit;
      color: var(--tfv-tree-text-color);
      background: var(--lumo-base-color, var(--vaadin-background-color, Field));
      border: 1px solid var(--tfv-tree-focus-ring-color);
      border-radius: var(--tfv-tree-item-border-radius);
      outline: none;
    }

    [part='label'] {
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    /* A dot after the label of each ancestor of the selected item */
    [part='selection-path-marker'] {
      flex: none;
      width: 0.4em;
      height: 0.4em;
      border-radius: 50%;
      background: var(--tfv-tree-selection-path-color);
    }

    [part='loading'],
    [part='empty'],
    [part='error'] {
      padding-block: var(--tfv-tree-item-padding-block);
      padding-inline-start: calc(
        var(--_level) * var(--tfv-tree-indent) + var(--tfv-tree-item-padding-inline) + var(--_toggle-width) + var(--tfv-tree-item-gap)
      );
      font-size: 0.875em;
      color: var(--tfv-tree-secondary-text-color);
    }

    [part='error'] {
      color: var(--tfv-tree-error-text-color);
    }

    @media (forced-colors: active) {
      [part~='item'][selected] {
        forced-color-adjust: none;
        background: Highlight;
        color: HighlightText;
      }

      /* The focus ring, otherwise lost on the selected item's Highlight */
      [part~='item'][selected]:focus-visible {
        outline-color: HighlightText;
      }

      [part='selection-path-marker'] {
        background: CanvasText;
      }

      [part~='item'][disabled]:not([selected]) {
        color: GrayText;
      }

      [part='checkbox'] {
        forced-color-adjust: none;
        border-color: CanvasText;
      }

      [part~='item'][checked] > [part='checkbox'],
      [part~='item'][partially-checked] > [part='checkbox'] {
        background: Highlight;
        border-color: Highlight;
      }

      [part~='item'] > [part='checkbox']::after {
        border-color: HighlightText;
      }
    }
  `;

  declare disabled: boolean;
  /** Set by the server when the value is read-only: the user cannot change the selection. */
  declare readonly: boolean;
  declare pageSize: number;
  /** How many visible items the tree renders all of; past that, virtual scrolling. Set by the server. */
  declare virtualScrollingThreshold: number;
  /** How item icons are shown, set by the server. */
  declare itemIconMode: ItemIconMode;
  /** The texts the tree shows by itself, set by the server. */
  declare i18n: TreeI18n;
  declare $server?: TreeServer;
  /** The key of the item that has focus, or null while focus is outside the tree. */
  declare _focusedKey: string | null;
  /** Whether the focused item shows a focus ring, as it does when focused with the keyboard. */
  declare _focusRing: boolean;
  /** The key of the selected item, which may not be fetched yet. */
  declare _selectedKey: string | null;
  /** The keys of the ancestors of the selected item. */
  declare _selectionPath: ReadonlySet<string>;
  /** The key of the item whose label is being edited, if any. */
  declare _editingKey: string | null;
  /** In checkbox selection mode, the keys of the top-most checked items, whose descendants are checked too. */
  declare _checkedKeys: ReadonlySet<string>;
  /** In checkbox selection mode, the keys of the partially checked items. */
  declare _partialKeys: ReadonlySet<string>;
  /** The key of the item being dragged with the mouse, if any. */
  declare _draggedKey: string | null;
  /** The key of the item cut with Ctrl+X, to be pasted with Ctrl+V, if any. */
  declare _cutKey: string | null;
  /** Where the item dragged would be dropped, if anywhere. */
  declare _dropTarget: { key: string; location: DropLocation } | null;
  /** The part of the rows in view, in pixels from the top of the first row. */
  declare _viewport: { top: number; bottom: number };
  /** The height of an item, and of a loading or message row, as measured in virtual scrolling. */
  declare _itemHeight: number;
  declare _messageHeight: number;

  /** Whether the last render was virtual: only the rows near the view. */
  private _virtual = false;
  /** The key of the item `scrollToItem` scrolls to, which renders until it is in view. */
  private _scrollTargetKey: string | null = null;
  private _viewportFrame = 0;
  private _resizeObserver?: ResizeObserver;
  private readonly _onViewportChange = (): void => {
    if (this._virtual && this._viewportFrame === 0) {
      this._viewportFrame = requestAnimationFrame(() => {
        this._viewportFrame = 0;
        this._measureViewport();
      });
    }
  };

  private _root: Level = newLevel(null);

  /** The icons drawn once in the shadow root, by name, in the `svg` icon mode. */
  private _iconSymbols = new Map<string, IconSymbol>();
  private _iconSymbolCount = 0;
  private _nodes = new Map<string, Node>();

  /** The keys of the collapsed items whose group shows that their child items failed to load. */
  private _failedKeys = new Set<string>();
  private _failedTimeouts = new Map<string, ReturnType<typeof setTimeout>>();

  /** Whether the tree set its own `aria-description`, to the empty text. */
  private _describedAsEmpty = false;

  /** Ignores the answers to fetches made before the latest refresh. */
  private _generation = 0;

  /** Fetches the next page of a level when its loading row scrolls into view. */
  private _loadingRowObserver?: IntersectionObserver;

  /**
   * The key of the item the tooltip is for: open, opening, or dismissed by the user, in
   * which case it stays closed until another item is hovered or focused.
   */
  private _tooltipKey: string | null = null;

  /** What the user has typed so far to move focus by label, lower-cased. */
  private _typed = '';
  private _typedTimeout?: ReturnType<typeof setTimeout>;

  constructor() {
    super();
    this.disabled = false;
    this.readonly = false;
    this.pageSize = PAGE_SIZE;
    this.virtualScrollingThreshold = VIRTUAL_SCROLLING_THRESHOLD;
    this.itemIconMode = 'svg';
    this.i18n = DEFAULT_I18N;
    this._focusedKey = null;
    this._focusRing = false;
    this._selectedKey = null;
    this._selectionPath = new Set();
    this._editingKey = null;
    this._checkedKeys = new Set();
    this._partialKeys = new Set();
    this._draggedKey = null;
    this._cutKey = null;
    this._dropTarget = null;
    this._viewport = { top: 0, bottom: window.innerHeight };
    // Guesses, until measured
    this._itemHeight = 32;
    this._messageHeight = 28;
  }

  override connectedCallback(): void {
    super.connectedCallback();
    if (!this.hasAttribute('role')) {
      this.setAttribute('role', 'tree');
    }
    this._loadingRowObserver = new IntersectionObserver((entries) => this._onLoadingRowsVisible(entries), {
      rootMargin: '0px 0px 100px 0px',
    });
    // Any scrolling may bring other rows into view: the tree's, the page's, or a container's
    document.addEventListener('scroll', this._onViewportChange, { capture: true, passive: true });
    window.addEventListener('resize', this._onViewportChange);
    this._resizeObserver = new ResizeObserver(this._onViewportChange);
    this._resizeObserver.observe(this);
  }

  override disconnectedCallback(): void {
    this._loadingRowObserver?.disconnect();
    this._loadingRowObserver = undefined;
    document.removeEventListener('scroll', this._onViewportChange, { capture: true });
    window.removeEventListener('resize', this._onViewportChange);
    this._resizeObserver?.disconnect();
    this._resizeObserver = undefined;
    cancelAnimationFrame(this._viewportFrame);
    this._viewportFrame = 0;
    clearTimeout(this._typedTimeout);
    this._typed = '';
    this._closeTooltip();
    this._clearLoadErrors();
    super.disconnectedCallback();
  }

  /** Drops the items shown and fetches the root items again. Called by the server. */
  async refresh(): Promise<void> {
    this._generation++;
    this._root = newLevel(null);
    this._nodes.clear();
    this._clearLoadErrors();
    this._editingKey = null;
    // The items fetched again tell which are selected or checked
    this._checkedKeys = new Set();
    this._partialKeys = new Set();
    this._selectedKey = null;
    this._selectionPath = new Set();
    await this._fetchNextPage(this._root);
  }

  /**
   * Updates an item the browser has fetched, and fetches its child items again if
   * asked to, or if it no longer has any. Called by the server.
   */
  refreshItem(item: TreeItem, refreshChildren: boolean): void {
    const node = this._nodes.get(item.key);
    if (!node) {
      return;
    }
    node.item = item;
    node.expanded = item.expandable && item.expanded;
    this._learnCheckState(node);
    if (refreshChildren || !item.expandable) {
      this._forgetChildren(node);
      if (node.expanded) {
        this._fetchChildren(node);
      }
    }
    this.requestUpdate();
  }

  /** Expands or collapses an item the browser has fetched. Called by the server. */
  setItemExpanded(key: string, expanded: boolean): void {
    const node = this._nodes.get(key);
    if (node) {
      this._setExpanded(node, expanded);
    }
  }

  /**
   * Sets the selected item and its selection path, by the keys of the items the
   * browser has. Called by the server.
   */
  setSelection(selectedKey: string | null, ...selectionPath: string[]): void {
    this._selectedKey = selectedKey;
    this._selectionPath = new Set(selectionPath);
  }

  /**
   * Scrolls an item into view, fetching pages of each level on the way down until the
   * next item is there. Called by the server, with the keys of an item's ancestors, the
   * root item first, then the item's own key, once it has expanded the ancestors.
   * Gives up if the tree is refreshed meanwhile, as the keys are then stale, or if the
   * user collapses an ancestor.
   */
  async scrollToItem(...keys: string[]): Promise<void> {
    if (keys.length === 0) {
      return;
    }
    const generation = this._generation;
    // Before the first update, the root items are not being fetched yet
    await this.updateComplete;
    let level = this._root;
    let node: Node | undefined;
    for (const [index, key] of keys.entries()) {
      node = await this._fetchUntilFound(level, key, generation);
      if (!node || generation !== this._generation) {
        return;
      }
      if (index < keys.length - 1) {
        if (!node.expanded || !node.children) {
          return;
        }
        level = node.children;
      }
    }
    if (!node) {
      return;
    }
    this._scrollTargetKey = node.item.key;
    this.requestUpdate();
    await this.updateComplete;
    const element = this._itemElement(node.item.key);
    if (element) {
      await scrollIntoViewSettled(element);
    }
    if (this._scrollTargetKey === node.item.key) {
      this._scrollTargetKey = null;
      this.requestUpdate();
    }
  }

  /**
   * Sets the checked items, by the keys of the top-most checked items and of the
   * partially checked items the browser has. Called by the server.
   */
  setChecked(checkedKeys: string[], partialKeys: string[]): void {
    this._checkedKeys = new Set(checkedKeys);
    this._partialKeys = new Set(partialKeys);
  }

  /**
   * Shows an item cut, to be moved, and says so, or none if the key is null. Called
   * by the server, from a context menu for instance; Ctrl+V pastes it as if cut here.
   */
  cutItem(key: string | null): void {
    const node = key === null ? undefined : this._nodes.get(key);
    this._cutKey = node ? key : null;
    if (node) {
      announce(this.i18n.cut.replace('{0}', node.item.label));
    }
  }

  /** Edits the label of an item, if it is shown and editable. Called by the server. */
  editItem(key: string): void {
    const node = this._nodes.get(key);
    if (node && this._visibleItems().includes(node)) {
      this._startEditing(node);
    }
  }

  /** Focuses the item in the tab order. */
  override focus(options?: FocusOptions): void {
    const item = this._itemElement(this._tabbableKey());
    if (item) {
      item.focus(options);
    } else {
      super.focus(options);
    }
  }

  /**
   * The detail Vaadin's context menu sends the server before opening: the key of the
   * item it opens on, or an empty key outside the items. Called by the context menu's
   * target connector while the event that opens it is dispatched.
   */
  getContextMenuBeforeOpenDetail(event: Event): { key: string } {
    // The contextmenu event comes as the source event of Vaadin's gesture; a click comes as itself
    const source: Event & { __composedPath?: EventTarget[] } =
      (event as CustomEvent<{ sourceEvent?: Event }>).detail?.sourceEvent ?? event;
    // On iOS the gesture fires after a timeout, once its touchstart has lost its path: Vaadin saves it
    const path = source.__composedPath ?? source.composedPath();
    const element = path.find(
      (target): target is HTMLElement => target instanceof HTMLElement && target.getAttribute('role') === 'treeitem',
    );
    return { key: element?.dataset.key ?? '' };
  }

  /** Keeps Vaadin's context menu closed while the tree is disabled. */
  preventContextMenu(_event: Event): boolean {
    return this.disabled;
  }

  protected override willUpdate(): void {
    // Fetching before the first render shows the tree busy from the start
    if (!this.hasUpdated) {
      this._fetchNextPage(this._root);
    }
  }

  protected override updated(changed: PropertyValues<this>): void {
    if (changed.has('disabled')) {
      if (this.disabled) {
        this.setAttribute('aria-disabled', 'true');
      } else {
        this.removeAttribute('aria-disabled');
      }
    }
    if (this._root.loading !== null) {
      this.setAttribute('aria-busy', 'true');
    } else {
      this.removeAttribute('aria-busy');
    }
    this._updateEmptyDescription();
    this._updateTooltip();
    const observer = this._loadingRowObserver;
    if (observer) {
      observer.disconnect();
      this.renderRoot.querySelectorAll('[part="loading"]').forEach((row) => observer.observe(row));
    }
    this.toggleAttribute('virtual', this._virtual);
    if (this._virtual) {
      this._measureRows();
      this._measureViewport();
    }
  }

  /** Learns the height of an item, and of a loading or message row, from those rendered. */
  private _measureRows(): void {
    const item = Array.from(this.renderRoot.querySelectorAll<HTMLElement>('[role="treeitem"]')).find(
      (element) => element.dataset.key !== this._editingKey,
    );
    const message = this.renderRoot.querySelector<HTMLElement>('[part="loading"], [part="empty"], [part="error"]');
    const itemHeight = item?.getBoundingClientRect().height;
    const messageHeight = message?.getBoundingClientRect().height;
    if (itemHeight && Math.abs(itemHeight - this._itemHeight) > 0.1) {
      this._itemHeight = itemHeight;
    }
    if (messageHeight && Math.abs(messageHeight - this._messageHeight) > 0.1) {
      this._messageHeight = messageHeight;
    }
  }

  /** Learns which part of the rows is in view: in the tree's box, and in the window. */
  private _measureViewport(): void {
    const rows = this.renderRoot.querySelector('#rows');
    if (!rows) {
      return;
    }
    const origin = rows.getBoundingClientRect().top;
    const box = this.getBoundingClientRect();
    const top = Math.max(box.top, 0);
    const bottom = Math.max(top, Math.min(box.bottom, window.innerHeight));
    const viewport = { top: top - origin, bottom: bottom - origin };
    if (Math.abs(viewport.top - this._viewport.top) >= 1 || Math.abs(viewport.bottom - this._viewport.bottom) >= 1) {
      this._viewport = viewport;
    }
  }

  /**
   * The rows to render: all of them up to the threshold of visible items; past it, the
   * rows near the view and those that must stay rendered, with spacers in between.
   */
  private _windowOf(rows: Row[], tabbableKey: string | null): (Row | Spacer)[] {
    let items = 0;
    for (const row of rows) {
      if (row.kind === 'item') {
        items++;
      }
    }
    this._virtual = items > this.virtualScrollingThreshold;
    if (!this._virtual) {
      return rows;
    }
    const offsets = new Float64Array(rows.length + 1);
    rows.forEach((row, index) => {
      offsets[index + 1] = offsets[index] + (row.kind === 'item' ? this._itemHeight : this._messageHeight);
    });
    const { top, bottom } = this._viewport;
    const margin = Math.max(300, bottom - top);
    const shown = new Set<number>();
    const end = Math.min(rows.length, lowerBound(offsets, bottom + margin));
    for (let index = Math.max(0, lowerBound(offsets, top - margin) - 1); index < end; index++) {
      shown.add(index);
    }
    const kept = new Set(
      [this._focusedKey, tabbableKey, this._editingKey, this._draggedKey, this._dropTarget?.key, this._scrollTargetKey].filter(
        (key) => key != null,
      ),
    );
    rows.forEach((row, index) => {
      if (row.kind === 'item' && kept.has(row.node.item.key)) {
        shown.add(index);
      }
    });
    const window: (Row | Spacer)[] = [];
    let next = 0;
    for (const index of [...shown].sort((a, b) => a - b)) {
      if (index > next) {
        window.push({ kind: 'spacer', from: next, height: offsets[index] - offsets[next] });
      }
      window.push(rows[index]);
      next = index + 1;
    }
    if (next < rows.length) {
      window.push({ kind: 'spacer', from: next, height: offsets[rows.length] - offsets[next] });
    }
    return window;
  }

  protected override render() {
    const rows = this._rows();
    const tabbableKey = this._tabbableKey(rows);
    const shown = this._windowOf(rows, tabbableKey);
    const shownRows = shown.filter((row): row is Row => row.kind !== 'spacer');
    const symbols = this._iconSymbolsOf(shownRows);
    return html`
      <div
        id="rows"
        role="none"
        @keydown=${this._onKeyDown}
        @focusin=${this._onFocusIn}
        @focusout=${this._onFocusOut}
        @click=${this._onClick}
        @dblclick=${this._onDoubleClick}
        @dragstart=${this._onDragStart}
        @dragover=${this._onDragOver}
        @drop=${this._onDrop}
        @dragend=${this._onDragEnd}
        @mouseover=${this._onMouseOver}
        @mouseleave=${this._onMouseLeave}
        @mousedown=${this._dismissTooltip}
      >
        ${repeat(
          shown,
          (row) =>
            row.kind === 'item'
              ? row.node.item.key
              : row.kind === 'loading'
                ? `loading:${row.level.parent?.item.key ?? ''}`
                : row.kind === 'spacer'
                  ? `spacer:${row.from}`
                  : `${row.kind}:${row.parentKey}`,
          (row) =>
            row.kind === 'item'
              ? this._renderItem(row, tabbableKey)
              : row.kind === 'loading'
                ? this._renderLoading(row)
                : row.kind === 'spacer'
                  ? html`<div aria-hidden="true" style="height: ${row.height}px"></div>`
                  : this._renderMessage(row),
        )}
      </div>
      <div hidden>
        <span id=${SELECTION_PATH_DESCRIPTION_ID}>${this.i18n.selectionPath}</span>
        ${shownRows.map((row) =>
          row.kind === 'item' && row.node.item.tooltip
            ? html`<span id=${this._descriptionId(row.node.item.key)}>${row.node.item.tooltip}</span>`
            : nothing,
        )}
      </div>
      <!-- A tree only holds treeitems: the tooltip is for the eyes, the hidden copies above describe the items -->
      <vaadin-tooltip manual aria-hidden="true" .ariaTarget=${null}></vaadin-tooltip>
      <slot name="tooltip"></slot>
      ${symbols.length > 0
        ? html`<svg id="icons" aria-hidden="true">
            ${symbols.map(
              (symbol) =>
                svg`<symbol id=${symbol.id} viewBox=${symbol.viewBox} preserveAspectRatio=${symbol.preserveAspectRatio}>${symbol.shapes}</symbol>`,
            )}
          </svg>`
        : nothing}
    `;
  }

  /** The symbols of the icons the rows show, drawing those not drawn yet, in the `svg` icon mode. */
  private _iconSymbolsOf(rows: Row[]): IconSymbol[] {
    if (this.itemIconMode !== 'svg') {
      return [];
    }
    for (const row of rows) {
      if (row.kind === 'item' && row.node.item.icon) {
        this._iconSymbol(row.node.item.icon);
      }
    }
    return [...this._iconSymbols.values()];
  }

  /**
   * The symbol of an icon, drawn from its icon set the first time, and again if the
   * icon set is replaced; `null` while the icon set, or the icon in it, is not loaded.
   */
  private _iconSymbol(icon: string): IconSymbol | null {
    const iconset = Iconsets.getIconset(icon.split(':')[0] || 'vaadin');
    const drawn = this._iconSymbols.get(icon);
    if (drawn && drawn.iconset === iconset) {
      return drawn;
    }
    this._iconSymbols.delete(icon);
    if (!iconset) {
      return null;
    }
    const { svg: shapes, size, viewBox, preserveAspectRatio } = Iconsets.getIconSvg(icon);
    if (shapes === nothing) {
      return null;
    }
    const symbol: IconSymbol = {
      id: `icon-${this._iconSymbolCount++}`,
      iconset,
      viewBox: viewBox || `0 0 ${size} ${size}`,
      preserveAspectRatio: preserveAspectRatio || 'xMidYMid meet',
      shapes,
    };
    this._iconSymbols.set(icon, symbol);
    return symbol;
  }

  /** An item's icon: an `<svg>` referring to its symbol, or a `<vaadin-icon>`. */
  private _renderIcon(icon: string) {
    const symbol = this.itemIconMode === 'svg' ? this._iconSymbol(icon) : null;
    return symbol
      ? html`<svg part="icon" aria-hidden="true"><use href="#${symbol.id}"></use></svg>`
      : html`<vaadin-icon part="icon" icon=${icon} aria-hidden="true"></vaadin-icon>`;
  }

  private _renderItem(row: Extract<Row, { kind: 'item' }>, tabbableKey: string | null) {
    const { node } = row;
    const { item } = node;
    const selected = item.key === this._selectedKey;
    const onSelectionPath = this._selectionPath.has(item.key);
    const expanded = item.expandable && node.expanded;
    const focused = item.key === this._focusedKey;
    const checkState = this._checkState(node);
    // The states are part names too, as ::part() cannot be followed by an attribute selector
    const states = {
      expanded,
      selected,
      'selection-path': onSelectionPath,
      checked: checkState === 'true',
      'partially-checked': checkState === 'mixed',
      focused,
      'focus-ring': focused && this._focusRing,
      disabled: item.disabled,
      'drop-before': this._dropTarget?.key === item.key && this._dropTarget.location === 'BEFORE',
      'drop-into': this._dropTarget?.key === item.key && this._dropTarget.location === 'INTO',
      'drop-after': this._dropTarget?.key === item.key && this._dropTarget.location === 'AFTER',
      dragged: item.key === this._draggedKey,
      cut: item.key === this._cutKey,
    };
    const part = ['item', ...Object.entries(states).flatMap(([state, on]) => (on ? [state] : []))].join(' ');
    // The marker is for the eyes: the description tells assistive technology
    const describedBy = [
      onSelectionPath ? SELECTION_PATH_DESCRIPTION_ID : null,
      item.tooltip ? this._descriptionId(item.key) : null,
    ].filter((id) => id !== null);
    return html`
      <div
        part=${part}
        role="treeitem"
        aria-level=${node.depth}
        aria-posinset=${row.position}
        aria-setsize=${row.setSize}
        aria-expanded=${item.expandable ? String(node.expanded) : nothing}
        aria-busy=${node.expanded && node.children?.loading ? 'true' : nothing}
        aria-selected=${checkState === null ? String(selected) : nothing}
        aria-checked=${checkState ?? nothing}
        aria-disabled=${item.disabled ? 'true' : nothing}
        aria-describedby=${describedBy.length > 0 ? describedBy.join(' ') : nothing}
        ?expanded=${expanded}
        ?selected=${selected}
        ?selection-path=${onSelectionPath}
        ?checked=${states.checked}
        ?partially-checked=${states['partially-checked']}
        ?focused=${focused}
        ?focus-ring=${states['focus-ring']}
        ?disabled=${item.disabled}
        ?drop-before=${states['drop-before']}
        ?drop-into=${states['drop-into']}
        ?drop-after=${states['drop-after']}
        ?dragged=${states.dragged}
        ?cut=${states.cut}
        draggable=${this._canDrag(node) ? 'true' : nothing}
        tabindex=${!this.disabled && item.key === tabbableKey ? 0 : -1}
        data-key=${item.key}
        style="--_level: ${node.depth - 1}"
      >
        ${item.expandable
          ? html`<span part="toggle" aria-hidden="true"></span>`
          : html`<span part="toggle-spacer" aria-hidden="true"></span>`}
        ${checkState === null ? nothing : html`<span part="checkbox" aria-hidden="true"></span>`}
        ${item.icon ? this._renderIcon(item.icon) : nothing}
        ${item.key === this._editingKey
          ? html`<input
              part="editor"
              aria-label=${this.i18n.editor}
              .value=${item.label}
              @keydown=${this._onEditorKeyDown}
              @blur=${this._onEditorBlur}
            />`
          : html`<span part="label">${item.label}</span>`}
        ${onSelectionPath ? html`<span part="selection-path-marker" aria-hidden="true"></span>` : nothing}
      </div>
    `;
  }

  /** Hidden from assistive technology, which learns of the loading from `aria-busy`. */
  private _renderLoading(row: Extract<Row, { kind: 'loading' }>) {
    return html`
      <div
        part="loading"
        aria-hidden="true"
        data-parent-key=${row.level.parent?.item.key ?? ''}
        style="--_level: ${row.depth - 1}"
      >
        ${this.i18n.loading}
      </div>
    `;
  }

  /**
   * Hidden from assistive technology, as a tree only holds treeitems: the error is
   * announced, and the empty text is the tree's description.
   */
  private _renderMessage(row: Extract<Row, { kind: 'empty' | 'error' }>) {
    return html`
      <div part=${row.kind} aria-hidden="true" data-parent-key=${row.parentKey} style="--_level: ${row.depth - 1}">
        ${row.kind === 'empty' ? this.i18n.empty : this.i18n.loadError}
      </div>
    `;
  }

  /** Whether the root items have been fetched, and there are none. */
  private _isEmpty(): boolean {
    return this._root.size === 0 && !this._root.failed;
  }

  /** Describes the tree with the empty text while it has no items, unless the app describes it. */
  private _updateEmptyDescription(): void {
    const empty = this._isEmpty();
    if (empty && (this._describedAsEmpty || !this.hasAttribute('aria-description'))) {
      this.setAttribute('aria-description', this.i18n.empty);
      this._describedAsEmpty = true;
    } else if (!empty && this._describedAsEmpty) {
      this.removeAttribute('aria-description');
      this._describedAsEmpty = false;
    }
  }

  /**
   * The rows to show: the visible items in tree order, each level followed by its
   * loading row if it has more items, or its error row if they failed to load; the
   * empty row if there are no items.
   */
  private _rows(): Row[] {
    const rows: Row[] = [];
    const addLevel = (level: Level, depth: number) => {
      level.nodes.forEach((node, index) => {
        rows.push({ kind: 'item', node, position: index + 1, setSize: level.size });
        if (node.expanded && node.children) {
          addLevel(node.children, depth + 1);
        } else if (this._failedKeys.has(node.item.key)) {
          rows.push({ kind: 'error', parentKey: node.item.key, depth: depth + 1 });
        }
      });
      if (hasMore(level)) {
        rows.push({ kind: 'loading', level, depth });
      } else if (level.failed) {
        rows.push({ kind: 'error', parentKey: level.parent?.item.key ?? '', depth });
      }
    };
    addLevel(this._root, 1);
    if (this._isEmpty()) {
      rows.push({ kind: 'empty', parentKey: '', depth: 1 });
    }
    return rows;
  }

  /** The visible items, in tree order. */
  private _visibleItems(rows: Row[] = this._rows()): Node[] {
    return rows.flatMap((row) => (row.kind === 'item' ? [row.node] : []));
  }

  /**
   * The key of the item in the tab order: the focused item if visible, else the
   * selected item if visible, else its deepest visible ancestor (which shows the
   * selection path), else the first item.
   */
  private _tabbableKey(rows: Row[] = this._rows()): string | null {
    const keys = this._visibleItems(rows).map((node) => node.item.key);
    const deepestOnPath = [...keys].reverse().find((key) => this._selectionPath.has(key));
    return (
      [this._focusedKey, this._selectedKey].find((key) => key !== null && keys.includes(key)) ??
      deepestOnPath ??
      keys[0] ??
      null
    );
  }

  /** Fetches the next page of a level, or waits for the one being fetched. */
  private _fetchNextPage(level: Level): Promise<void> {
    if (!level.loading && this.$server && hasMore(level)) {
      level.loading = this._fetchPage(level, this.$server);
      this.requestUpdate();
    }
    return level.loading ?? Promise.resolve();
  }

  private async _fetchPage(level: Level, server: TreeServer): Promise<void> {
    const generation = this._generation;
    try {
      let page: ItemPage;
      try {
        page = await server.fetchChildren(level.parent?.item.key ?? null, level.nodes.length, this.pageSize);
      } catch {
        // The server has handed the exception to its error handler
        if (generation === this._generation && this._isShown(level)) {
          this._onLoadError(level);
        }
        return;
      }
      if (generation !== this._generation || !this._isShown(level)) {
        return;
      }
      const depth = (level.parent?.depth ?? 0) + 1;
      const added = page.items.map((item) => this._addNode(item, level.parent, depth));
      level.nodes.push(...added);
      // An empty page means the server knows no more items, whatever the size said
      level.size = page.items.length === 0 ? level.nodes.length : page.size;
      added.filter((node) => node.expanded).forEach((node) => this._fetchChildren(node));
    } finally {
      level.loading = null;
      this.requestUpdate();
    }
  }

  /** Tells whether a level is still part of the tree, not dropped by refreshing its parent or an ancestor. */
  private _isShown(level: Level): boolean {
    for (let current = level; current.parent; current = current.parent.parent?.children ?? this._root) {
      const parent: Node = current.parent;
      if (parent.children !== current || this._nodes.get(parent.item.key) !== parent) {
        return false;
      }
    }
    return true;
  }

  /**
   * Shows that a page of a level failed to load, and announces it. The parent of the
   * level, if any, collapses back and its child items are dropped, so that expanding it
   * again fetches them again; its group shows the error for a while.
   */
  private _onLoadError(level: Level): void {
    announce(this.i18n.loadError, { mode: 'alert' });
    const parent = level.parent;
    if (!parent) {
      level.failed = true;
      return;
    }
    const key = parent.item.key;
    if (parent.expanded) {
      this._setExpanded(parent, false);
      this.$server?.setExpanded(false, key);
    }
    this._forgetChildren(parent);
    this._failedKeys.add(key);
    clearTimeout(this._failedTimeouts.get(key));
    this._failedTimeouts.set(
      key,
      setTimeout(() => {
        this._failedTimeouts.delete(key);
        this._failedKeys.delete(key);
        this.requestUpdate();
      }, LOAD_ERROR_DURATION),
    );
  }

  /** Stops showing errors under items. */
  private _clearLoadErrors(): void {
    this._failedTimeouts.forEach((timeout) => clearTimeout(timeout));
    this._failedTimeouts.clear();
    this._failedKeys.clear();
  }

  /** Drops the fetched child items of an item and their descendants, moving focus to the item from any of them. */
  private _forgetChildren(node: Node): void {
    if (!node.children) {
      return;
    }
    this._keepFocusVisible(node);
    const forget = (level: Level) =>
      level.nodes.forEach((child) => {
        this._nodes.delete(child.item.key);
        if (child.children) {
          forget(child.children);
        }
      });
    forget(node.children);
    node.children = undefined;
  }

  /** Fetches pages of a level until the item with the given key is there, if it ever is. */
  private async _fetchUntilFound(level: Level, key: string, generation: number): Promise<Node | undefined> {
    for (;;) {
      const node = level.nodes.find((candidate) => candidate.item.key === key);
      if (node || generation !== this._generation || (!level.loading && !hasMore(level)) || !this.$server) {
        return node;
      }
      await this._fetchNextPage(level);
    }
  }

  private _addNode(item: TreeItem, parent: Node | null, depth: number): Node {
    const node: Node = { item, parent, depth, expanded: item.expandable && item.expanded };
    this._nodes.set(item.key, node);
    if (item.selected) {
      this._selectedKey = item.key;
    }
    if (item.onSelectionPath) {
      this._selectionPath = new Set(this._selectionPath).add(item.key);
    }
    this._learnCheckState(node);
    return node;
  }

  /**
   * The state of an item's checkbox, or null in single selection mode: checked if it
   * or an ancestor is among the top-most checked items.
   */
  private _checkState(node: Node): CheckState | null {
    if (node.item.checked === null) {
      return null;
    }
    for (let current: Node | null = node; current; current = current.parent) {
      if (this._checkedKeys.has(current.item.key)) {
        return 'true';
      }
    }
    return this._partialKeys.has(node.item.key) ? 'mixed' : 'false';
  }

  /** Takes the state the server sent with an item into the checked and partially checked keys. */
  private _learnCheckState(node: Node): void {
    const { key, checked } = node.item;
    const checkedKeys = new Set(this._checkedKeys);
    const partialKeys = new Set(this._partialKeys);
    checkedKeys.delete(key);
    partialKeys.delete(key);
    // A checked item under a checked parent is checked by it already
    if (checked === 'true' && !(node.parent && this._checkState(node.parent) === 'true')) {
      checkedKeys.add(key);
    } else if (checked === 'mixed') {
      partialKeys.add(key);
    }
    this._checkedKeys = checkedKeys;
    this._partialKeys = partialKeys;
  }

  /** Asks the server to check an item, or to uncheck it if it is checked, unless the user cannot. */
  private _toggleChecked(node: Node): void {
    if (this.readonly || node.item.disabled) {
      return;
    }
    this.$server?.setChecked(node.item.key, this._checkState(node) !== 'true');
  }

  /** Selects an item, or in checkbox selection mode checks or unchecks it. */
  private _activate(node: Node): void {
    if (node.item.checked === null) {
      this._select(node);
    } else {
      this._toggleChecked(node);
    }
  }

  /** Fetches the first page of child items of an expanded item, unless fetched already. */
  private _fetchChildren(node: Node): void {
    if (!node.children) {
      node.children = newLevel(node);
      this._fetchNextPage(node.children);
    }
  }

  private _setExpanded(node: Node, expanded: boolean): void {
    if (!node.item.expandable || node.expanded === expanded) {
      return;
    }
    node.expanded = expanded;
    if (expanded) {
      this._failedKeys.delete(node.item.key);
      this._fetchChildren(node);
    } else {
      this._keepFocusVisible(node);
    }
    this.requestUpdate();
  }

  /** Moves focus to an item being collapsed from any of its descendants, which are being hidden. */
  private _keepFocusVisible(collapsed: Node): void {
    let ancestor = this._focusedKey === null ? null : (this._nodes.get(this._focusedKey)?.parent ?? null);
    while (ancestor && ancestor !== collapsed) {
      ancestor = ancestor.parent;
    }
    if (!ancestor) {
      return;
    }
    const hadFocus = this.shadowRoot?.activeElement !== null;
    this._focusedKey = collapsed.item.key;
    if (hadFocus) {
      this.updateComplete.then(() => this._itemElement(collapsed.item.key)?.focus());
    }
  }

  private _toggle(node: Node): void {
    const expanded = !node.expanded;
    this._setExpanded(node, expanded);
    this.$server?.setExpanded(expanded, node.item.key);
  }

  /** Expands the fetched siblings of an item, and the item itself, telling the server in one go. */
  private _expandSiblings(node: Node): void {
    const siblings = (node.parent?.children ?? this._root).nodes.filter(
      (sibling) => sibling.item.expandable && !sibling.expanded,
    );
    if (siblings.length > 0) {
      siblings.forEach((sibling) => this._setExpanded(sibling, true));
      this.$server?.setExpanded(true, ...siblings.map((sibling) => sibling.item.key));
    }
  }

  private _onLoadingRowsVisible(entries: IntersectionObserverEntry[]): void {
    entries
      .filter((entry) => entry.isIntersecting)
      .forEach((entry) => {
        const parentKey = (entry.target as HTMLElement).dataset.parentKey;
        const level = parentKey ? this._nodes.get(parentKey)?.children : this._root;
        if (level) {
          this._fetchNextPage(level);
        }
      });
  }

  /** The id of the hidden copy of an item's tooltip text, which describes the item. */
  private _descriptionId(key: string): string {
    return `tooltip-${key}`;
  }

  private _tooltip(): Tooltip | null {
    return this.renderRoot.querySelector<Tooltip>('vaadin-tooltip');
  }

  /** Opens the tooltip of an item, if it has one, after the delay for hover or keyboard focus. */
  private _showTooltip(element: HTMLElement, trigger: 'hover' | 'focus'): void {
    const key = element.dataset.key;
    const text = key === undefined ? null : this._nodes.get(key)?.item.tooltip;
    const tooltip = this._tooltip();
    if (!text || !tooltip?._stateController || this.disabled) {
      this._closeTooltip();
      return;
    }
    this._tooltipKey = key!;
    pointTooltip(tooltip, element, text);
    tooltip._stateController.open({ [trigger]: true });
  }

  /** Closes the tooltip at the user's request: it stays closed until another item is hovered or focused. */
  private _dismissTooltip(): void {
    this._tooltip()?._stateController?.close(true);
  }

  /** Closes the tooltip and forgets its item, so that hovering or focusing any item opens it again. */
  private _closeTooltip(immediate = true): void {
    this._tooltipKey = null;
    this._tooltip()?._stateController?.close(immediate);
  }


  /** Keeps the tooltip on its item as the items are rendered again, and closes it if the item is gone. */
  private _updateTooltip(): void {
    if (this._tooltipKey === null) {
      return;
    }
    const element = this._itemElement(this._tooltipKey);
    const text = this._nodes.get(this._tooltipKey)?.item.tooltip;
    const tooltip = this._tooltip();
    if (!element || !text || this.disabled) {
      this._closeTooltip();
    } else if (tooltip) {
      pointTooltip(tooltip, element, text);
    }
  }

  /** Hovering over an item opens its tooltip; moving within the item leaves it as it is. */
  private _onMouseOver(event: MouseEvent): void {
    const element = (event.target as HTMLElement).closest<HTMLElement>('[role="treeitem"]');
    if (!element) {
      this._closeTooltip();
    } else if (element.dataset.key !== this._tooltipKey) {
      this._showTooltip(element, 'hover');
    }
  }

  /** Leaving the items closes the tooltip after its hide delay, as other tooltips do. */
  private _onMouseLeave(): void {
    this._closeTooltip(false);
  }

  /** The item an event happened on, if it happened on an item. */
  private _eventNode(event: Event): Node | undefined {
    const key = (event.target as HTMLElement).closest<HTMLElement>('[role="treeitem"]')?.dataset.key;
    return key === undefined ? undefined : this._nodes.get(key);
  }

  private _itemElement(key: string | null): HTMLElement | null {
    return key === null
      ? null
      : this.renderRoot.querySelector<HTMLElement>(`[role="treeitem"][data-key="${CSS.escape(key)}"]`);
  }

  /** Clicking the toggle of an item expands or collapses it; clicking elsewhere on it selects it. */
  private _onClick(event: MouseEvent): void {
    const node = this._eventNode(event);
    if (!node || this.disabled || this._isEditor(event)) {
      return;
    }
    if ((event.target as HTMLElement).closest('[part="toggle"]')) {
      this._toggle(node);
    } else {
      this._activate(node);
    }
  }

  /** Whether the user can move an item, by dragging it or cutting it. */
  private _canDrag(node: Node): boolean {
    return node.item.draggable && !node.item.disabled && !this.disabled && !this.readonly;
  }

  /** Whether an item can be dropped on a target: not on itself, nor into its descendants, nor on an item taking no drops. */
  private _canDrop(dragged: Node, target: Node): boolean {
    for (let current: Node | null = target; current; current = current.parent) {
      if (current === dragged) {
        return false;
      }
    }
    return target.item.droppable;
  }

  /** Where over an item the pointer is: its top quarter is before it, its bottom quarter after it, the rest into it. */
  private _dropLocation(event: DragEvent, element: HTMLElement): DropLocation {
    const { top, height } = element.getBoundingClientRect();
    const offset = (event.clientY - top) / height;
    return offset < 0.25 ? 'BEFORE' : offset > 0.75 ? 'AFTER' : 'INTO';
  }

  private _onDragStart(event: DragEvent): void {
    const node = this._eventNode(event);
    if (!node || !this._canDrag(node)) {
      event.preventDefault();
      return;
    }
    this._closeTooltip();
    this._draggedKey = node.item.key;
    if (event.dataTransfer) {
      event.dataTransfer.effectAllowed = 'move';
      // Some browsers only drag with data set
      event.dataTransfer.setData('text/plain', node.item.label);
    }
  }

  /** Accepts the drop where the dragged item can go, and shows where. */
  private _onDragOver(event: DragEvent): void {
    const dragged = this._draggedKey === null ? undefined : this._nodes.get(this._draggedKey);
    const target = this._eventNode(event);
    const element = (event.target as HTMLElement).closest<HTMLElement>('[role="treeitem"]');
    if (!dragged || !target || !element || !this._canDrop(dragged, target)) {
      this._dropTarget = null;
      return;
    }
    event.preventDefault();
    if (event.dataTransfer) {
      event.dataTransfer.dropEffect = 'move';
    }
    const location = this._dropLocation(event, element);
    if (this._dropTarget?.key !== target.item.key || this._dropTarget.location !== location) {
      this._dropTarget = { key: target.item.key, location };
    }
  }

  private _onDrop(event: DragEvent): void {
    const dragged = this._draggedKey === null ? undefined : this._nodes.get(this._draggedKey);
    const target = this._eventNode(event);
    const element = (event.target as HTMLElement).closest<HTMLElement>('[role="treeitem"]');
    if (dragged && target && element && this._canDrop(dragged, target)) {
      event.preventDefault();
      this.$server?.drop(dragged.item.key, target.item.key, this._dropLocation(event, element));
    }
    this._dropTarget = null;
  }

  private _onDragEnd(): void {
    this._draggedKey = null;
    this._dropTarget = null;
  }

  /**
   * Ctrl+X (or Cmd+X) cuts the focused item, saying how to move it; Ctrl+V pastes it
   * into the focused item, Ctrl+Shift+V before it. Tells whether it handled the key:
   * not when there is nothing to cut or paste, so that the page can have it.
   */
  private _onCutOrPaste(event: KeyboardEvent, node: Node): boolean {
    if (!(event.ctrlKey || event.metaKey) || event.altKey) {
      return false;
    }
    const key = event.key.toLowerCase();
    if (key === 'x' && !event.shiftKey && this._canDrag(node)) {
      this._cutKey = node.item.key;
      this.$server?.cut(node.item.key);
      announce(`${this.i18n.cut.replace('{0}', node.item.label)} ${this.i18n.cutHint}`);
      return true;
    }
    const cut = this._cutKey === null ? undefined : this._nodes.get(this._cutKey);
    if (key === 'v' && cut) {
      if (this._canDrag(cut) && this._canDrop(cut, node)) {
        this.$server?.drop(cut.item.key, node.item.key, event.shiftKey ? 'BEFORE' : 'INTO');
        this._cutKey = null;
      }
      return true;
    }
    return false;
  }

  /** Double-clicking an editable item, not its toggle, edits its label. */
  private _onDoubleClick(event: MouseEvent): void {
    const node = this._eventNode(event);
    if (node && !(event.target as HTMLElement).closest('[part="toggle"]') && !this._isEditor(event)) {
      this._startEditing(node);
    }
  }

  /** Whether an event happened in the text field that edits a label. */
  private _isEditor(event: Event): boolean {
    return (event.composedPath()[0] as HTMLElement).getAttribute?.('part') === 'editor';
  }

  private _editor(): HTMLInputElement | null {
    return this.renderRoot.querySelector<HTMLInputElement>('[part="editor"]');
  }

  /** Shows the text field for the label of an item, focused with the label selected, if the user can edit it. */
  private async _startEditing(node: Node): Promise<void> {
    if (!node.item.editable || this.disabled || this.readonly || node.item.disabled) {
      return;
    }
    this._closeTooltip();
    this._focusedKey = node.item.key;
    this._editingKey = node.item.key;
    await this.updateComplete;
    const editor = this._editor();
    editor?.focus();
    editor?.select();
  }

  /**
   * Stops editing: saves the label typed if it changed, showing it until the server
   * sends the item back, and gives focus back to the item unless it went elsewhere.
   */
  private async _stopEditing(save: boolean, refocus: boolean): Promise<void> {
    const key = this._editingKey;
    const node = key === null ? undefined : this._nodes.get(key);
    const label = this._editor()?.value;
    this._editingKey = null;
    if (save && node && label !== undefined && label !== node.item.label) {
      node.item = { ...node.item, label };
      this.$server?.edit(node.item.key, label);
    }
    if (refocus && key !== null) {
      await this.updateComplete;
      this._itemElement(key)?.focus();
    }
  }

  /**
   * Enter saves the label and Escape cancels, without closing a dialog; other keys are
   * the text field's, and the tree leaves them alone.
   */
  private _onEditorKeyDown(event: KeyboardEvent): void {
    if (event.key === 'Enter') {
      event.preventDefault();
      this._stopEditing(true, true);
    } else if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      this._stopEditing(false, true);
    }
  }

  /** Focus leaving the text field saves the label, unless editing already stopped. */
  private _onEditorBlur(): void {
    if (this._editingKey !== null) {
      this._stopEditing(true, false);
    }
  }

  private _select(node: Node): void {
    if (this.readonly || node.item.disabled || node.item.key === this._selectedKey) {
      return;
    }
    this._selectedKey = node.item.key;
    // The server confirms the selection path, or clears it if it cannot tell
    this._selectionPath = new Set(ancestors(node).map((ancestor) => ancestor.item.key));
    this.$server?.select(node.item.key);
  }

  /** Focusing an item with the keyboard opens its tooltip. */
  private _onFocusIn(event: FocusEvent): void {
    const target = event.target as HTMLElement;
    const key = target.dataset.key;
    if (key !== undefined) {
      this._focusedKey = key;
      this._focusRing = target.matches(':focus-visible');
      if (this._focusRing) {
        this._showTooltip(target, 'focus');
      }
    }
  }

  /** Forgets the focused item when focus leaves the tree, so that coming back goes to the selected item. */
  private _onFocusOut(event: FocusEvent): void {
    const next = event.relatedTarget as Element | null;
    if (!next || !(this.renderRoot.contains(next) || this.contains(next))) {
      this._focusedKey = null;
      this._focusRing = false;
      this._closeTooltip();
    }
  }

  private _onKeyDown(event: KeyboardEvent): void {
    const node = this._eventNode(event);
    if (!node || this.disabled || this._isEditor(event)) {
      return;
    }
    if (this._onCutOrPaste(event, node)) {
      event.preventDefault();
      return;
    }
    if (event.altKey || event.ctrlKey || event.metaKey) {
      return;
    }
    if (event.key === 'Escape' && this._cutKey !== null) {
      // Cancelling the cut is all Escape does, so that it does not also close a dialog
      this._cutKey = null;
      this.$server?.cut(null);
      event.preventDefault();
      event.stopPropagation();
      return;
    }
    if (event.key === 'Escape') {
      if (this._tooltip()?.opened) {
        // Closing the tooltip is all Escape does, so that it does not also close a dialog
        this._dismissTooltip();
        event.preventDefault();
        event.stopPropagation();
      }
      return;
    }
    const visible = this._visibleItems();
    const index = visible.indexOf(node);
    const rtl = this.matches(':dir(rtl)');
    const forward = rtl ? 'ArrowLeft' : 'ArrowRight';
    const backward = rtl ? 'ArrowRight' : 'ArrowLeft';
    let target: Node | undefined;
    if (event.key === 'ArrowDown') {
      target = visible[index + 1];
    } else if (event.key === 'ArrowUp') {
      target = visible[index - 1];
    } else if (event.key === 'Home') {
      target = visible[0];
    } else if (event.key === 'End') {
      target = visible[visible.length - 1];
    } else if (event.key === forward) {
      if (node.item.expandable && !node.expanded) {
        this._toggle(node);
      } else if (node.expanded) {
        target = node.children?.nodes[0];
      }
    } else if (event.key === backward) {
      if (node.expanded) {
        this._toggle(node);
      } else {
        target = node.parent ?? undefined;
      }
    } else if (event.key === 'Enter' || (event.key === ' ' && this._typed === '')) {
      this._activate(node);
    } else if (event.key === 'F2') {
      this._startEditing(node);
    } else if (event.key === '*') {
      this._expandSiblings(node);
    } else if ([...event.key].length === 1) {
      target = this._typeAhead(event.key, visible, index);
    } else {
      return;
    }
    event.preventDefault();
    if (target) {
      this._focusItem(target.item.key);
    }
  }

  /** Moves focus to an item, once rendered if virtual scrolling left it out; focusing scrolls it into view. */
  private _focusItem(key: string): void {
    this._focusedKey = key;
    const element = this._itemElement(key);
    if (element) {
      element.focus();
    } else {
      this.updateComplete.then(() => this._itemElement(key)?.focus());
    }
  }

  /**
   * Adds a typed character to the search, and finds the next visible item whose label
   * starts with it: from the focused item on, or after it when only one character, or
   * the same one again and again, was typed, which cycles through the items starting with it.
   */
  private _typeAhead(character: string, visible: Node[], index: number): Node | undefined {
    clearTimeout(this._typedTimeout);
    this._typedTimeout = setTimeout(() => (this._typed = ''), TYPE_AHEAD_TIMEOUT);
    this._typed += character.toLocaleLowerCase();
    const typed = [...this._typed];
    const repeated = typed.every((typedCharacter) => typedCharacter === typed[0]);
    const prefix = repeated ? typed[0] : this._typed;
    const start = repeated ? index + 1 : index;
    for (let i = 0; i < visible.length; i++) {
      const candidate = visible[(start + i) % visible.length];
      if (candidate.item.label.toLocaleLowerCase().startsWith(prefix)) {
        return candidate;
      }
    }
    return undefined;
  }
}

if (!customElements.get('tfv-tree')) {
  customElements.define('tfv-tree', TfvTree);
}

declare global {
  interface HTMLElementTagNameMap {
    'tfv-tree': TfvTree;
  }
}
