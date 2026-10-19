# Tree for Vaadin

An accessible tree component for Vaadin Flow whose items are loaded lazily from the server.

- Java 21
- Vaadin 25.3. The add-on depends on Vaadin only, not on Spring.

The words in bold below are defined in [GLOSSARY.md](GLOSSARY.md); the design decisions are in [docs/adr](docs/adr).

## Status

The tree shows its **root items**, and items can be **expanded** and **collapsed**, by the user (with the
toggle in front of an item, or the keyboard) or by the server. The user selects an item by clicking it, or
with Enter or Space. Items can show an icon and a tooltip. The tree keeps its state when the data is
refreshed, shows when it has no items or when items fail to load, and its texts can be translated. The tree follows the Aura or Lumo theme, light or
dark, and can be restyled (see [Styling](#styling)). Checkbox selection is still to come.

## Usage

```java
TreeData<Folder> data = new TreeData<>();
data.addRootItems(documents, music, pictures);

Tree<Folder> tree = new Tree<>();
tree.setItems(data);                    // or setDataProvider(anyHierarchicalDataProvider)
tree.setItemLabelGenerator(Folder::name);
tree.setItemIconGenerator(folder -> "vaadin:folder");  // an icon name, or null for none
tree.setItemTooltipGenerator(Folder::description);     // the tooltip text, or null for none
tree.setAriaLabel("Folders");           // the accessible name of the tree

tree.expand(documents);                 // or collapse(...), isExpanded(...)
tree.addExpandListener(event -> log("Expanded " + event.getItems() + (event.isFromClient() ? " by the user" : "")));
tree.addCollapseListener(event -> log("Collapsed " + event.getItems()));

tree.asSingleSelect().setValue(letters); // the selected item, or null for none
tree.asSingleSelect().addValueChangeListener(event -> open(event.getValue()));
tree.scrollToItem(letters);             // expands its ancestors and scrolls it into view

data.addItem(documents, receipts);
tree.getDataProvider().refreshItem(documents, true); // or refreshAll()
tree.setI18n(new TreeI18n().setEmpty("No folders")); // the texts the tree shows by itself
```

The tree is rendered as an ARIA `tree` whose **items** are `treeitem`s. The browser fetches them from the
server a page at a time through the add-on's own protocol ([ADR-0001](docs/adr/0001-own-lazy-loading-protocol.md)):
the root items first, the **child items** of an item when it is first expanded, and the next page of a level
when the user scrolls near its end. `setPageSize(int)` sets the page size (default 50). Every item fetched stays in
the page, with its level and position in its level in `aria-level`, `aria-posinset` and `aria-setsize`; while a
level is being fetched, its parent (or the tree, for the root items) is `aria-busy`.

Expanding an item does not expand its ancestors: its child items show once they are all expanded too.

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

### Data changes, errors and texts

`refreshAll()` on the data provider has the browser fetch the items again. Items that still exist, by their id
(`getId`), stay expanded, and the value stays as it is even if its item is gone: clear it if need be.
`refreshItem(item)` sends one item again (its label, icon, tooltip and whether it has child items);
`refreshItem(item, true)` also fetches its child items again, moving focus to the item if one of them had it.

If the data provider throws while fetching child items, Flow hands the exception to the session's
`ErrorHandler`, which logs it by default. In the browser, the item collapses back (with a `CollapseEvent` from
the client), and its group shows "Could not load the items" for three seconds (part `error`); expanding it
again fetches the child items again. If the root items fail to load, the error stays until the next refresh.
The error is announced to screen readers through Vaadin's live region, as an alert.

A tree without items shows "No items" (part `empty`), which is also its accessible description
(`aria-description`), unless the app gives the tree a description of its own.

The texts the tree shows by itself, "Loading…", "No items" and the load error, are English by default and
set with `setI18n(new TreeI18n()...)`. Set the object again after changing it.

### Icons and tooltips

`setItemIconGenerator` gives each item the name of an icon, such as `vaadin:folder`, shown
with `<vaadin-icon>` before its label (part `icon`, sized by `--tfv-tree-icon-size`); `null` means no icon.
Icons are decorative: they are `aria-hidden`, and the label stays the accessible name of the item. The
`vaadin:` icons come with the add-on; other icon sets must be loaded by the app.

`setItemTooltipGenerator` gives each item an optional tooltip text; `null` or empty means no tooltip. One
`<vaadin-tooltip>` follows the item hovered or focused with the keyboard, with the same delays as other Vaadin
tooltips, and Escape closes it. As a tree only holds treeitems, the tooltip itself is hidden from assistive
technology: its text is the item's accessible description (`aria-describedby`) instead, whether or not the
tooltip shows. Items hold text only, not server-side components.
`setTooltipText`, from `HasTooltip`, is a tooltip for the whole tree instead.

### Keyboard

The keys follow the [ARIA tree pattern](https://www.w3.org/WAI/ARIA/apg/patterns/treeview/), and selection
does not follow focus: moving the **focused item** never changes the selected item.

| Key | Does |
| --- | --- |
| Tab | Enters the tree, on the selected item if visible, else on its deepest visible ancestor (the one showing the selection path), else on the first item: the tree is one tab stop |
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

### Styling

The tree looks at home in both [Aura and Lumo](https://vaadin.com/docs/latest/styling/themes), light or dark,
and in a page with neither. Its styles live in its shadow DOM; style it from outside through its parts, state
attributes and custom properties.

```css
tfv-tree {
  --tfv-tree-indent: 1em;
  --tfv-tree-selected-background: var(--lumo-primary-color-10pct);
  --tfv-tree-selected-text-color: var(--lumo-primary-text-color);
}

tfv-tree::part(item selected) {
  font-weight: 600;
}
```

Set the custom properties on `tfv-tree` itself (or on a class of it), not on an ancestor: the tree gives each
one its theme value, which only a rule on the element overrides. The example above trades the default solid
fill of the selected item for Lumo's tint; mind that a tint is under the 3:1 contrast WCAG asks of a state
indicator.

| Part | Is |
| --- | --- |
| `item` | An item (the `treeitem`) |
| `toggle` | The chevron of an item with child items, which expands and collapses it |
| `toggle-spacer` | Where the chevron would be, on an item without child items |
| `icon` | The `<vaadin-icon>` of an item |
| `label` | The label of an item |
| `selection-path-marker` | The dot after an ancestor of the selected item |
| `loading` | The row shown while the items of a level are fetched |
| `empty` | The row shown when the tree has no items |
| `error` | The row shown when items fail to load |

There is no `children` part: the items are siblings in the DOM, each indented by its level (`aria-level`),
so nothing wraps the child items of an item. Indent them with `--tfv-tree-indent`.

An item's states are part names too, as `::part()` cannot be followed by an attribute selector: style the
selected item with `tfv-tree::part(item selected)`, an expanded one with `tfv-tree::part(item expanded)`. Inside
the shadow DOM, the same states are attributes of the item.

| State (part name and attribute of `item`) | Set when |
| --- | --- |
| `expanded` | the item is expanded |
| `selected` | the item is the selected item |
| `selection-path` | the item is an ancestor of the selected item |
| `focused` | the item has focus |
| `focus-ring` | the item has focus from the keyboard, and shows a focus ring |

| Custom property | Default: Aura, else Lumo, else the Vaadin base styles |
| --- | --- |
| `--tfv-tree-font-size`, `--tfv-tree-line-height` | `--aura-font-size-m` / `--lumo-font-size-m`, and the matching line heights |
| `--tfv-tree-text-color` | `--lumo-body-text-color` / `--vaadin-text-color` |
| `--tfv-tree-secondary-text-color` | `--lumo-secondary-text-color` / `--vaadin-text-color-secondary`: loading and empty rows, and the defaults of the two below |
| `--tfv-tree-error-text-color` | the error row: `--aura-red-text` / `--lumo-error-text-color`, else `--tfv-tree-text-color` |
| `--tfv-tree-icon-color`, `--tfv-tree-toggle-color` | `--tfv-tree-secondary-text-color` |
| `--tfv-tree-icon-size` | `--lumo-icon-size-s` / `--vaadin-icon-size` |
| `--tfv-tree-indent` | the indentation per level: `--lumo-space-l` / `--vaadin-padding-l` |
| `--tfv-tree-item-gap` | between toggle, icon and label: `--lumo-space-xs` / `--vaadin-gap-xs` |
| `--tfv-tree-item-padding-block`, `--tfv-tree-item-padding-inline` | `--lumo-space-xs` / `--vaadin-padding-xs`, `--lumo-space-s` / `--vaadin-padding-s` |
| `--tfv-tree-item-border-radius` | `--lumo-border-radius-m` / `--vaadin-radius-m` |
| `--tfv-tree-item-hover-background` | `--lumo-contrast-5pct` / `--vaadin-background-container` |
| `--tfv-tree-selected-background` | `--aura-accent-text-color` / `--lumo-primary-text-color` |
| `--tfv-tree-selected-text-color` | `--aura-background-color` / `--lumo-base-color` |
| `--tfv-tree-selection-path-color` | `--aura-accent-text-color` / `--lumo-primary-text-color` |
| `--tfv-tree-focus-ring-color` | `--lumo-primary-text-color` / `--vaadin-focus-ring-color` (Aura sets the latter) |
| `--tfv-tree-focus-ring-width` | `--vaadin-focus-ring-width`, or 2px |

The defaults meet WCAG contrast in Aura, Lumo light and Lumo dark: text 4.5:1, and the selected fill, focus ring
and selection path marker 3:1 (checked by `TreeThemesIT`). In forced colors mode (Windows high contrast), the
selected item takes the system's `Highlight` colors.

## Development

- Run the demo: `./mvnw` at the root (builds the add-on, then runs the demo), then open http://localhost:8080
- Test: `./mvnw verify`. It runs
  - the add-on's browserless tests (`addon/src/test/java`),
  - the `<tfv-tree>` Lit tests with `@web/test-runner` (`addon/src/test/frontend`),
  - the Playwright and axe-core tests against the packaged demo (`demo/src/test/java`, `*IT`).

  `-DskipITs` skips the demo tests; `-DskipTests` skips all of them.
- Lit tests alone: `cd addon && npm ci && npm test`

The Lit element (`addon/src/main/resources/META-INF/resources/frontend/tfv-tree/`) ships inside the jar and is
loaded with `@JsModule`. `addon/package.json` only holds test tooling: at run time Lit, `<vaadin-icon>`, `<vaadin-tooltip>` and Vaadin's live region (`@vaadin/a11y-base`) come from the Vaadin app.

## License

[Apache License 2.0](LICENSE)
