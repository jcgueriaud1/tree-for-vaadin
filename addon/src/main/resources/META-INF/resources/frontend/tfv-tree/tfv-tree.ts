import { css, html, LitElement, nothing, type PropertyValues } from 'lit';
import { repeat } from 'lit/directives/repeat.js';

/**
 * An item as the server sends it: the key to refer to it by, its label, whether it
 * has child items, whether it is expanded, whether it is the selected item and
 * whether it is on the selection path.
 */
export interface TreeItem {
  key: string;
  label: string;
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
  setExpanded(key: string, expanded: boolean): unknown;
  select(key: string): unknown;
}

/** How many items are fetched at once, unless the server sets `pageSize`. */
export const PAGE_SIZE = 50;

/** The text shown while the items of a level are being fetched. */
const LOADING = 'Loading…';

/** The child items of a parent (or the root items) fetched so far. */
interface Level {
  parent: Node | null;
  /** The items fetched so far: always the first ones, as pages are fetched in order. */
  nodes: Node[];
  /** How many items the level has in all, or -1 before its first page arrives. */
  size: number;
  /** The page being fetched, if any. */
  loading: Promise<void> | null;
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

/** A row of the tree: a visible item, or the row standing for the items of a level not fetched yet. */
type Row =
  | { kind: 'item'; node: Node; position: number; setSize: number }
  | { kind: 'loading'; level: Level; depth: number };

function newLevel(parent: Node | null): Level {
  return { parent, nodes: [], size: -1, loading: null };
}

/** Tells whether there are items of the level still to fetch. */
function hasMore(level: Level): boolean {
  return level.size < 0 || level.nodes.length < level.size;
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
 * One item at a time is in the tab order (roving tabindex); the arrow, Home and End
 * keys move focus between visible items.
 *
 * Clicking an item selects it, and tells `$server.select`; the server sets the
 * selection with `setSelection`. The ancestors of the selected item have the
 * `selection-path` attribute and a `selection-path-marker` part.
 */
export class TfvTree extends LitElement {
  static override properties = {
    disabled: { type: Boolean, reflect: true },
    readonly: { type: Boolean },
    pageSize: { type: Number },
    _focusedKey: { state: true },
    _selectedKey: { state: true },
    _selectionPath: { state: true },
  };

  static override styles = css`
    :host {
      display: block;
      overflow: auto;
      outline: none;
      --_indent: 1.25em;
    }

    :host([hidden]) {
      display: none !important;
    }

    :host([disabled]) {
      opacity: 0.5;
      pointer-events: none;
    }

    [part='item'] {
      display: flex;
      align-items: center;
      gap: 0.25em;
      padding-block: 0.25em;
      padding-inline: calc(var(--_level) * var(--_indent) + 0.25em) 0.5em;
      border-radius: 0.25em;
      cursor: default;
    }

    [part='item'][selected] {
      background: var(--vaadin-tree-selected-background, color-mix(in srgb, currentColor 12%, transparent));
    }

    [part='item']:focus-visible {
      outline: 2px solid var(--vaadin-focus-ring-color, var(--lumo-primary-color-50pct, Highlight));
      outline-offset: -2px;
    }

    [part='toggle'],
    [part='toggle-spacer'] {
      flex: none;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 1em;
      height: 1em;
    }

    [part='toggle'] {
      cursor: pointer;
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

    [part='item'][expanded] > [part='toggle']::before {
      transform: rotate(45deg);
    }

    /* A dot after the label of each ancestor of the selected item */
    [part='selection-path-marker'] {
      flex: none;
      width: 0.4em;
      height: 0.4em;
      border-radius: 50%;
      background: currentColor;
    }

    @media (forced-colors: active) {
      [part='item'][selected] {
        forced-color-adjust: none;
        background: Highlight;
        color: HighlightText;
      }

      [part='selection-path-marker'] {
        background: CanvasText;
      }
    }

    [part='loading'] {
      padding-block: 0.25em;
      padding-inline-start: calc(var(--_level) * var(--_indent) + 1.5em);
      font-size: 0.875em;
      opacity: 0.7;
    }
  `;

  declare disabled: boolean;
  /** Set by the server when the value is read-only: the user cannot change the selection. */
  declare readonly: boolean;
  declare pageSize: number;
  declare $server?: TreeServer;
  declare _focusedKey: string | null;
  /** The key of the selected item, which may not be fetched yet. */
  declare _selectedKey: string | null;
  /** The keys of the ancestors of the selected item. */
  declare _selectionPath: ReadonlySet<string>;

  private _root: Level = newLevel(null);
  private _nodes = new Map<string, Node>();

  /** Ignores the answers to fetches made before the latest refresh. */
  private _generation = 0;

  /** Fetches the next page of a level when its loading row scrolls into view. */
  private _loadingRowObserver?: IntersectionObserver;

  constructor() {
    super();
    this.disabled = false;
    this.readonly = false;
    this.pageSize = PAGE_SIZE;
    this._focusedKey = null;
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
    super.disconnectedCallback();
  }

  /** Drops the items shown and fetches the root items again. Called by the server. */
  async refresh(): Promise<void> {
    this._generation++;
    this._root = newLevel(null);
    this._nodes.clear();
    // The items fetched again tell which are selected
    this._selectedKey = null;
    this._selectionPath = new Set();
    await this._fetchNextPage(this._root);
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
    this._itemElement(node!.item.key)?.scrollIntoView({ block: 'nearest' });
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
      <div role="none" @keydown=${this._onKeyDown} @focusin=${this._onFocusIn} @click=${this._onClick}>
        ${repeat(
          rows,
          (row) => (row.kind === 'item' ? row.node.item.key : `loading:${row.level.parent?.item.key ?? ''}`),
          (row) => (row.kind === 'item' ? this._renderItem(row, tabbableKey) : this._renderLoading(row)),
        )}
      </div>
      <slot name="tooltip"></slot>
    `;
  }

  private _renderItem(row: Extract<Row, { kind: 'item' }>, tabbableKey: string | null) {
    const { node } = row;
    const { item } = node;
    const selected = item.key === this._selectedKey;
    const onSelectionPath = this._selectionPath.has(item.key);
    return html`
      <div
        part="item"
        role="treeitem"
        aria-level=${node.depth}
        aria-posinset=${row.position}
        aria-setsize=${row.setSize}
        aria-expanded=${item.expandable ? String(node.expanded) : nothing}
        aria-busy=${node.expanded && node.children?.loading ? 'true' : nothing}
        aria-selected=${String(selected)}
        ?expanded=${item.expandable && node.expanded}
        ?selected=${selected}
        ?selection-path=${onSelectionPath}
        tabindex=${!this.disabled && item.key === tabbableKey ? 0 : -1}
        data-key=${item.key}
        style="--_level: ${node.depth - 1}"
      >
        ${item.expandable
          ? html`<span part="toggle" aria-hidden="true"></span>`
          : html`<span part="toggle-spacer" aria-hidden="true"></span>`}
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
        ${LOADING}
      </div>
    `;
  }

  /** The rows to show: the visible items in tree order, each level followed by its loading row if it has more items. */
  private _rows(): Row[] {
    const rows: Row[] = [];
    const addLevel = (level: Level, depth: number) => {
      level.nodes.forEach((node, index) => {
        rows.push({ kind: 'item', node, position: index + 1, setSize: level.size });
        if (node.expanded && node.children) {
          addLevel(node.children, depth + 1);
        }
      });
      if (hasMore(level)) {
        rows.push({ kind: 'loading', level, depth });
      }
    };
    addLevel(this._root, 1);
    return rows;
  }

  /** The key of the item in the tab order: the focused item if visible, else the first item. */
  private _tabbableKey(rows: Row[] = this._rows()): string | null {
    const items = rows.flatMap((row) => (row.kind === 'item' ? [row.node.item.key] : []));
    return this._focusedKey !== null && items.includes(this._focusedKey) ? this._focusedKey : (items[0] ?? null);
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
      const page = await server.fetchChildren(level.parent?.item.key ?? null, level.nodes.length, this.pageSize);
      if (generation !== this._generation) {
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
    this.$server?.setExpanded(node.item.key, expanded);
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

  private _itemElement(key: string | null): HTMLElement | null {
    return key === null
      ? null
      : this.renderRoot.querySelector<HTMLElement>(`[role="treeitem"][data-key="${CSS.escape(key)}"]`);
  }

  private _itemElements(): HTMLElement[] {
    return Array.from(this.renderRoot.querySelectorAll<HTMLElement>('[role="treeitem"]'));
  }

  /** Clicking the toggle of an item expands or collapses it; clicking elsewhere on it selects it. */
  private _onClick(event: MouseEvent): void {
    const target = event.target as HTMLElement;
    const key = target.closest<HTMLElement>('[role="treeitem"]')?.dataset.key;
    const node = key === undefined ? undefined : this._nodes.get(key);
    if (!node || this.disabled) {
      return;
    }
    if (target.closest('[part="toggle"]')) {
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

  private _onFocusIn(event: FocusEvent): void {
    const key = (event.target as HTMLElement).dataset.key;
    if (key !== undefined) {
      this._focusedKey = key;
    }
  }

  private _onKeyDown(event: KeyboardEvent): void {
    const items = this._itemElements();
    const last = items.length - 1;
    const current = Math.max(
      items.findIndex((item) => item.dataset.key === this._tabbableKey()),
      0,
    );
    const target = {
      ArrowDown: Math.min(current + 1, last),
      ArrowUp: Math.max(current - 1, 0),
      Home: 0,
      End: last,
    }[event.key];
    if (target === undefined || last < 0) {
      return;
    }
    event.preventDefault();
    this._focusedKey = items[target].dataset.key ?? null;
    items[target].focus();
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
