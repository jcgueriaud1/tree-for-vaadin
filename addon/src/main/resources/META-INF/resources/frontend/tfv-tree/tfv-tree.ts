import { css, html, LitElement, type PropertyValues } from 'lit';

/** An item as the server sends it: the key to refer to it by and its label. */
export interface TreeItem {
  key: string;
  label: string;
}

/** A page of child items, with the number of children the parent has in all. */
export interface ItemPage {
  items: TreeItem[];
  size: number;
}

/** The server side of the tree, see Tree.java. */
export interface TreeServer {
  fetchChildren(parentKey: string | null, offset: number, limit: number): Promise<ItemPage>;
}

/** How many items are fetched at once. */
export const PAGE_SIZE = 50;

/**
 * An accessible tree: the element is the ARIA `tree` and each item a `treeitem`.
 *
 * Items are fetched from the server a page at a time through `$server.fetchChildren`
 * (ADR-0001). One item at a time is in the tab order (roving tabindex); the arrow,
 * Home and End keys move focus between items.
 */
export class TfvTree extends LitElement {
  static override properties = {
    disabled: { type: Boolean, reflect: true },
    _items: { state: true },
    _focusedIndex: { state: true },
  };

  static override styles = css`
    :host {
      display: block;
      outline: none;
    }

    :host([hidden]) {
      display: none !important;
    }

    :host([disabled]) {
      opacity: 0.5;
      pointer-events: none;
    }

    [part='item'] {
      display: block;
      padding: 0.25em 0.5em;
      border-radius: 0.25em;
      cursor: default;
    }

    [part='item']:focus-visible {
      outline: 2px solid var(--vaadin-focus-ring-color, var(--lumo-primary-color-50pct, Highlight));
      outline-offset: -2px;
    }
  `;

  declare disabled: boolean;
  declare $server?: TreeServer;
  declare _items: TreeItem[];
  declare _focusedIndex: number;

  /** Ignores the answers to fetches made before the latest refresh. */
  private _generation = 0;

  constructor() {
    super();
    this.disabled = false;
    this._items = [];
    this._focusedIndex = 0;
  }

  override connectedCallback(): void {
    super.connectedCallback();
    if (!this.hasAttribute('role')) {
      this.setAttribute('role', 'tree');
    }
  }

  /** Drops the items shown and fetches the root items again. Called by the server. */
  async refresh(): Promise<void> {
    const generation = ++this._generation;
    const server = this.$server;
    if (!server) {
      return;
    }
    const page = await server.fetchChildren(null, 0, PAGE_SIZE);
    if (generation !== this._generation) {
      return;
    }
    this._items = page.items;
    this._focusedIndex = Math.min(this._focusedIndex, Math.max(page.items.length - 1, 0));
  }

  /** Focuses the item in the tab order. */
  override focus(options?: FocusOptions): void {
    const item = this._itemElements()[this._focusedIndex];
    if (item) {
      item.focus(options);
    } else {
      super.focus(options);
    }
  }

  protected override firstUpdated(): void {
    this.refresh();
  }

  protected override updated(changed: PropertyValues<this>): void {
    if (changed.has('disabled')) {
      if (this.disabled) {
        this.setAttribute('aria-disabled', 'true');
      } else {
        this.removeAttribute('aria-disabled');
      }
    }
  }

  protected override render() {
    const count = this._items.length;
    return html`
      <div role="none" @keydown=${this._onKeyDown} @focusin=${this._onFocusIn}>
        ${this._items.map(
          (item, index) => html`
            <div
              part="item"
              role="treeitem"
              aria-level="1"
              aria-posinset=${index + 1}
              aria-setsize=${count}
              tabindex=${this.disabled ? -1 : index === this._focusedIndex ? 0 : -1}
              data-index=${index}
            >
              ${item.label}
            </div>
          `,
        )}
      </div>
      <slot name="tooltip"></slot>
    `;
  }

  private _itemElements(): HTMLElement[] {
    return Array.from(this.renderRoot.querySelectorAll<HTMLElement>('[role="treeitem"]'));
  }

  private _onFocusIn(event: FocusEvent): void {
    const index = (event.target as HTMLElement).dataset.index;
    if (index !== undefined) {
      this._focusedIndex = Number(index);
    }
  }

  private _onKeyDown(event: KeyboardEvent): void {
    const last = this._items.length - 1;
    const target = {
      ArrowDown: Math.min(this._focusedIndex + 1, last),
      ArrowUp: Math.max(this._focusedIndex - 1, 0),
      Home: 0,
      End: last,
    }[event.key];
    if (target === undefined || last < 0) {
      return;
    }
    event.preventDefault();
    this._focusedIndex = target;
    this._itemElements()[target]?.focus();
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
