# Tree for Vaadin

A Vaadin Flow add-on providing an accessible tree component whose items are loaded lazily from the server.

## Language

**Item**:
One of the developer's domain objects shown as an entry in the tree.
_Avoid_: Node, element, entry

**Root item**:
An item with no parent.
_Avoid_: Top-level node

**Child item**:
An item that has a parent item.
_Avoid_: Sub-node, leaf (a leaf is just an item with no children)

**Expanded**:
The state of an item whose child items are shown; its opposite is **collapsed**.
_Avoid_: Open, unfolded

**Visible item**:
An item whose ancestors are all expanded, so it can be reached in the tree.
_Avoid_: Rendered item, shown node

**Focused item**:
The single item that receives keyboard input; independent of selection.
_Avoid_: Active item, current item

**Disabled item**:
An item the user can focus, expand and collapse, but not select.
_Avoid_: Inactive item, greyed-out item, read-only item (read-only is the whole tree)

## Selection

**Selected item**:
The single item that is the tree's value in single-select mode; there is at most one.
_Avoid_: Active item, chosen item

**Selection path**:
The ancestors of the selected item, marked as containing the selection without being selected themselves.
_Avoid_: Partially selected parent, highlighted branch

**Checked item**:
An item marked as chosen in checkbox mode; checking a parent checks all of its descendants.
_Avoid_: Selected item (in checkbox mode), ticked item

**Partially checked item**:
In checkbox mode, a parent with some, but not all, of its descendants checked.
_Avoid_: Mixed item, half-selected, indeterminate item
