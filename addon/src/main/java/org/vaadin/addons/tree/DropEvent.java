package org.vaadin.addons.tree;

import com.vaadin.flow.component.ComponentEvent;

/**
 * Fired when the user drops an item of a {@link Tree} before, after or into
 * another one, by dragging it or with the keyboard. The tree moves nothing
 * itself: move the item in the data and refresh the data provider, or leave it
 * where it is to refuse the move.
 *
 * @param <T>
 *            the type of the items
 */
public class DropEvent<T> extends ComponentEvent<Tree<T>> {

    private final T draggedItem;
    private final T targetItem;
    private final DropLocation location;

    /**
     * Creates an event for an item dropped on another.
     *
     * @param source
     *            the tree, not {@code null}
     * @param fromClient
     *            {@code true} if the user dropped the item
     * @param draggedItem
     *            the item dropped
     * @param targetItem
     *            the item it was dropped on
     * @param location
     *            where, relative to the target item
     */
    public DropEvent(Tree<T> source, boolean fromClient, T draggedItem, T targetItem, DropLocation location) {
        super(source, fromClient);
        this.draggedItem = draggedItem;
        this.targetItem = targetItem;
        this.location = location;
    }

    /**
     * Gets the item the user dropped.
     *
     * @return the dragged item, never {@code null}
     */
    public T getDraggedItem() {
        return draggedItem;
    }

    /**
     * Gets the item the user dropped it on: never the dragged item itself, nor
     * one of its descendants.
     *
     * @return the target item, never {@code null}
     */
    public T getTargetItem() {
        return targetItem;
    }

    /**
     * Gets where the user dropped the item, relative to the target item.
     *
     * @return the drop location, never {@code null}
     */
    public DropLocation getLocation() {
        return location;
    }
}
