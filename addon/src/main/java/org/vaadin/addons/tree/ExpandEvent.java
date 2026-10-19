package org.vaadin.addons.tree;

import java.util.Collection;
import java.util.List;

import com.vaadin.flow.component.ComponentEvent;

/**
 * Fired when items of a {@link Tree} are expanded, by the user or by
 * {@link Tree#expand(Collection)}.
 *
 * @param <T>
 *            the type of the items
 */
public class ExpandEvent<T> extends ComponentEvent<Tree<T>> {

    private final List<T> items;

    /**
     * Creates an event for items that were expanded.
     *
     * @param source
     *            the tree, not {@code null}
     * @param fromClient
     *            {@code true} if the user expanded the items
     * @param items
     *            the items that were expanded
     */
    public ExpandEvent(Tree<T> source, boolean fromClient, Collection<T> items) {
        super(source, fromClient);
        this.items = List.copyOf(items);
    }

    /**
     * Gets the items that were expanded.
     *
     * @return the expanded items, never empty
     */
    public List<T> getItems() {
        return items;
    }
}
