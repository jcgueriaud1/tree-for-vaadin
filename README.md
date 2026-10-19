# Tree for Vaadin

An accessible tree component for Vaadin Flow whose items are loaded lazily from the server.

- Java 21
- Vaadin 25.3. The add-on depends on Vaadin only, not on Spring.

The words in bold below are defined in [GLOSSARY.md](GLOSSARY.md); the design decisions are in [docs/adr](docs/adr).

## Status

The tree shows its **root items**, and items can be **expanded** and **collapsed**, by the user (with the
toggle in front of an item, or the keyboard) or by the server. The user selects an item by clicking it, or
with Enter or Space. Checkbox selection is still to come.

## Usage

```java
TreeData<Folder> data = new TreeData<>();
data.addRootItems(documents, music, pictures);

Tree<Folder> tree = new Tree<>();
tree.setItems(data);                    // or setDataProvider(anyHierarchicalDataProvider)
tree.setItemLabelGenerator(Folder::name);
tree.setAriaLabel("Folders");           // the accessible name of the tree

tree.expand(documents);                 // or collapse(...), isExpanded(...)
tree.addExpandListener(event -> log("Expanded " + event.getItems() + (event.isFromClient() ? " by the user" : "")));
tree.addCollapseListener(event -> log("Collapsed " + event.getItems()));

tree.asSingleSelect().setValue(letters); // the selected item, or null for none
tree.asSingleSelect().addValueChangeListener(event -> open(event.getValue()));
tree.scrollToItem(letters);             // expands its ancestors and scrolls it into view
```

The tree is rendered as an ARIA `tree` whose **items** are `treeitem`s. The browser fetches them from the
server a page at a time through the add-on's own protocol ([ADR-0001](docs/adr/0001-own-lazy-loading-protocol.md)):
the root items first, the **child items** of an item when it is first expanded, and the next page of a level
when the user scrolls near its end. `setPageSize(int)` sets the page size (default 50). Every item fetched stays in
the page, with its level and position in its level in `aria-level`, `aria-posinset` and `aria-setsize`; while a
level is being fetched, its parent (or the tree, for the root items) is `aria-busy`.

Expanded items stay expanded when the data provider is refreshed. Expanding an item does not expand its
ancestors: its child items show once they are all expanded too.

There is one **selected item** at most (`setSelectionMode(SelectionMode.SINGLE)`, the only mode for now), and
every item can be selected, parents too. The `Tree` is not itself a `HasValue`: `asSingleSelect()` is, so it can
be bound with a `Binder`. Clicking an item selects it (clicking its toggle only expands or collapses it);
clicking the selected item again keeps it selected, and only the server clears the selection, with
`setValue(null)`. Items are `aria-selected`. Expanding and collapsing never change the selection, and setting
the value never expands anything. The ancestors of the selected item, its **selection path**, have the
`selection-path` attribute and a dot marker (part `selection-path-marker`), collapsed or not; this needs the
data provider to implement `getParent`, as `TreeDataProvider` does, and is left out otherwise.
`scrollToItem(item)` needs `getParent` too: it expands the item's ancestors and fetches the pages on the way
down, then scrolls the item into view.

### Keyboard

The keys follow the [ARIA tree pattern](https://www.w3.org/WAI/ARIA/apg/patterns/treeview/), and selection
does not follow focus: moving the **focused item** never changes the selected item.

| Key | Does |
| --- | --- |
| Tab | Enters the tree, on the selected item if visible, else the first item: the tree is one tab stop |
| Down / Up | Moves focus to the next / previous visible item |
| Home / End | Moves focus to the first / last visible item |
| Right | Expands a collapsed item; on an expanded item, moves focus to its first child item |
| Left | Collapses an expanded item; otherwise moves focus to its parent |
| Enter, Space | Selects the focused item |
| `*` | Expands the focused item and its siblings, in one `ExpandEvent` |
| Any character | Moves focus to the next visible item whose label starts with it; characters typed within half a second, spaces included, are searched together |

Left and Right are swapped when the tree is right to left (`dir="rtl"`). Type-ahead only searches the items
fetched and visible. When an item is collapsed while one of its descendants has focus, focus moves to it. The
focused item has a focus ring; the selected item a background.

## Development

- Run the demo: `./mvnw` at the root (builds the add-on, then runs the demo), then open http://localhost:8080
- Test: `./mvnw verify`. It runs
  - the add-on's browserless tests (`addon/src/test/java`),
  - the `<tfv-tree>` Lit tests with `@web/test-runner` (`addon/src/test/frontend`),
  - the Playwright and axe-core tests against the packaged demo (`demo/src/test/java`, `*IT`).

  `-DskipITs` skips the demo tests; `-DskipTests` skips all of them.
- Lit tests alone: `cd addon && npm ci && npm test`

The Lit element (`addon/src/main/resources/META-INF/resources/frontend/tfv-tree/`) ships inside the jar and is
loaded with `@JsModule`. `addon/package.json` only holds test tooling: at run time Lit comes from the Vaadin app.

## License

[Apache License 2.0](LICENSE)
