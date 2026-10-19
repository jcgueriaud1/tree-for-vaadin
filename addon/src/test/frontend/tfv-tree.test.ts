import { expect, fixture, html, nextFrame, waitUntil } from '@open-wc/testing';
import {
  type ItemPage,
  PAGE_SIZE,
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
      const level = children(parentKey);
      const items = level.slice(offset, offset + limit).map((item, i) => {
        const key = parentKey === null ? String(offset + i) : `${parentKey}/${offset + i}`;
        return typeof item === 'string'
          ? { key, label: item, expandable: false, expanded: false }
          : { key, label: item[0], expandable: item[1].length > 0, expanded: item[2] ?? false };
      });
      return { items, size: level.length };
    },
    setExpanded(key, expanded) {
      server.expansions.push([key, expanded]);
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

async function clickToggle(tree: TfvTree, label: string): Promise<void> {
  item(tree, label).querySelector<HTMLElement>('[part="toggle"]')!.click();
  await settle(tree);
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
});
