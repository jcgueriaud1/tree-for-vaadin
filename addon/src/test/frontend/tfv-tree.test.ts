import { expect, fixture, html, nextFrame, waitUntil } from '@open-wc/testing';
import { resetMouse, sendKeys, sendMouse } from '@web/test-runner-commands';
import { Tooltip } from '@vaadin/tooltip/src/vaadin-tooltip.js';
import {
  DEFAULT_I18N,
  type ItemPage,
  LOAD_ERROR_DURATION,
  PAGE_SIZE,
  TYPE_AHEAD_TIMEOUT,
  type TfvTree,
  type TreeServer,
} from '../../main/resources/META-INF/resources/frontend/tfv-tree/tfv-tree.ts';

/** An item of the fake server's tree: a label, or a label with child items. */
type FakeItem = string | [label: string, children: FakeItem[], expanded?: boolean];

type FakeServer = TreeServer & {
  /** The fetches made, as [parentKey, offset, limit]. */
  calls: unknown[][];
  /** The setExpanded calls made, as [key, expanded]. */
  expansions: unknown[][];
  /** The keys of the select calls made. */
  selections: string[];
  /** The key of the item the server has selected, and the keys of its ancestors. */
  selectedKey: string | null;
  selectionPath: string[];
  /** The icon and tooltip of items, by label; items not in them have none. */
  icons: Record<string, string>;
  tooltips: Record<string, string>;
  /** The keys of the parents whose child items fail to load, null for the root items. */
  failing: (string | null)[];
  /** While true, fetches wait until release() is called. */
  hold: boolean;
  release(): void;
};

/**
 * A server holding the given tree of items, recording the calls it gets. The key of an
 * item is its path of indexes, such as "0/1" for the second child of the first root item.
 */
function serverWith(roots: FakeItem[]): FakeServer {
  const children = (key: string | null): FakeItem[] => {
    if (key === null) {
      return roots;
    }
    let level = roots;
    for (const index of key.split('/').map(Number)) {
      const item = level[index];
      level = typeof item === 'string' ? [] : item[1];
    }
    return level;
  };
  let waiting: (() => void)[] = [];
  const server: FakeServer = {
    calls: [],
    expansions: [],
    selections: [],
    selectedKey: null,
    selectionPath: [],
    icons: {},
    tooltips: {},
    failing: [],
    hold: false,
    release() {
      const resolve = waiting;
      waiting = [];
      resolve.forEach((fn) => fn());
    },
    async fetchChildren(parentKey, offset, limit): Promise<ItemPage> {
      server.calls.push([parentKey, offset, limit]);
      if (server.hold) {
        await new Promise<void>((resolve) => waiting.push(resolve));
      }
      if (server.failing.includes(parentKey)) {
        throw new Error(`Cannot load ${parentKey}`);
      }
      const level = children(parentKey);
      const items = level.slice(offset, offset + limit).map((item, i) => {
        const key = parentKey === null ? String(offset + i) : `${parentKey}/${offset + i}`;
        const label = typeof item === 'string' ? item : item[0];
        const extras = {
          icon: server.icons[label] ?? null,
          tooltip: server.tooltips[label] ?? null,
          selected: key === server.selectedKey,
          onSelectionPath: server.selectionPath.includes(key),
        };
        return typeof item === 'string'
          ? { key, label, expandable: false, expanded: false, ...extras }
          : { key, label, expandable: item[1].length > 0, expanded: item[2] ?? false, ...extras };
      });
      return { items, size: level.length };
    },
    setExpanded(expanded, ...keys) {
      keys.forEach((key) => server.expansions.push([key, expanded]));
    },
    select(key) {
      server.selections.push(key);
    },
  };
  return server;
}

async function treeWith(server: TreeServer, setUp: (tree: TfvTree) => void = () => {}): Promise<TfvTree> {
  const tree = document.createElement('tfv-tree');
  tree.$server = server;
  tree.setAttribute('aria-label', 'Folders');
  setUp(tree);
  await fixture(html`<div>${tree}</div>`);
  await settle(tree);
  return tree;
}

/** Waits for the fetches under way to be answered and shown. */
async function settle(tree: TfvTree): Promise<void> {
  for (let i = 0; i < 3; i++) {
    await nextFrame();
    await tree.updateComplete;
  }
}

function treeItems(tree: TfvTree): HTMLElement[] {
  return Array.from(tree.shadowRoot!.querySelectorAll<HTMLElement>('[role="treeitem"]'));
}

function labels(tree: TfvTree): string[] {
  return treeItems(tree).map((item) => item.textContent!.trim());
}

function item(tree: TfvTree, label: string): HTMLElement {
  const found = treeItems(tree).find((element) => element.textContent!.trim() === label);
  if (!found) {
    throw new Error(`No item ${label} in ${labels(tree)}`);
  }
  return found;
}

function loadingRows(tree: TfvTree): HTMLElement[] {
  return Array.from(tree.shadowRoot!.querySelectorAll<HTMLElement>('[part="loading"]'));
}

function messageRows(tree: TfvTree, part: 'empty' | 'error'): HTMLElement[] {
  return Array.from(tree.shadowRoot!.querySelectorAll<HTMLElement>(`[part="${part}"]`));
}

/** Waits for Vaadin's live region to announce the given text as an alert. */
async function announced(text: string): Promise<void> {
  await waitUntil(
    () => Array.from(document.body.children).some((element) => element.getAttribute('role') === 'alert' && element.textContent === text),
    `"${text}" announced`,
  );
}

async function clickLabel(tree: TfvTree, label: string): Promise<void> {
  item(tree, label).querySelector<HTMLElement>('[part="label"]')!.click();
  await settle(tree);
}

/** The accessible description of an item: the texts its `aria-describedby` points at, in order. */
function description(tree: TfvTree, label: string): string | undefined {
  const ids = item(tree, label).getAttribute('aria-describedby');
  return ids === null
    ? undefined
    : ids
        .split(' ')
        .map((id) => tree.shadowRoot!.getElementById(id)?.textContent ?? '')
        .join(' ');
}

function selectedLabels(tree: TfvTree): string[] {
  return treeItems(tree)
    .filter((element) => element.getAttribute('aria-selected') === 'true')
    .map((element) => element.textContent!.trim());
}

function selectionPathLabels(tree: TfvTree): string[] {
  return treeItems(tree)
    .filter((element) => element.hasAttribute('selection-path'))
    .map((element) => element.textContent!.trim());
}

