import { expect, fixture, html, nextFrame } from '@open-wc/testing';
import { type ItemPage, PAGE_SIZE, type TfvTree, type TreeServer } from '../../main/resources/META-INF/resources/frontend/tfv-tree/tfv-tree.ts';

/** A server holding the given root items, recording the fetches it gets. */
function serverWith(labels: string[]): TreeServer & { calls: unknown[][] } {
  const calls: unknown[][] = [];
  return {
    calls,
    async fetchChildren(parentKey, offset, limit): Promise<ItemPage> {
      calls.push([parentKey, offset, limit]);
      const items = labels.slice(offset, offset + limit).map((label, i) => ({ key: String(offset + i), label }));
      return { items, size: labels.length };
    },
  };
}

async function treeWith(server: TreeServer): Promise<TfvTree> {
  const tree = document.createElement('tfv-tree');
  tree.$server = server;
  tree.setAttribute('aria-label', 'Folders');
  await fixture(html`<div>${tree}</div>`);
  await waitForItems(tree);
  return tree;
}

async function waitForItems(tree: TfvTree): Promise<void> {
  await nextFrame();
  await tree.updateComplete;
}

function treeItems(tree: TfvTree): HTMLElement[] {
  return Array.from(tree.shadowRoot!.querySelectorAll<HTMLElement>('[role="treeitem"]'));
}

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

  it('shows each root item as a treeitem at level 1', async () => {
    const tree = await treeWith(serverWith(['Documents', 'Music']));

    const items = treeItems(tree);
    expect(items.map((item) => item.textContent!.trim())).to.deep.equal(['Documents', 'Music']);
    expect(items.map((item) => item.getAttribute('aria-level'))).to.deep.equal(['1', '1']);
    expect(items.map((item) => item.getAttribute('aria-posinset'))).to.deep.equal(['1', '2']);
    expect(items.map((item) => item.getAttribute('aria-setsize'))).to.deep.equal(['2', '2']);
  });

  it('puts one item in the tab order and moves focus with the arrow keys', async () => {
    const tree = await treeWith(serverWith(['Documents', 'Music', 'Pictures']));

    expect(treeItems(tree).map((item) => item.tabIndex)).to.deep.equal([0, -1, -1]);

    tree.focus();
    treeItems(tree)[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, composed: true }));
    await tree.updateComplete;

    expect(tree.shadowRoot!.activeElement).to.equal(treeItems(tree)[1]);
    expect(treeItems(tree).map((item) => item.tabIndex)).to.deep.equal([-1, 0, -1]);

    treeItems(tree)[1].dispatchEvent(new KeyboardEvent('keydown', { key: 'End', bubbles: true, composed: true }));
    await tree.updateComplete;
    expect(tree.shadowRoot!.activeElement).to.equal(treeItems(tree)[2]);
  });

  it('shows the root items again when the server refreshes it', async () => {
    const labels = ['Documents'];
    const tree = await treeWith(serverWith(labels));

    labels.push('Music');
    await tree.refresh();
    await tree.updateComplete;

    expect(treeItems(tree).map((item) => item.textContent!.trim())).to.deep.equal(['Documents', 'Music']);
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
});
