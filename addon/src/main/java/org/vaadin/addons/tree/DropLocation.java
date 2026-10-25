package org.vaadin.addons.tree;

/** Where an item is dropped, relative to the item it is dropped on. */
public enum DropLocation {
    /** Before the target item, as its previous sibling. */
    BEFORE,
    /** After the target item, as its next sibling. */
    AFTER,
    /** Into the target item, as one of its child items. */
    INTO
}