async function clickToggle(tree: TfvTree, label: string): Promise<void> {
  item(tree, label).querySelector<HTMLElement>('[part="toggle"]')!.click();
  await settle(tree);
}

/** Presses a key on the focused item. */
async function press(tree: TfvTree, key: string): Promise<void> {
  const focused = tree.shadowRoot!.activeElement ?? treeItems(tree)[0];
  focused.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, composed: true, cancelable: true }));
  await settle(tree);
}

function focusedLabel(tree: TfvTree): string | undefined {
  return tree.shadowRoot!.activeElement?.textContent!.trim();
}

const FOLDERS: FakeItem[] = [['Documents', ['Invoices', ['Letters', ['2025', '2026']]]], 'Music', 'Pictures'];

describe('tfv-tree', () => {
  it('is an ARIA tree', async () => {
    const tree = await treeWith(serverWith([]));

    expect(tree.getAttribute('role')).to.equal('tree');
  });

  it('fetches the first page of root items from the server', async () => {
    const server = serverWith(['Documents', 'Music']);

    await treeWith(server);

    expect(server.calls).to.deep.equal([[null, 0, PAGE_SIZE]]);
  });

  it('fetches pages of the size the server sets', async () => {
    const server = serverWith(['Documents', 'Music']);

    await treeWith(server, (tree) => (tree.pageSize = 10));

    expect(server.calls).to.deep.equal([[null, 0, 10]]);
  });

  it('shows each root item as a treeitem at level 1', async () => {
    const tree = await treeWith(serverWith(['Documents', 'Music']));

    const items = treeItems(tree);
    expect(labels(tree)).to.deep.equal(['Documents', 'Music']);
    expect(items.map((element) => element.getAttribute('aria-level'))).to.deep.equal(['1', '1']);
    expect(items.map((element) => element.getAttribute('aria-posinset'))).to.deep.equal(['1', '2']);
    expect(items.map((element) => element.getAttribute('aria-setsize'))).to.deep.equal(['2', '2']);
  });

  it('puts one item in the tab order and moves focus with the arrow keys', async () => {
    const tree = await treeWith(serverWith(['Documents', 'Music', 'Pictures']));

    expect(treeItems(tree).map((element) => element.tabIndex)).to.deep.equal([0, -1, -1]);

    tree.focus();
    treeItems(tree)[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, composed: true }));
    await tree.updateComplete;

    expect(tree.shadowRoot!.activeElement).to.equal(treeItems(tree)[1]);
    expect(treeItems(tree).map((element) => element.tabIndex)).to.deep.equal([-1, 0, -1]);

    treeItems(tree)[1].dispatchEvent(new KeyboardEvent('keydown', { key: 'End', bubbles: true, composed: true }));
    await tree.updateComplete;
    expect(tree.shadowRoot!.activeElement).to.equal(treeItems(tree)[2]);
  });

  it('shows the root items again when the server refreshes it', async () => {
    const roots: FakeItem[] = ['Documents'];
    const tree = await treeWith(serverWith(roots));

    roots.push('Music');
    await tree.refresh();
    await tree.updateComplete;

    expect(labels(tree)).to.deep.equal(['Documents', 'Music']);
  });

  it('takes its items out of the tab order while disabled', async () => {
    const tree = await treeWith(serverWith(['Documents']));

    tree.toggleAttribute('disabled', true);
    await tree.updateComplete;

    expect(tree.getAttribute('aria-disabled')).to.equal('true');
    expect(treeItems(tree)[0].tabIndex).to.equal(-1);
  });

  it('has no accessibility violations', async () => {
    const tree = await treeWith(serverWith(['Documents', 'Music']));

    await expect(tree).to.be.accessible();
  });

  describe('expanding and collapsing', () => {
    it('gives a toggle and aria-expanded only to items with children', async () => {
      const tree = await treeWith(serverWith(FOLDERS));

      expect(item(tree, 'Documents').getAttribute('aria-expanded')).to.equal('false');
      expect(item(tree, 'Documents').querySelector('[part="toggle"]')).to.exist;
      expect(item(tree, 'Music').hasAttribute('aria-expanded')).to.be.false;
      expect(item(tree, 'Music').querySelector('[part="toggle"]')).to.not.exist;
    });

    it('takes clicks on the toggle within a 24px square around the chevron (WCAG 2.5.8)', async () => {
      const server = serverWith(FOLDERS);
      const tree = await treeWith(server);
      const toggle = item(tree, 'Documents').querySelector<HTMLElement>('[part="toggle"]')!;
      const { x, y, width, height } = toggle.getBoundingClientRect();
      const [centerX, centerY] = [x + width / 2, y + height / 2];

      for (const [dx, dy] of [[-11, -11], [11, -11], [-11, 11], [11, 11]]) {
        const hit = tree.shadowRoot!.elementFromPoint(centerX + dx, centerY + dy);
        expect(hit === toggle, `the toggle is hit at ${dx}, ${dy}, not ${hit?.getAttribute('part')}`).to.be.true;
      }

      await sendMouse({ type: 'click', position: [Math.round(centerX - 11), Math.round(centerY + 11)] });
      await settle(tree);
      await resetMouse();
      expect(server.expansions).to.deep.equal([['0', true]]);
      expect(server.selections).to.deep.equal([]);
    });

    it('fetches the child items when an item is first expanded, and shows them under it', async () => {
      const server = serverWith(FOLDERS);
      const tree = await treeWith(server);

      await clickToggle(tree, 'Documents');

      expect(server.calls).to.deep.include(['0', 0, PAGE_SIZE]);
      expect(server.expansions).to.deep.equal([['0', true]]);
      expect(labels(tree)).to.deep.equal(['Documents', 'Invoices', 'Letters', 'Music', 'Pictures']);
      expect(item(tree, 'Documents').getAttribute('aria-expanded')).to.equal('true');
      const letters = item(tree, 'Letters');
      expect(letters.getAttribute('aria-level')).to.equal('2');
      expect(letters.getAttribute('aria-posinset')).to.equal('2');
      expect(letters.getAttribute('aria-setsize')).to.equal('2');
    });

    it('hides the child items when collapsed, and shows them again without fetching them again', async () => {
      const server = serverWith(FOLDERS);
      const tree = await treeWith(server);
      await clickToggle(tree, 'Documents');

      await clickToggle(tree, 'Documents');
      expect(labels(tree)).to.deep.equal(['Documents', 'Music', 'Pictures']);
      expect(item(tree, 'Documents').getAttribute('aria-expanded')).to.equal('false');
      expect(server.expansions).to.deep.equal([
        ['0', true],
        ['0', false],
      ]);

      const fetches = server.calls.length;
      await clickToggle(tree, 'Documents');
      expect(labels(tree)).to.deep.equal(['Documents', 'Invoices', 'Letters', 'Music', 'Pictures']);
      expect(server.calls.length).to.equal(fetches);
    });

    it('is busy, with a loading row, while the child items are being fetched', async () => {
      const server = serverWith(FOLDERS);
      const tree = await treeWith(server);
      server.hold = true;

      await clickToggle(tree, 'Documents');

      expect(item(tree, 'Documents').getAttribute('aria-busy')).to.equal('true');
      expect(loadingRows(tree).map((row) => row.textContent!.trim())).to.deep.equal(['Loading…']);
      expect(loadingRows(tree)[0].getAttribute('aria-hidden')).to.equal('true');
      await expect(tree).to.be.accessible();

      server.release();
      await settle(tree);

      expect(item(tree, 'Documents').hasAttribute('aria-busy')).to.be.false;
      expect(loadingRows(tree)).to.be.empty;
    });

    it('is busy while the root items are being fetched', async () => {
      const server = serverWith(FOLDERS);
      server.hold = true;
      const tree = await treeWith(server);

      expect(tree.getAttribute('aria-busy')).to.equal('true');

      server.release();
      await settle(tree);
      expect(tree.hasAttribute('aria-busy')).to.be.false;
    });

    it('shows the child items of items the server says are expanded', async () => {
      const server = serverWith([['Documents', ['Invoices', ['Letters', ['2025'], true]], true], 'Music']);
      const tree = await treeWith(server);
      await settle(tree);

      expect(labels(tree)).to.deep.equal(['Documents', 'Invoices', 'Letters', '2025', 'Music']);
      expect(item(tree, '2025').getAttribute('aria-level')).to.equal('3');
    });

    it('expands and collapses items when the server says so, without telling it back', async () => {
      const server = serverWith(FOLDERS);
      const tree = await treeWith(server);

      tree.setItemExpanded('0', true);
      await settle(tree);
      expect(labels(tree)).to.deep.equal(['Documents', 'Invoices', 'Letters', 'Music', 'Pictures']);

      tree.setItemExpanded('0', false);
      await settle(tree);
      expect(labels(tree)).to.deep.equal(['Documents', 'Music', 'Pictures']);
      expect(server.expansions).to.be.empty;
    });

    it('moves focus to an item collapsed while one of its descendants has focus', async () => {
      const tree = await treeWith(serverWith(FOLDERS));
      await clickToggle(tree, 'Documents');
      await clickToggle(tree, 'Letters');
      item(tree, '2026').focus();

      tree.setItemExpanded('0', false);
      await settle(tree);

      expect(tree.shadowRoot!.activeElement).to.equal(item(tree, 'Documents'));
      expect(item(tree, 'Documents').tabIndex).to.equal(0);
    });

    it('does not expand items while disabled', async () => {
      const server = serverWith(FOLDERS);
      const tree = await treeWith(server);
      tree.disabled = true;
      await tree.updateComplete;

      await clickToggle(tree, 'Documents');

      expect(labels(tree)).to.deep.equal(['Documents', 'Music', 'Pictures']);
      expect(server.expansions).to.be.empty;
    });

    it('has no accessibility violations once expanded', async () => {
      const tree = await treeWith(serverWith(FOLDERS));
      await clickToggle(tree, 'Documents');

      await expect(tree).to.be.accessible();
    });
  });

  describe('selection', () => {
    it('marks every item as selectable, one at a time', async () => {
      const tree = await treeWith(serverWith(FOLDERS));

      expect(treeItems(tree).map((element) => element.getAttribute('aria-selected'))).to.deep.equal([
        'false',
        'false',
        'false',
      ]);
      expect(tree.hasAttribute('aria-multiselectable')).to.be.false;
    });

    it('selects an item when the user clicks it, and tells the server', async () => {
      const server = serverWith(FOLDERS);
      const tree = await treeWith(server);

      await clickLabel(tree, 'Music');

      expect(selectedLabels(tree)).to.deep.equal(['Music']);
      expect(item(tree, 'Music').hasAttribute('selected')).to.be.true;
      expect(server.selections).to.deep.equal(['1']);
    });

    it('keeps the selected item selected when the user clicks it again', async () => {
      const server = serverWith(FOLDERS);
      const tree = await treeWith(server);
      await clickLabel(tree, 'Music');

      await clickLabel(tree, 'Music');

      expect(selectedLabels(tree)).to.deep.equal(['Music']);
      expect(server.selections).to.deep.equal(['1']);
    });

    it('selects parents too, but not by clicking their toggle', async () => {
      const server = serverWith(FOLDERS);
      const tree = await treeWith(server);

      await clickToggle(tree, 'Documents');
      expect(selectedLabels(tree)).to.be.empty;

      await clickLabel(tree, 'Documents');
      expect(selectedLabels(tree)).to.deep.equal(['Documents']);
    });

    it('marks the ancestors of the item the user selects as its selection path', async () => {
      const tree = await treeWith(serverWith(FOLDERS));
      await clickToggle(tree, 'Documents');
      await clickToggle(tree, 'Letters');

      await clickLabel(tree, '2026');

      expect(selectionPathLabels(tree)).to.deep.equal(['Documents', 'Letters']);
      expect(item(tree, 'Documents').getAttribute('aria-selected')).to.equal('false');
      expect(item(tree, 'Documents').querySelector('[part="selection-path-marker"]')).to.exist;
      expect(item(tree, '2026').querySelector('[part="selection-path-marker"]')).to.not.exist;
    });

    it('describes the ancestors of the selected item as containing it, and no other item', async () => {
      const tree = await treeWith(serverWith(FOLDERS));
      await clickToggle(tree, 'Documents');
      await clickToggle(tree, 'Letters');

      await clickLabel(tree, '2026');

      expect(description(tree, 'Documents')).to.equal(DEFAULT_I18N.selectionPath);
      expect(description(tree, 'Letters')).to.equal(DEFAULT_I18N.selectionPath);
      for (const label of ['Invoices', '2025', '2026', 'Music']) {
        expect(item(tree, label).hasAttribute('aria-describedby'), label).to.be.false;
      }

      await clickToggle(tree, 'Documents');
      expect(description(tree, 'Documents'), 'collapsed').to.equal(DEFAULT_I18N.selectionPath);

      await clickLabel(tree, 'Music');
      expect(item(tree, 'Documents').hasAttribute('aria-describedby')).to.be.false;
    });

    it('describes an ancestor of the selected item as containing it before its tooltip', async () => {
      const server = serverWith(FOLDERS);
      server.tooltips = { Documents: 'Your documents' };
      const tree = await treeWith(server);
      await clickToggle(tree, 'Documents');

      await clickLabel(tree, 'Invoices');

      expect(description(tree, 'Documents')).to.equal(`${DEFAULT_I18N.selectionPath} Your documents`);
    });

    it('keeps the selection when the parent of the selected item is collapsed, showing it on the parent', async () => {
      const server = serverWith(FOLDERS);
      const tree = await treeWith(server);
      await clickToggle(tree, 'Documents');
      await clickLabel(tree, 'Letters');

      await clickToggle(tree, 'Documents');
      expect(selectionPathLabels(tree)).to.deep.equal(['Documents']);

      await clickToggle(tree, 'Documents');
      expect(selectedLabels(tree)).to.deep.equal(['Letters']);
      expect(server.selections).to.deep.equal(['0/1']);
    });

    it('shows the selection the server sets, without expanding anything', async () => {
      const tree = await treeWith(serverWith(FOLDERS));

      tree.setSelection(null, '0');
      await tree.updateComplete;
      expect(selectedLabels(tree)).to.be.empty;
      expect(selectionPathLabels(tree)).to.deep.equal(['Documents']);
      expect(labels(tree)).to.deep.equal(['Documents', 'Music', 'Pictures']);

      tree.setSelection('2');
      await tree.updateComplete;
      expect(selectedLabels(tree)).to.deep.equal(['Pictures']);
      expect(selectionPathLabels(tree)).to.be.empty;

      tree.setSelection(null);
      await tree.updateComplete;
      expect(selectedLabels(tree)).to.be.empty;
    });

    it('shows the selection on items as they are fetched', async () => {
      const server = serverWith(FOLDERS);
      server.selectedKey = '0/1/0';
      server.selectionPath = ['0', '0/1'];
      const tree = await treeWith(server);

      expect(selectionPathLabels(tree)).to.deep.equal(['Documents']);
      await clickToggle(tree, 'Documents');
      await clickToggle(tree, 'Letters');

      expect(selectionPathLabels(tree)).to.deep.equal(['Documents', 'Letters']);
      expect(selectedLabels(tree)).to.deep.equal(['2025']);
    });

    it('does not select while disabled or read-only', async () => {
      const server = serverWith(FOLDERS);
      const tree = await treeWith(server);

      tree.disabled = true;
      await tree.updateComplete;
      await clickLabel(tree, 'Music');
      tree.disabled = false;
      tree.readonly = true;
      await tree.updateComplete;
      await clickLabel(tree, 'Music');

      expect(selectedLabels(tree)).to.be.empty;
      expect(server.selections).to.be.empty;
    });

    it('has no accessibility violations with a selection', async () => {
      const tree = await treeWith(serverWith(FOLDERS));
      await clickToggle(tree, 'Documents');
      await clickLabel(tree, 'Letters');

      await expect(tree).to.be.accessible();
    });
  });

  describe('scrolling to an item', () => {
    // The server expands the ancestors before it asks to scroll
    const MANY: FakeItem[] = [
      'Gift cards',
      ['Products', Array.from({ length: 25 }, (_, i) => `Product ${i}`), true],
      'Services',
    ];

    async function smallTree(server: TreeServer): Promise<TfvTree> {
      return treeWith(server, (element) => {
        element.pageSize = 10;
        element.style.height = '100px';
        element.style.overflow = 'auto';
      });
    }

    it('fetches pages of each level until the item is there, then scrolls it into view', async () => {
      const server = serverWith(MANY);
      const tree = await smallTree(server);

      await tree.scrollToItem('1', '1/22');
      await settle(tree);

      expect(item(tree, 'Products').getAttribute('aria-expanded')).to.equal('true');
      expect(server.calls.filter((call) => call[0] === '1')).to.deep.equal([
        ['1', 0, 10],
        ['1', 10, 10],
        ['1', 20, 10],
      ]);
      expect(server.expansions).to.be.empty;
      const treeBox = tree.getBoundingClientRect();
      const itemBox = item(tree, 'Product 22').getBoundingClientRect();
      expect(itemBox.top).to.be.at.least(treeBox.top);
      expect(itemBox.bottom).to.be.at.most(treeBox.bottom);
      expect(selectedLabels(tree)).to.be.empty;
    });

    it('waits for the root items to be fetched', async () => {
      const server = serverWith(MANY);
      server.hold = true;
      const tree = await smallTree(server);

      const scrolled = tree.scrollToItem('1', '1/5');
      server.hold = false;
      server.release();
      await scrolled;
      await settle(tree);

      expect(labels(tree)).to.include('Product 5');
    });

    it('gives up on an item that is not there', async () => {
      const tree = await smallTree(serverWith(MANY));

      await tree.scrollToItem('1', '1/99');

      expect(labels(tree)).to.include('Product 24');
    });

    it('gives up when the tree is refreshed meanwhile, as the keys are stale', async () => {
      const server = serverWith(MANY);
      const tree = await smallTree(server);
      const fetches = server.calls.length;

      const scrolled = tree.scrollToItem('1', '1/22');
      tree.refresh();
      await scrolled;
      await settle(tree);

      expect(server.calls.slice(fetches).filter((call) => call[0] === '1' && call[1] !== 0)).to.be.empty;
    });

    it('gives up when the user collapses an ancestor meanwhile, leaving it collapsed', async () => {
      const server = serverWith(MANY);
      server.hold = true;
      const tree = await smallTree(server);
      server.release();
      await settle(tree);

      const scrolled = tree.scrollToItem('1', '1/22');
      await clickToggle(tree, 'Products');
      server.hold = false;
      server.release();
      await scrolled;
      await settle(tree);

      expect(item(tree, 'Products').getAttribute('aria-expanded')).to.equal('false');
      expect(server.expansions).to.deep.equal([['1', false]]);
    });
  });

  describe('paging', () => {
    const MANY: FakeItem[] = [['Products', Array.from({ length: 25 }, (_, i) => `Product ${i}`)]];

    it('gives every item its position in the whole level, before all pages are fetched', async () => {
      const tree = await treeWith(serverWith(MANY), (element) => {
        element.pageSize = 10;
        element.style.height = '100px';
        element.style.overflow = 'auto';
      });

      await clickToggle(tree, 'Products');

      expect(labels(tree)).to.have.length(11);
      expect(item(tree, 'Product 0').getAttribute('aria-setsize')).to.equal('25');
      expect(item(tree, 'Product 9').getAttribute('aria-posinset')).to.equal('10');
    });

    it('fetches the next page when the end of a level scrolls into view, until the level is complete', async () => {
      const server = serverWith(MANY);
      const tree = await treeWith(server, (element) => {
        element.pageSize = 10;
        element.style.height = '100px';
        element.style.overflow = 'auto';
      });
      await clickToggle(tree, 'Products');
      expect(server.calls.filter((call) => call[0] === '0')).to.deep.equal([['0', 0, 10]]);
      expect(loadingRows(tree)).to.have.length(1);

      for (let i = 0; i < 3; i++) {
        tree.scrollTop = tree.scrollHeight;
        await settle(tree);
      }
      await waitUntil(() => labels(tree).length === 26, 'all 25 products shown');

      expect(server.calls.filter((call) => call[0] === '0')).to.deep.equal([
        ['0', 0, 10],
        ['0', 10, 10],
        ['0', 20, 10],
      ]);
      expect(loadingRows(tree)).to.be.empty;
      expect(item(tree, 'Product 24').getAttribute('aria-posinset')).to.equal('25');
    });
  });

  describe('keyboard', () => {
    it('puts the selected item in the tab order, until focus moves within the tree', async () => {
      const server = serverWith(FOLDERS);
      server.selectedKey = '1';
      const tree = await treeWith(server);

      expect(treeItems(tree).map((element) => element.tabIndex)).to.deep.equal([-1, 0, -1]);
      tree.focus();
      await press(tree, 'ArrowDown');
      expect(treeItems(tree).map((element) => element.tabIndex)).to.deep.equal([-1, -1, 0]);
    });

    it('puts the selected item back in the tab order when focus leaves the tree', async () => {
      const server = serverWith(FOLDERS);
      server.selectedKey = '1';
      const tree = await treeWith(server);
      const outside = document.createElement('button');
      tree.after(outside);
      tree.focus();
      await press(tree, 'ArrowDown');

      outside.focus();
      await tree.updateComplete;

      expect(item(tree, 'Music').tabIndex).to.equal(0);
    });

    it('puts the deepest visible ancestor of the selected item in the tab order when it is hidden', async () => {
      const server = serverWith(['Music', ['Documents', ['Invoices', ['Letters', ['2025', '2026']]], true]]);
      server.selectedKey = '1/1/1';
      server.selectionPath = ['1', '1/1'];
      const tree = await treeWith(server);
      await settle(tree);

      expect(labels(tree)).to.deep.equal(['Music', 'Documents', 'Invoices', 'Letters']);
      expect(item(tree, 'Letters').tabIndex).to.equal(0);
      tree.focus();
      expect(focusedLabel(tree)).to.equal('Letters');
    });

    it('puts the first item in the tab order when nothing on the selection path is visible', async () => {
      const server = serverWith(FOLDERS);
      server.selectedKey = '0/1';
      const tree = await treeWith(server);

      expect(treeItems(tree).map((element) => element.tabIndex)).to.deep.equal([0, -1, -1]);
    });

    it('moves focus up and down, and to the first and last visible items, without selecting', async () => {
      const server = serverWith(FOLDERS);
      const tree = await treeWith(server);
      tree.focus();

      await press(tree, 'ArrowUp');
      expect(focusedLabel(tree)).to.equal('Documents');
      await press(tree, 'ArrowDown');
      expect(focusedLabel(tree)).to.equal('Music');
      await press(tree, 'End');
      expect(focusedLabel(tree)).to.equal('Pictures');
      await press(tree, 'ArrowDown');
      expect(focusedLabel(tree)).to.equal('Pictures');
      await press(tree, 'Home');
      expect(focusedLabel(tree)).to.equal('Documents');
      expect(server.selections).to.be.empty;
    });

    it('expands with Right, then moves to the first child item', async () => {
      const server = serverWith(FOLDERS);
      const tree = await treeWith(server);
      tree.focus();

      await press(tree, 'ArrowRight');
      expect(item(tree, 'Documents').getAttribute('aria-expanded')).to.equal('true');
      expect(focusedLabel(tree)).to.equal('Documents');
      expect(server.expansions).to.deep.equal([['0', true]]);

      await press(tree, 'ArrowRight');
      expect(focusedLabel(tree)).to.equal('Invoices');

      await press(tree, 'ArrowRight');
      expect(focusedLabel(tree)).to.equal('Invoices');
    });

    it('moves to the parent with Left, then collapses it', async () => {
      const server = serverWith([['Documents', ['Invoices'], true], 'Music']);
      const tree = await treeWith(server);
      item(tree, 'Invoices').focus();

      await press(tree, 'ArrowLeft');
      expect(focusedLabel(tree)).to.equal('Documents');

      await press(tree, 'ArrowLeft');
      expect(labels(tree)).to.deep.equal(['Documents', 'Music']);
      expect(server.expansions).to.deep.equal([['0', false]]);

      await press(tree, 'ArrowLeft');
      expect(focusedLabel(tree)).to.equal('Documents');
    });

    it('swaps Left and Right when right to left', async () => {
      const tree = await treeWith(serverWith(FOLDERS), (element) => (element.dir = 'rtl'));
      tree.focus();

      await press(tree, 'ArrowLeft');
      expect(item(tree, 'Documents').getAttribute('aria-expanded')).to.equal('true');
      await press(tree, 'ArrowLeft');
      expect(focusedLabel(tree)).to.equal('Invoices');
      await press(tree, 'ArrowRight');
      expect(focusedLabel(tree)).to.equal('Documents');
      await press(tree, 'ArrowRight');
      expect(item(tree, 'Documents').getAttribute('aria-expanded')).to.equal('false');
    });

    it('selects the focused item with Enter or Space', async () => {
      const server = serverWith(FOLDERS);
      const tree = await treeWith(server);
      tree.focus();

      await press(tree, 'Enter');
      expect(selectedLabels(tree)).to.deep.equal(['Documents']);
      await press(tree, 'ArrowDown');
      await press(tree, ' ');
      expect(selectedLabels(tree)).to.deep.equal(['Music']);
      expect(server.selections).to.deep.equal(['0', '1']);
    });

    it('does not select with the keyboard while read-only', async () => {
      const server = serverWith(FOLDERS);
      const tree = await treeWith(server, (element) => (element.readonly = true));
      tree.focus();

      await press(tree, 'Enter');

      expect(selectedLabels(tree)).to.be.empty;
    });

    it('expands the siblings of the focused item with *, in one call to the server', async () => {
      const server = serverWith([['Documents', ['Invoices']], 'Music', ['Pictures', ['Holidays']]]);
      const tree = await treeWith(server);
      tree.focus();

      await press(tree, '*');

      expect(labels(tree)).to.deep.equal(['Documents', 'Invoices', 'Music', 'Pictures', 'Holidays']);
      expect(server.expansions).to.deep.equal([
        ['0', true],
        ['2', true],
      ]);
      expect(focusedLabel(tree)).to.equal('Documents');
    });

    it('moves to the next visible item starting with the typed character, cycling through them', async () => {
      const tree = await treeWith(serverWith(['Documents', 'Music', 'Movies', 'Pictures']));
      tree.focus();

      await press(tree, 'm');
      expect(focusedLabel(tree)).to.equal('Music');
      await press(tree, 'm');
      expect(focusedLabel(tree)).to.equal('Movies');
      await press(tree, 'M');
      expect(focusedLabel(tree)).to.equal('Music');
    });

    it('moves to the item starting with the characters typed in quick succession, spaces included', async () => {
      const tree = await treeWith(serverWith(['Gift cards', 'Games', 'Gift boxes']));
      tree.focus();

      for (const key of 'gift b') {
        await press(tree, key);
      }

      expect(focusedLabel(tree)).to.equal('Gift boxes');
      expect(selectedLabels(tree)).to.be.empty;
    });

    it('starts a new search after a pause in typing', async () => {
      const tree = await treeWith(serverWith(['Documents', 'Music', 'Pictures']));
      tree.focus();

      await press(tree, 'm');
      await new Promise((resolve) => setTimeout(resolve, TYPE_AHEAD_TIMEOUT + 50));
      await press(tree, 'p');

      expect(focusedLabel(tree)).to.equal('Pictures');
    });

    it('searches only the visible items', async () => {
      const tree = await treeWith(serverWith(FOLDERS));
      tree.focus();

      await press(tree, 'l');

      expect(focusedLabel(tree)).to.equal('Documents');
    });

    it('ignores keys while disabled', async () => {
      const server = serverWith(FOLDERS);
      const tree = await treeWith(server);
      tree.focus();
      tree.disabled = true;
      await tree.updateComplete;

      await press(tree, 'ArrowRight');
      await press(tree, 'Enter');

      expect(server.expansions).to.be.empty;
      expect(server.selections).to.be.empty;
    });
  });

  describe('icons and tooltips', () => {
    function folderServer(): FakeServer {
      const server = serverWith(['Documents', 'Music', 'Pictures']);
      server.icons = { Documents: 'vaadin:folder', Music: 'vaadin:music' };
      server.tooltips = { Documents: 'Your documents', Pictures: 'Your pictures' };
      return server;
    }

    function tooltip(tree: TfvTree): Tooltip {
      return tree.shadowRoot!.querySelector('vaadin-tooltip')!;
    }

    function hover(element: Element): void {
      element.dispatchEvent(new MouseEvent('mouseover', { bubbles: true, composed: true }));
    }

    before(() => {
      Tooltip.setDefaultHoverDelay(0);
      Tooltip.setDefaultFocusDelay(0);
      Tooltip.setDefaultHideDelay(0);
    });

    after(() => {
      Tooltip.setDefaultHoverDelay(-1);
      Tooltip.setDefaultFocusDelay(-1);
      Tooltip.setDefaultHideDelay(-1);
    });

    it('shows the icon of an item before its label, hidden from assistive technology', async () => {
      const tree = await treeWith(folderServer());

      const icon = item(tree, 'Documents').querySelector('[part="icon"]')!;
      expect(icon.localName).to.equal('vaadin-icon');
      expect(icon.getAttribute('icon')).to.equal('vaadin:folder');
      expect(icon.getAttribute('aria-hidden')).to.equal('true');
      expect(icon.nextElementSibling!.getAttribute('part')).to.equal('label');
      expect(labels(tree)).to.deep.equal(['Documents', 'Music', 'Pictures'], 'the label stays the only text');
    });

    it('shows no icon for an item without one', async () => {
      const tree = await treeWith(folderServer());

      expect(item(tree, 'Pictures').querySelector('[part="icon"]')).to.equal(null);
    });

    it('describes an item by its tooltip, and an item without one by nothing', async () => {
      const tree = await treeWith(folderServer());

      expect(description(tree, 'Documents')).to.equal('Your documents');
      expect(item(tree, 'Music').hasAttribute('aria-describedby')).to.equal(false);
      expect(tooltip(tree).getAttribute('aria-hidden')).to.equal('true', 'a tree only holds treeitems');
    });

    it('opens the tooltip of the item hovered, and closes it over an item without one', async () => {
      const tree = await treeWith(folderServer());

      hover(item(tree, 'Documents').querySelector('[part="label"]')!);
      await tree.updateComplete;

      expect(tooltip(tree).opened).to.equal(true);
      expect(tooltip(tree).target).to.equal(item(tree, 'Documents'));
      expect(tooltip(tree).text).to.equal('Your documents');

      hover(item(tree, 'Pictures'));
      expect(tooltip(tree).target).to.equal(item(tree, 'Pictures'));
      expect(tooltip(tree).text).to.equal('Your pictures');

      hover(item(tree, 'Music'));
      expect(tooltip(tree).opened).to.equal(false);
    });

    it('closes the tooltip when the item is pressed, until another item is hovered', async () => {
      const tree = await treeWith(folderServer());
      hover(item(tree, 'Documents'));

      const label = item(tree, 'Documents').querySelector('[part="label"]')!;
      label.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, composed: true }));
      hover(label);

      expect(tooltip(tree).opened).to.equal(false);
    });

    it('opens the tooltip of the item focused with the keyboard, and closes it with Escape', async () => {
      const tree = await treeWith(folderServer());

      await sendKeys({ press: 'Tab' });
      await tree.updateComplete;
      expect(focusedLabel(tree)).to.equal('Documents');
      expect(tooltip(tree).opened).to.equal(true);
      expect(tooltip(tree).text).to.equal('Your documents');

      await sendKeys({ press: 'ArrowDown' });
      expect(focusedLabel(tree)).to.equal('Music');
      expect(tooltip(tree).opened).to.equal(false);

      await sendKeys({ press: 'ArrowDown' });
      expect(tooltip(tree).opened).to.equal(true);
      expect(tooltip(tree).target).to.equal(item(tree, 'Pictures'));

      let escaped = false;
      document.addEventListener('keydown', () => (escaped = true), { once: true });
      await sendKeys({ press: 'Escape' });
      expect(tooltip(tree).opened).to.equal(false);
      expect(escaped).to.equal(false, 'Escape closing the tooltip goes no further');
    });

    it('does not open the tooltip of an item focused by clicking it', async () => {
      const tree = await treeWith(folderServer());
      const { x, y, height } = item(tree, 'Documents').querySelector('[part="label"]')!.getBoundingClientRect();

      await sendMouse({ type: 'click', position: [Math.round(x + 5), Math.round(y + height / 2)] });
      await settle(tree);

      expect(focusedLabel(tree)).to.equal('Documents');
      expect(tooltip(tree).opened).to.equal(false);
      await resetMouse();
    });

    it('closes the tooltip when its item is hidden', async () => {
      const server = serverWith(FOLDERS);
      server.tooltips = { Invoices: 'Paid and unpaid' };
      const tree = await treeWith(server);
      await clickToggle(tree, 'Documents');
      hover(item(tree, 'Invoices'));
      expect(tooltip(tree).opened).to.equal(true);

      tree.setItemExpanded('0', false);
      await settle(tree);

      expect(tooltip(tree).opened).to.equal(false);
    });
  });

  describe('data changes and errors', () => {
    it('shows the empty text when there are no items, and is described by it', async () => {
      const tree = await treeWith(serverWith([]));

      const [empty] = messageRows(tree, 'empty');
      expect(empty.textContent!.trim()).to.equal('No items');
      expect(empty.getAttribute('aria-hidden')).to.equal('true');
      expect(tree.getAttribute('aria-description')).to.equal('No items');
      expect(loadingRows(tree)).to.be.empty;
      await expect(tree).to.be.accessible();
    });

    it('stops being described as empty once it has items', async () => {
      const roots: FakeItem[] = [];
      const tree = await treeWith(serverWith(roots));

      roots.push('Documents');
      await tree.refresh();
      await settle(tree);

      expect(messageRows(tree, 'empty')).to.be.empty;
      expect(tree.hasAttribute('aria-description')).to.be.false;
    });

    it('keeps the description the app gives it while empty', async () => {
      const tree = await treeWith(serverWith([]), (element) => element.setAttribute('aria-description', 'Your folders'));

      expect(messageRows(tree, 'empty')).to.have.length(1);
      expect(tree.getAttribute('aria-description')).to.equal('Your folders');
    });

    it('collapses an item back when its child items fail to load, and briefly shows and announces the error', async function () {
      this.timeout(LOAD_ERROR_DURATION + 3000);
      const server = serverWith(FOLDERS);
      server.failing = ['0'];
      const tree = await treeWith(server);

      await clickToggle(tree, 'Documents');

      expect(item(tree, 'Documents').getAttribute('aria-expanded')).to.equal('false');
      expect(server.expansions).to.deep.equal([
        ['0', true],
        ['0', false],
      ]);
      const [error] = messageRows(tree, 'error');
      expect(error.textContent!.trim()).to.equal('Could not load the items');
      expect(error.getAttribute('aria-level')).to.be.null;
      expect(error.getAttribute('aria-hidden')).to.equal('true');
      expect(error.previousElementSibling).to.equal(item(tree, 'Documents'));
      await announced('Could not load the items');
      await expect(tree).to.be.accessible();

      await waitUntil(() => messageRows(tree, 'error').length === 0, 'error hidden', {
        timeout: LOAD_ERROR_DURATION + 1000,
      });
    });

    it('fetches the child items again when expanded again after failing', async () => {
      const server = serverWith(FOLDERS);
      server.failing = ['0'];
      const tree = await treeWith(server);
      await clickToggle(tree, 'Documents');

      server.failing = [];
      await clickToggle(tree, 'Documents');

      expect(messageRows(tree, 'error')).to.be.empty;
      expect(labels(tree)).to.deep.equal(['Documents', 'Invoices', 'Letters', 'Music', 'Pictures']);
    });

    it('shows the error in place of the root items when they fail to load, until refreshed', async () => {
      const server = serverWith(FOLDERS);
      server.failing = [null];
      const tree = await treeWith(server);

      expect(messageRows(tree, 'error')).to.have.length(1);
      expect(loadingRows(tree)).to.be.empty;
      expect(messageRows(tree, 'empty')).to.be.empty;
      expect(tree.hasAttribute('aria-busy')).to.be.false;
      await announced('Could not load the items');

      server.failing = [];
      await tree.refresh();
      await settle(tree);

      expect(messageRows(tree, 'error')).to.be.empty;
      expect(labels(tree)).to.deep.equal(['Documents', 'Music', 'Pictures']);
    });

    it('updates an item the server refreshes, leaving its child items as they are', async () => {
      const server = serverWith(FOLDERS);
      const tree = await treeWith(server);
      await clickToggle(tree, 'Documents');
      server.calls = [];

      tree.refreshItem({ ...(await server.fetchChildren(null, 0, 1)).items[0], label: 'Docs', expanded: true }, false);
      await settle(tree);

      expect(labels(tree)).to.deep.equal(['Docs', 'Invoices', 'Letters', 'Music', 'Pictures']);
      expect(server.calls).to.deep.equal([[null, 0, 1]]);
    });

    it('fetches the child items of an item again when the server refreshes them, keeping focus on the item', async () => {
      const documents: FakeItem = ['Documents', ['Invoices', 'Letters'], true];
      const server = serverWith([documents, 'Music']);
      const tree = await treeWith(server);
      tree.focus();
      await press(tree, 'ArrowDown');
      expect(focusedLabel(tree)).to.equal('Invoices');

      documents[1] = ['Receipts'];
      tree.refreshItem((await server.fetchChildren(null, 0, 1)).items[0], true);
      await settle(tree);

      expect(labels(tree)).to.deep.equal(['Documents', 'Receipts', 'Music']);
      expect(focusedLabel(tree)).to.equal('Documents');
    });

    it('collapses an item the server refreshes once it has no child items', async () => {
      const documents: FakeItem = ['Documents', ['Invoices'], true];
      const server = serverWith([documents]);
      const tree = await treeWith(server);

      documents[1] = [];
      tree.refreshItem((await server.fetchChildren(null, 0, 1)).items[0], false);
      await settle(tree);

      expect(labels(tree)).to.deep.equal(['Documents']);
      expect(item(tree, 'Documents').hasAttribute('aria-expanded')).to.be.false;
    });

    it('ignores the server refreshing an item it has not fetched', async () => {
      const tree = await treeWith(serverWith(FOLDERS));

      const letters = { key: '0/1', label: 'Mail', icon: null, tooltip: null, expandable: true, expanded: true };
      tree.refreshItem({ ...letters, selected: false, onSelectionPath: false }, true);
      await settle(tree);

      expect(labels(tree)).to.deep.equal(['Documents', 'Music', 'Pictures']);
    });

    it('shows the texts the server sets', async () => {
      const server = serverWith([['Documents', ['Invoices']]]);
      server.failing = ['0'];
      const tree = await treeWith(server, (element) => {
        element.i18n = {
          loading: 'Ladataan…',
          empty: 'Ei kohteita',
          loadError: 'Lataus epäonnistui',
          selectionPath: 'Sisältää valitun kohteen',
        };
      });
      tree.setSelection(null, '0');
      await tree.updateComplete;
      expect(description(tree, 'Documents')).to.equal('Sisältää valitun kohteen');

      server.hold = true;
      const refreshed = tree.refresh();
      await tree.updateComplete;
      expect(loadingRows(tree)[0].textContent!.trim()).to.equal('Ladataan…');
      server.hold = false;
      server.release();
      await refreshed;
      await settle(tree);

      await clickToggle(tree, 'Documents');
      expect(messageRows(tree, 'error')[0].textContent!.trim()).to.equal('Lataus epäonnistui');
      await announced('Lataus epäonnistui');

      const empty = await treeWith(serverWith([]), (element) => (element.i18n = tree.i18n));
      expect(messageRows(empty, 'empty')[0].textContent!.trim()).to.equal('Ei kohteita');
      expect(empty.getAttribute('aria-description')).to.equal('Ei kohteita');
    });

    it('shows English texts unless the server sets others', async () => {
      const tree = await treeWith(serverWith([]));

      expect(tree.i18n).to.deep.equal(DEFAULT_I18N);
    });
  });

  describe('styling', () => {
    function stateOf(tree: TfvTree, label: string): string[] {
      return ['focused', 'focus-ring', 'selected', 'selection-path', 'expanded'].filter((name) =>
        item(tree, label).hasAttribute(name),
      );
    }

    it('marks the focused item, with a focus ring only when focused with the keyboard', async () => {
      const tree = await treeWith(serverWith(FOLDERS));
      const outside = document.createElement('button');
      tree.after(outside);

      await sendKeys({ press: 'Tab' });
      await tree.updateComplete;
      expect(stateOf(tree, 'Documents')).to.deep.equal(['focused', 'focus-ring']);

      await sendKeys({ press: 'ArrowDown' });
      await tree.updateComplete;
      expect(stateOf(tree, 'Documents')).to.deep.equal([]);
      expect(stateOf(tree, 'Music')).to.deep.equal(['focused', 'focus-ring']);

      const { x, y, height } = item(tree, 'Pictures').querySelector('[part="label"]')!.getBoundingClientRect();
      await sendMouse({ type: 'click', position: [Math.round(x + 5), Math.round(y + height / 2)] });
      await settle(tree);
      expect(stateOf(tree, 'Pictures')).to.deep.equal(['focused', 'selected']);
      await resetMouse();

      outside.focus();
      await tree.updateComplete;
      expect(stateOf(tree, 'Pictures')).to.deep.equal(['selected']);
    });

    it('marks the expanded items and the selection path', async () => {
      const server = serverWith([['Documents', ['Invoices', 'Letters'], true], 'Music']);
      server.selectedKey = '0/1';
      server.selectionPath = ['0'];
      const tree = await treeWith(server);
      await settle(tree);

      expect(stateOf(tree, 'Documents')).to.deep.equal(['selection-path', 'expanded']);
      expect(stateOf(tree, 'Letters')).to.deep.equal(['selected']);
    });

    it('names the states of an item in its part, for styling with ::part() from outside', async () => {
      const server = serverWith([['Documents', ['Invoices', 'Letters'], true], 'Music']);
      server.selectedKey = '0/1';
      server.selectionPath = ['0'];
      const tree = await treeWith(server);
      await settle(tree);

      expect(item(tree, 'Documents').getAttribute('part')).to.equal('item expanded selection-path');
      expect(item(tree, 'Letters').getAttribute('part')).to.equal('item selected');
      expect(item(tree, 'Music').getAttribute('part')).to.equal('item');

      const style = document.createElement('style');
      style.textContent = 'tfv-tree::part(item selected) { text-decoration: underline; }';
      document.head.append(style);
      try {
        expect(getComputedStyle(item(tree, 'Letters')).textDecorationLine).to.equal('underline');
        expect(getComputedStyle(item(tree, 'Music')).textDecorationLine).to.equal('none');
      } finally {
        style.remove();
      }
    });

    it('lays the parts out from its --tfv-tree-* properties', async () => {
      const server = serverWith([['Documents', ['Invoices', 'Letters'], true], 'Music']);
      server.selectedKey = '1';
      const tree = await treeWith(server, (element) => {
        element.style.setProperty('--tfv-tree-indent', '30px');
        element.style.setProperty('--tfv-tree-item-padding-inline', '4px');
        element.style.setProperty('--tfv-tree-selected-background', 'rgb(1, 2, 3)');
        element.style.setProperty('--tfv-tree-selected-text-color', 'rgb(250, 251, 252)');
      });
      await settle(tree);

      expect(getComputedStyle(item(tree, 'Documents')).paddingInlineStart).to.equal('4px');
      expect(getComputedStyle(item(tree, 'Letters')).paddingInlineStart).to.equal('34px');
      expect(getComputedStyle(item(tree, 'Music')).backgroundColor).to.equal('rgb(1, 2, 3)');
      expect(getComputedStyle(item(tree, 'Music')).color).to.equal('rgb(250, 251, 252)');
    });

    it('takes the Aura token before the Lumo one, and the Lumo one when there is no Aura', async () => {
      const server = serverWith(['Documents']);
      server.selectedKey = '0';
      const tree = await treeWith(server);
      const theme = tree.parentElement!;

      theme.style.setProperty('--lumo-primary-text-color', 'rgb(0, 0, 200)');
      expect(getComputedStyle(item(tree, 'Documents')).backgroundColor).to.equal('rgb(0, 0, 200)');

      theme.style.setProperty('--aura-accent-text-color', 'rgb(200, 0, 0)');
      expect(getComputedStyle(item(tree, 'Documents')).backgroundColor).to.equal('rgb(200, 0, 0)');
    });
  });
});
