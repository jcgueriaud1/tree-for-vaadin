# Own lazy-loading protocol instead of HierarchicalDataCommunicator

The tree loads children on expand, in pages (default 50), and renders every loaded item in the DOM so each `treeitem` stays focusable and keeps a stable position for screen readers. Flow's `HierarchicalDataCommunicator` (public since Vaadin 25) is built around a flattened, virtually scrolled viewport, which we rejected for v1 on accessibility grounds; so the component sits directly on `HierarchicalDataProvider` with its own key mapper and a small `@ClientCallable` "fetch a page of children for key" protocol.

## Consequences

We own the protocol (keys, paging, refresh, expand state) instead of inheriting TreeGrid's tested code. Very large expanded trees cost DOM size; virtual scrolling is a separate future ticket and would likely revisit this decision.
