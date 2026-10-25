package org.vaadin.addons.tree;

import com.vaadin.flow.component.ComponentEvent;

/**
 * Fired when the user edits the label of an item of a {@link Tree}. The tree
 * then shows the label the item label generator gives the item: to rename the
 * item, change it so that the generator gives the new label; to reject the
 * new label, leave it as it is.
 *
 * @param <T>
 *            the type of the items
 */
public class EditEvent<T> extends ComponentEvent<Tree<T>> {

    private final T item;
    private final String label;

    /**
     * Creates an event for a label the user edited.
     *
     * @param source
     *            the tree, not {@code null}
     * @param fromClient
     *            {@code true} if the user edited the label
     * @param item
     *            the item whose label was edited
     * @param label
     *            the label the user typed
     */
    public EditEvent(Tree<T> source, boolean fromClient, T item, String label) {
        super(source, fromClient);
        this.item = item;
        this.label = label;
    }

    /**
     * Gets the item whose label the user edited.
     *
     * @return the item, never {@code null}
     */
    public T getItem() {
        return item;
    }

    /**
     * Gets the label the user typed, as typed: it may be empty, or the same as
     * another item's.
     *
     * @return the new label, never {@code null}
     */
    public String getLabel() {
        return label;
    }
}
