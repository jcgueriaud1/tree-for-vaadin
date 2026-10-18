# Tree for Vaadin

An accessible tree component for Vaadin Flow whose items are loaded lazily from the server.

- Java 21
- Vaadin 25.3. The add-on depends on Vaadin only, not on Spring.

The words in bold below are defined in [GLOSSARY.md](GLOSSARY.md); the design decisions are in [docs/adr](docs/adr).

## Status

Walking skeleton: the tree shows its **root items**. Expanding items, selection and keyboard navigation
beyond moving focus up and down are still to come.

## Usage

```java
TreeData<Folder> data = new TreeData<>();
data.addRootItems(documents, music, pictures);

Tree<Folder> tree = new Tree<>();
tree.setItems(data);                    // or setDataProvider(anyHierarchicalDataProvider)
tree.setItemLabelGenerator(Folder::name);
tree.setAriaLabel("Folders");           // the accessible name of the tree
```

The tree is rendered as an ARIA `tree` whose **items** are `treeitem`s. The browser fetches them from the
server a page at a time through the add-on's own protocol ([ADR-0001](docs/adr/0001-own-lazy-loading-protocol.md)).

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
