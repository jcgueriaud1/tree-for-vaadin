import { css, html, LitElement, nothing, type PropertyValues } from 'lit';
import { repeat } from 'lit/directives/repeat.js';
import { announce } from '@vaadin/a11y-base/src/announce.js';

/**
 * An item as the server sends it: the key to refer to it by, its label, its icon and
 * tooltip if any, whether it has child items, whether it is expanded, whether it is
 * the selected item and whether it is on the selection path.
 */
export interface TreeItem {
  key: string;
  label: string;
  /** The name of a `<vaadin-icon>`, such as `vaadin:folder`. */
  icon: string | null;
  tooltip: string | null;
  expandable: boolean;
  expanded: boolean;
  selected: boolean;
  onSelectionPath: boolean;
}

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
}

export const DEFAULT_I18N: TreeI18n = {
  loading: 'Loading…',
  empty: 'No items',
  loadError: 'Could not load the items',
};

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
 * `aria-posinset` and `aria-setsize`.
 *
 * One item at a time is in the tab order (roving tabindex): the focused item, or when
 * focus enters the tree, the selected item if visible, else the first item. The keys
 * follow the ARIA tree pattern, selection not following focus: Up and Down move focus
 * between visible items, Home and End to the first and last; Right expands an item or
 * moves to its first child item, Left collapses it or moves to its parent (swapped
 * right to left); Enter and Space select the focused item; `*` expands its siblings;
 * typing moves to the next visible item whose label starts with what was typed.
 *
 * Clicking an item selects it, and tells `$server.select`; the server sets the
 * selection with `setSelection`. The ancestors of the selected item have the
 * `selection-path` attribute and a `selection-path-marker` part.
 *
 * An item can have an icon, a decorative `<vaadin-icon>` hidden from assistive
 * technology, and a tooltip: one `<vaadin-tooltip>` follows the item hovered or
 * focused with the keyboard. The tooltip is hidden from assistive technology, as a
 * tree only holds treeitems; its text is the item's `aria-describedby` instead, from a
 * hidden copy, so the item is described whether or not the tooltip shows.
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
    i18n: { type: Object, attribute: false },
    _focusedKey: { state: true },
    _focusRing: { state: true },
    _selectedKey: { state: true },
    _selectionPath: { state: true },
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
    }

    @media (any-hover: hover) {
      [part~='item']:not([selected]):hover {
        background: var(--tfv-tree-item-hover-background);
      }
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

    [part='icon'] {
      flex: none;
      width: var(--tfv-tree-icon-size);
      height: var(--tfv-tree-icon-size);
      color: var(--tfv-tree-icon-color);
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
    }
  `;

  declare disabled: boolean;
  /** Set by the server when the value is read-only: the user cannot change the selection. */
  declare readonly: boolean;
  declare pageSize: number;
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

  private _root: Level = newLevel(null);
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
    this.i18n = DEFAULT_I18N;
    this._focusedKey = null;
    this._focusRing = false;
    this._selectedKey = null;
    this._selectionPath = new Set();
  }

  override connectedCallback(): void {
    super.connectedCallback();
    if (!this.hasAttribute('role')) {
      this.setAttribute('role', 'tree');
    }
    this._loadingRowObserver = new IntersectionObserver((entries) => this._onLoadingRowsVisible(entries), {
      rootMargin: '0px 0px 100px 0px',
    });
  }

  override disconnectedCallback(): void {
    this._loadingRowObserver?.disconnect();
    this._loadingRowObserver = undefined;
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
    // The items fetched again tell which are selected
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
    await this.updateComplete;
    if (node) {
      this._itemElement(node.item.key)?.scrollIntoView({ block: 'nearest' });
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
  }

  protected override render() {
    const rows = this._rows();
    const tabbableKey = this._tabbableKey(rows);
    return html`
      <div
        role="none"
        @keydown=${this._onKeyDown}
        @focusin=${this._onFocusIn}
        @focusout=${this._onFocusOut}
        @click=${this._onClick}
        @mouseover=${this._onMouseOver}
        @mouseleave=${this._onMouseLeave}
        @mousedown=${this._dismissTooltip}
      >
        ${repeat(
          rows,
          (row) =>
            row.kind === 'item'
              ? row.node.item.key
              : row.kind === 'loading'
                ? `loading:${row.level.parent?.item.key ?? ''}`
                : `${row.kind}:${row.parentKey}`,
          (row) =>
            row.kind === 'item'
              ? this._renderItem(row, tabbableKey)
              : row.kind === 'loading'
                ? this._renderLoading(row)
                : this._renderMessage(row),
        )}
      </div>
      <div hidden>
        ${rows.map((row) =>
          row.kind === 'item' && row.node.item.tooltip
            ? html`<span id=${this._descriptionId(row.node.item.key)}>${row.node.item.tooltip}</span>`
            : nothing,
        )}
      </div>
      <!-- A tree only holds treeitems: the tooltip is for the eyes, the hidden copies above describe the items -->
      <vaadin-tooltip manual aria-hidden="true" .ariaTarget=${null}></vaadin-tooltip>
      <slot name="tooltip"></slot>
    `;
  }

  private _renderItem(row: Extract<Row, { kind: 'item' }>, tabbableKey: string | null) {
    const { node } = row;
    const { item } = node;
    const selected = item.key === this._selectedKey;
    const onSelectionPath = this._selectionPath.has(item.key);
    const expanded = item.expandable && node.expanded;
    const focused = item.key === this._focusedKey;
    // The states are part names too, as ::part() cannot be followed by an attribute selector
    const states = {
      expanded,
      selected,
      'selection-path': onSelectionPath,
      focused,
      'focus-ring': focused && this._focusRing,
    };
    const part = ['item', ...Object.entries(states).flatMap(([state, on]) => (on ? [state] : []))].join(' ');
    return html`
      <div
        part=${part}
        role="treeitem"
        aria-level=${node.depth}
        aria-posinset=${row.position}
        aria-setsize=${row.setSize}
        aria-expanded=${item.expandable ? String(node.expanded) : nothing}
        aria-busy=${node.expanded && node.children?.loading ? 'true' : nothing}
        aria-selected=${String(selected)}
        aria-describedby=${item.tooltip ? this._descriptionId(item.key) : nothing}
        ?expanded=${expanded}
        ?selected=${selected}
        ?selection-path=${onSelectionPath}
        ?focused=${focused}
        ?focus-ring=${states['focus-ring']}
        tabindex=${!this.disabled && item.key === tabbableKey ? 0 : -1}
        data-key=${item.key}
        style="--_level: ${node.depth - 1}"
      >
        ${item.expandable
          ? html`<span part="toggle" aria-hidden="true"></span>`
          : html`<span part="toggle-spacer" aria-hidden="true"></span>`}
        ${item.icon ? html`<vaadin-icon part="icon" icon=${item.icon} aria-hidden="true"></vaadin-icon>` : nothing}
        <span part="label">${item.label}</span>
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
    return node;
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
    if (!node || this.disabled) {
      return;
    }
    if ((event.target as HTMLElement).closest('[part="toggle"]')) {
      this._toggle(node);
    } else {
      this._select(node);
    }
  }

  private _select(node: Node): void {
    if (this.readonly || node.item.key === this._selectedKey) {
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
    if (!node || this.disabled || event.altKey || event.ctrlKey || event.metaKey) {
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
      this._select(node);
    } else if (event.key === '*') {
      this._expandSiblings(node);
    } else if ([...event.key].length === 1) {
      target = this._typeAhead(event.key, visible, index);
    } else {
      return;
    }
    event.preventDefault();
    if (target) {
      this._focusedKey = target.item.key;
      this._itemElement(target.item.key)?.focus();
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
