# Tree for Vaadin

An accessible tree component for Vaadin Flow whose items are loaded lazily from the server.

**Live demo:** https://tree-for-vaadin.onrender.com. It runs on Render's free plan, so the first visit after a
quiet spell can take about a minute while it wakes up.

- Java 21
- Vaadin 25.3. The add-on depends on Vaadin only, not on Spring.

The words in bold below are defined in [GLOSSARY.md](GLOSSARY.md); the design decisions are in [docs/adr](docs/adr).

## Features

- An ARIA `tree`, labelled, that screen readers and keyboards can use: the keys of the
  [ARIA tree pattern](https://www.w3.org/WAI/ARIA/apg/patterns/treeview/), type-ahead included.
- **Items** fetched lazily from any `HierarchicalDataProvider`, a page at a time, as the user expands items and scrolls.
- **Expanding** and **collapsing** by the user or by the server, with events telling which.
- One **selected item**, bound like any field with `asSingleSelect()`, with its ancestors marked; or checkboxes,
  where checking a parent checks its descendants, loaded or not, with `asMultiSelect()`.
- Icons and tooltips on items, a context menu, renaming items in place, and moving them by drag and drop or with
  the keyboard.
- Filtering through the data provider, and searching for items not loaded yet.
- Data refreshes that keep the state of the tree, and messages for an empty tree and for items that fail to load,
  in your language.
- Read-only and disabled states, for the whole tree or for single items.
- Touch screens: items and their toggle are fingertip-sized there.
- The Aura and Lumo themes, light or dark, and styling through parts and CSS custom properties.

Virtual scrolling and the other ideas for later are in the [issues](https://github.com/jcgueriaud1/tree-for-vaadin/issues).

## Installation

Add the dependency to your Vaadin application:

```xml
<dependency>
    <groupId>org.vaadin.addons</groupId>
    <artifactId>tree-for-vaadin</artifactId>
    <version>1.0.0</version>
</dependency>
```

The add-on is not published yet. Until it is, build it with `./mvnw install` and depend on `1.0.0-SNAPSHOT`.

## Quick start

```java
TreeData<Folder> data = new TreeData<>();
data.addRootItems(documents, music, pictures);
data.addItems(documents, invoices, letters);

Tree<Folder> tree = new Tree<>();
tree.setItems(data);
tree.setItemLabelGenerator(Folder::name);
tree.setAriaLabel("Folders");           // the accessible name of the tree: always give it one
tree.asSingleSelect().addValueChangeListener(event -> open(event.getValue()));
add(tree);
```

A tree needs an accessible name: `setAriaLabel`, or `setAriaLabelledBy` the id of a visible label.

## Items from a backend

`setDataProvider` takes any `HierarchicalDataProvider`. The tree asks it for the child items of an item when the
user first expands it, and for the next page of a level when the user scrolls near its end, so a level can hold
thousands of items:

```java
public class CatalogDataProvider extends AbstractBackEndHierarchicalDataProvider<Entry, Void> {

    @Override
    public int getChildCount(HierarchicalQuery<Entry, Void> query) {
        return query.getParent() == null ? categories.count() : products.countIn((Category) query.getParent());
    }

    @Override
    public boolean hasChildren(Entry entry) {
        return entry instanceof Category category && products.countIn(category) > 0;
    }

    @Override
    public Entry getParent(Entry entry) {        // for the selection path and scrollToItem
        return entry instanceof Product product ? product.category() : null;
    }

    @Override
    protected Stream<Entry> fetchChildrenFromBackEnd(HierarchicalQuery<Entry, Void> query) {
        return query.getParent() == null
                ? categories.fetch(query.getOffset(), query.getLimit())
                : products.fetchIn((Category) query.getParent(), query.getOffset(), query.getLimit());
    }
}

tree.setDataProvider(new CatalogDataProvider());
tree.setPageSize(100);                   // how many items each fetch asks for; 50 by default
```

The demo's [`CatalogDataProvider`](demo/src/main/java/org/vaadin/addons/tree/demo/CatalogDataProvider.java) is a
complete one.

## Usage

```java
tree.setItemIconGenerator(folder -> "vaadin:folder");  // an icon name, or null for none
tree.setItemTooltipGenerator(Folder::description);     // the tooltip text, or null for none
tree.setItemEnabledProvider(folder -> !folder.archived()); // a disabled item cannot be selected

tree.expand(documents);                 // or collapse(...), isExpanded(...)
tree.addExpandListener(event -> log("Expanded " + event.getItems() + (event.isFromClient() ? " by the user" : "")));
tree.addCollapseListener(event -> log("Collapsed " + event.getItems()));

tree.asSingleSelect().setValue(letters); // the selected item, or null for none
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

### Selection

There is one **selected item** at most (`setSelectionMode(SelectionMode.SINGLE)`, the default), and
every item can be selected, parents too. The `Tree` is not itself a `HasValue`: `asSingleSelect()` is, so it can
be bound with a `Binder`:

```java
binder.forField(tree.asSingleSelect()).bind(Shortcut::getFolder, Shortcut::setFolder);
```

Clicking an item selects it (clicking its toggle only expands or collapses it);
clicking the selected item again keeps it selected, and only the server clears the selection, with
`setValue(null)`. Items are `aria-selected`. Expanding and collapsing never change the selection, and setting
the value never expands anything. The ancestors of the selected item, its **selection path**, have the
`selection-path` attribute and a dot marker (part `selection-path-marker`), collapsed or not. As the marker is
for the eyes, each of them is also described to assistive technology as "Contains the selected item" (before its
tooltip text, if any), so a screen reader on a collapsed folder tells that the selection is inside. This needs the
data provider to implement `getParent`, as `TreeDataProvider` does, and is left out otherwise.
`scrollToItem(item)` needs `getParent` too: it expands the item's ancestors and fetches the pages on the way
down, then scrolls the item into view.

`asSingleSelect().setReadOnly(true)` (or `binder.setReadOnly(true)`) keeps the user from changing the selection;
they can still expand, collapse and move through the items. `setEnabled(false)` disables the whole tree: it
still shows its items, dimmed, but takes no input and is out of the tab order (`aria-disabled`).

`setItemEnabledProvider` disables single items instead. A disabled item looks muted and is `aria-disabled`; the
user can still focus it, read its tooltip, and expand and collapse it, so its child items stay reachable, but
cannot select it, by clicking or with Enter or Space. The server can still select it with `setValue`. Give a
disabled item a tooltip that says why it is disabled: it is also its accessible description.

### Checkbox selection

`setSelectionMode(SelectionMode.CHECKBOX)` gives each item a checkbox instead: clicking an item, or Space or Enter,
checks or unchecks it, and its descendants with it, loaded or not. An item with some checked descendants, but not
all, is a **partially checked item**; checking it checks it as a whole. Changing the mode clears the selection.

```java
tree.setSelectionMode(SelectionMode.CHECKBOX);
tree.asMultiSelect().select(documents);          // checks Documents and everything under it
tree.asMultiSelect().getValue();                 // {documents}: the top-most checked items
tree.isChecked(letters);                         // true: an ancestor is checked
tree.asMultiSelect().deselect(letters);          // {invoices}: Documents is partially checked now
tree.isPartiallyChecked(documents);              // true
binder.forField(tree.asMultiSelect()).bind(Order::getFolders, Order::setFolders);
```

The value is the top-most checked items ([ADR-0002](docs/adr/0002-checked-value-is-the-top-most-checked-items.md)):
checking a parent gives the parent, not its descendants, so checking a root of a large backend fetches nothing, and
checking the last unchecked child of a parent gives the parent instead of its child items. Unchecking an item
under a checked ancestor checks its siblings instead, fetching the child items of each item on the way down to it.
`isChecked(item)` tells whether any item is checked, and `asMultiSelect().isSelected(item)` says the same.
Checkbox selection needs the data provider to implement `getParent`, as `TreeDataProvider` does.

Items are `aria-checked`, `mixed` when partially checked, and the tree is `aria-multiselectable`; there is no
`aria-selected` in this mode. Read-only and disabled items work as in single selection: the user cannot check
them, but checking a parent checks its disabled descendants too.

### Context menu

`addContextMenu()` gives the tree a `TreeContextMenu`, a Vaadin `ContextMenu` that knows the item it opens on:
the item the user right-clicks or long-presses, or the focused item when they press Shift+F10 or the Menu key.

```java
TreeContextMenu<Folder> menu = tree.addContextMenu();
menu.addItem("Open", event -> menu.getTargetItem().ifPresent(this::open));
menu.addItem("Delete", event -> menu.getTargetItem().ifPresent(this::delete));

// Or items that depend on the item, and no menu outside the items
menu.setDynamicContentHandler(folder -> {
    if (folder == null) {
        return false;
    }
    menu.removeAll();
    menu.addItem(tree.isExpanded(folder) ? "Collapse" : "Expand", event -> toggle(folder));
    return true;
});
```

`getTargetItem()` is empty when the menu opens outside the items. The menu opens on disabled items too, as the
app may offer something there, but not on a disabled tree. Opening it changes neither the selection nor the focused
item; it takes focus, and gives it back to the item when it closes.

### Renaming items

`setItemEditableProvider` lets the user rename items in place: F2 or a double-click puts a text field (part
`editor`) in place of the label of the focused item, Enter or leaving the field saves, and Escape cancels; either
gives focus back to the item. Saving fires an `EditEvent`, then the tree shows the label your label generator
gives the item: rename the item in the listener to keep the new label, or leave it as it is to reject it.

```java
tree.setItemEditableProvider(project -> true);
tree.addEditListener(event -> {
    String name = event.getLabel().strip();
    if (!name.isEmpty()) {
        event.getItem().setName(name);
        tree.getDataProvider().refreshItem(event.getItem());
    }
});

// On a touch screen there is no F2: start renaming from a context menu
TreeContextMenu<Project> menu = tree.addContextMenu();
menu.addItem("Rename", event -> menu.getTargetItem().ifPresent(tree::editItem));
```

`editItem(item)` starts renaming an item the browser shows. Disabled items, and any item while the selection is
read-only, cannot be renamed. The text field is named "Label" for assistive technology (`TreeI18n.setEditor`).

### Moving items: drag and drop

`setItemDraggableProvider` lets the user move items: drag one and drop it before, into or after another, by where
the pointer is over it (its top quarter, middle or bottom quarter), or, with the keyboard, cut the focused item with
Ctrl+X (⌘X) and paste it into the focused item with Ctrl+V, or before it with Ctrl+Shift+V; Escape cancels. The
tree announces how to paste when an item is cut. An item is never dropped on itself nor into its own descendants,
and `setItemDroppableProvider` keeps drops off items. Disabled items, and any item while the selection is
read-only, cannot be moved.

Dropping fires a `DropEvent`; the tree moves nothing itself, so move the item in your data and refresh it:

```java
tree.setItemDraggableProvider(project -> true);
tree.setItemDroppableProvider(project -> !project.isArchived());
tree.addDropListener(event -> {
    projects.move(event.getDraggedItem(), event.getTargetItem(), event.getLocation()); // BEFORE, AFTER or INTO
    tree.getDataProvider().refreshAll();     // expanded items stay expanded
});
```

Drag and drop uses the browser's own, which most touch screens lack, and they have no Ctrl+X either. There, move
items from a context menu, which a long press opens: `cutItem(item)` cuts an item as Ctrl+X does, showing it cut
and announcing it, and `pasteInto`, `pasteBefore` and `pasteAfter` drop it, firing the same `DropEvent`, if it can
go there; they return whether it did. `getCutItem()` tells what is cut, by the menu or by Ctrl+X, and
`cancelCut()` cancels it, as Escape does.

```java
TreeContextMenu<Project> menu = tree.addContextMenu();
menu.setDynamicContentHandler(project -> {
    menu.removeAll();
    menu.addItem("Cut", event -> tree.cutItem(project));
    tree.getCutItem().ifPresent(cut -> {
        menu.addItem("Paste into", event -> tree.pasteInto(project));
        menu.addItem("Paste before", event -> tree.pasteBefore(project));
    });
    return project != null;
});
```

The demo's "Drag and drop" section moves files in a tree with single selection and in one with checkboxes, where
checked items keep their state where they go; its projects can also be cut and pasted from their context menu.

With checkboxes, the tree checks the same items again when the data is refreshed: a checked item moved under
another parent marks its new ancestors as partially checked, and a parent whose child items are now all checked
becomes checked itself, with a value change event. A checked item removed from the data stays in the value for the
app to clear.

### Filtering and search

The tree shows what its data provider gives it, filter included. `TreeDataProvider`'s filter keeps the ancestors
of the items that match, and `expandRecursively` expands them to show the matches:

```java
TreeDataProvider<Folder> provider = new TreeDataProvider<>(data);
tree.setDataProvider(provider);
tree.setI18n(new TreeI18n().setEmpty("No folder matches"));

filterField.addValueChangeListener(event -> {
    provider.setFilter(folder -> folder.name().contains(event.getValue()));
    tree.expandRecursively(data.getRootItems(), Integer.MAX_VALUE);
});
```

`expandRecursively(items, depth)` and `collapseRecursively(items, depth)` work as in TreeGrid: depth 0 is the
items alone. They fetch the child items of every item down to the depth, with the filter, so mind the cost with a
large backend. With an in-memory data provider, an item whose child items are all filtered out has no toggle; a
backend data provider's `hasChildren` is trusted as it is, so make it follow the filter.

To find an item the tree has not fetched yet, ask your backend for it and reveal it with `scrollToItem`, which
needs `getParent`:

```java
catalog.find(searchField.getValue()).ifPresent(product -> {
    tree.scrollToItem(product);              // expands its ancestors, fetching the pages on the way
    tree.asSingleSelect().setValue(product);
});
```

Tell screen reader users the outcome, how many items match or that none does, in a live region such as a
`role="status"` paragraph: the tree does not announce changes to its items.

### Data changes, errors and texts (i18n)

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

The texts the tree shows by itself, "Loading…", "No items" and the load error, and the description of the
selection path, "Contains the selected item", the name of the text field that renames an item, "Label", and what is
announced when an item is cut (`cut` and, after Ctrl+X, `cutHint`), are English by default and set with `setI18n`. Set the object again after changing it:

```java
tree.setI18n(new TreeI18n()
        .setLoading("Chargement…")
        .setEmpty("Aucun élément")
        .setLoadError("Impossible de charger les éléments")
        .setSelectionPath("Contient l’élément sélectionné")
        .setEditor("Nom"));
```

Item labels and tooltips come from your generators, so translate them there.

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
| Enter, Space | Selects the focused item, unless it is disabled; with checkboxes, checks or unchecks it |
| `*` | Expands the focused item and its siblings, in one `ExpandEvent` |
| Shift+F10, Menu key | Opens the context menu of the focused item, if the tree has one |
| F2 | Renames the focused item, if it can be renamed; then Enter saves and Escape cancels |
| Ctrl+X, then Ctrl+V or Ctrl+Shift+V | Moves the focused item, if it can be moved, into or before the item focused then; Escape cancels |
| Any character | Moves focus to the next visible item whose label starts with it; characters typed within half a second, spaces included, are searched together |

Left and Right are swapped when the tree is right to left (`dir="rtl"`). Type-ahead only searches the items
fetched and visible. When an item is collapsed while one of its descendants has focus, focus moves to it. The
focused item has a focus ring; the selected item a background.

### Touch screens

A tap on an item selects it, and a tap on its toggle expands or collapses it. With a mouse, the toggle takes
clicks in a 24px square around its chevron at least (WCAG 2.5.8). On a touch screen (`pointer: coarse`), items
are at least 44px tall, and the toggle is a 44px column as tall as its item, so a thumb that lands beside the
chevron still opens the item.

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
| `editor` | The text field in place of the label of an item being renamed |
| `selection-path-marker` | The dot after an ancestor of the selected item |
| `checkbox` | The checkbox of an item, in checkbox selection mode |
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
| `checked` | the item is checked, in checkbox selection mode |
| `partially-checked` | some of the item's descendants are checked, but not all |
| `focused` | the item has focus |
| `focus-ring` | the item has focus from the keyboard, and shows a focus ring |
| `dragged`, `cut` | the item is being dragged, or was cut with Ctrl+X to be moved |
| `drop-before`, `drop-into`, `drop-after` | the item dragged would be dropped before, into or after this item |
| `disabled` | the item is disabled by `setItemEnabledProvider` |

| Custom property | Default: Aura, else Lumo, else the Vaadin base styles |
| --- | --- |
| `--tfv-tree-font-size`, `--tfv-tree-line-height` | `--aura-font-size-m` / `--lumo-font-size-m`, and the matching line heights |
| `--tfv-tree-text-color` | `--lumo-body-text-color` / `--vaadin-text-color` |
| `--tfv-tree-secondary-text-color` | `--lumo-secondary-text-color` / `--vaadin-text-color-secondary`: loading and empty rows, and the defaults of the two below |
| `--tfv-tree-disabled-text-color` | a disabled item: `--lumo-disabled-text-color` / `--vaadin-text-color-disabled` |
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
| `--tfv-tree-checkbox-color`, `--tfv-tree-checkbox-mark-color` | the fill of a checked checkbox and its mark: as the selected item's background and text |
| `--tfv-tree-focus-ring-color` | `--lumo-primary-text-color` / `--vaadin-focus-ring-color` (Aura sets the latter) |
| `--tfv-tree-drop-indicator-color` | where a dragged item would go: `--tfv-tree-focus-ring-color` |
| `--tfv-tree-focus-ring-width` | `--vaadin-focus-ring-width`, or 2px |

The defaults meet WCAG contrast in Aura, Lumo light and Lumo dark: text 4.5:1, and the selected fill, focus ring
and selection path marker 3:1 (checked by `TreeThemesIT`). In forced colors mode (Windows high contrast), the
selected item takes the system's `Highlight` colors.

## Repository layout

| Path | Holds |
| --- | --- |
| `addon/` | The add-on, `org.vaadin.addons:tree-for-vaadin`, depending on Vaadin only |
| `addon/src/main/java/org/vaadin/addons/tree/` | `Tree` and its events and texts |
| `addon/src/main/resources/META-INF/resources/frontend/tfv-tree/` | The `<tfv-tree>` Lit element |
| `addon/src/test/` | Browserless tests of `Tree`, and Lit tests of `<tfv-tree>` |
| `demo/` | The Spring Boot demo, and the Playwright and axe-core tests run against it |
| `docs/adr/` | The design decisions |
| `GLOSSARY.md` | The words the README and the code use for the parts of a tree |

## Development

- Run the demo: `./mvnw` at the root (builds the add-on, then runs the demo), then open http://localhost:8080
- Test: `./mvnw verify`. It runs
  - the add-on's browserless tests (`addon/src/test/java`),
  - the `<tfv-tree>` Lit tests with `@web/test-runner` (`addon/src/test/frontend`),
  - the Playwright and axe-core tests against the packaged demo (`demo/src/test/java`, `*IT`).

  `-DskipITs` skips the demo tests; `-DskipTests` skips all of them.
- Lit tests alone: `cd addon && npm ci && npm test`
- Performance: the demo's `/performance` page builds a tree of any size from a generated backend, and times
  expanding everything, scrolling to the last item, collapsing and checking, from the click until the tree has
  fetched and rendered, with the server's share and the items in the page. For numbers worth comparing, run the
  packaged demo, which is a production build (`./mvnw -DskipTests package`, then
  `java -jar demo/target/tree-for-vaadin-demo.jar`), or its Docker image, rather than `./mvnw`'s development mode.

The Lit element (`addon/src/main/resources/META-INF/resources/frontend/tfv-tree/`) ships inside the jar and is
loaded with `@JsModule`. `addon/package.json` only holds test tooling: at run time Lit, `<vaadin-icon>`, `<vaadin-tooltip>` and Vaadin's live region (`@vaadin/a11y-base`) come from the Vaadin app.

### Hosting the demo

The [live demo](https://tree-for-vaadin.onrender.com) runs on [Render](https://render.com)'s free plan from the `Dockerfile`, which builds the production
bundle and runs it on a small JRE image sized for the free instance (512 MB, 0.1 CPU).
[`render.yaml`](render.yaml) is the Blueprint: on Render, *New > Blueprint* and pick this repository. It
redeploys when `main` changes and the Test workflow passes. Free instances sleep after 15 minutes without
traffic, and take about a minute to wake up.

To try the image locally: `docker build -t tree-for-vaadin-demo . && docker run -p 8080:8080 tree-for-vaadin-demo`.

## License

[Apache License 2.0](LICENSE)
