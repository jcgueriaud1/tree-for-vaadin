package org.vaadin.addons.tree;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The checked items of a tree in checkbox selection mode, kept as the top-most
 * checked items (ADR-0002): checking an item checks its descendants without
 * listing them, and checking every child item of a parent checks the parent
 * instead.
 *
 * @param <T>
 *            the type of the items
 */
final class CheckedItems<T> implements Serializable {

    /** What checking needs to know of the items' hierarchy. */
    interface Hierarchy<T> extends Serializable {

        /** The ancestors of an item, the root item first; throws if the data provider cannot tell. */
        List<T> ancestors(T item);

        /** How many child items an item has. */
        int childCount(T parent);

        /** The child items of an item. */
        Stream<T> children(T parent);

        /** The id of an item, which tells whether two items are the same. */
        Object id(T item);
    }

    private final Hierarchy<T> hierarchy;
    /** The top-most checked items by id, in the order they were checked, with their ancestors. */
    private final Map<Object, Checked<T>> top = new LinkedHashMap<>();
    /** The ancestors of the top-most checked items by id: the partially checked items. */
    private Map<Object, T> partial = Map.of();

    private record Checked<T>(T item, List<T> ancestors) implements Serializable {

        /** The item's parent, or {@code null} for a root item. */
        T parent() {
            return ancestors.isEmpty() ? null : ancestors.getLast();
        }
    }

    CheckedItems(Hierarchy<T> hierarchy) {
        this.hierarchy = hierarchy;
    }

    /** The top-most checked items: the value. */
    Set<T> items() {
        Set<T> items = new LinkedHashSet<>();
        top.values().forEach(checked -> items.add(checked.item()));
        return Collections.unmodifiableSet(items);
    }

    /** The partially checked items: those with checked descendants that are not checked themselves. */
    Set<T> partiallyChecked() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(partial.values()));
    }

    /** Whether the item, or one of its ancestors, is checked. */
    boolean isChecked(T item) {
        return isTop(item) || hierarchy.ancestors(item).stream().anyMatch(this::isTop);
    }

    /** Whether the item, given whether its parent is checked, is checked; spares walking its ancestors. */
    boolean isChecked(T item, boolean parentChecked) {
        return parentChecked || isTop(item);
    }

    /** Whether the item is not checked, but some of its descendants are. */
    boolean isPartiallyChecked(T item) {
        return partial.containsKey(hierarchy.id(item));
    }

    /** Whether the item is one of the top-most checked items. */
    boolean isTop(T item) {
        return top.containsKey(hierarchy.id(item));
    }

    /** Checks an item and its descendants, and its parent if all its siblings are checked too, and so on up. */
    void check(T item) {
        Objects.requireNonNull(item, "item");
        List<T> ancestors = hierarchy.ancestors(item);
        if (isTop(item) || ancestors.stream().anyMatch(this::isTop)) {
            return;
        }
        Object id = hierarchy.id(item);
        // Its checked descendants are now covered by it
        top.values().removeIf(checked -> checked.ancestors().stream().anyMatch(a -> hierarchy.id(a).equals(id)));
        top.put(id, new Checked<>(item, ancestors));
        for (int i = ancestors.size() - 1; i >= 0; i--) {
            T parent = ancestors.get(i);
            Object parentId = hierarchy.id(parent);
            List<Object> childIds = top.values().stream()
                    .filter(checked -> checked.parent() != null && hierarchy.id(checked.parent()).equals(parentId))
                    .map(checked -> hierarchy.id(checked.item()))
                    .toList();
            if (childIds.size() < hierarchy.childCount(parent)) {
                break;
            }
            childIds.forEach(top::remove);
            top.put(parentId, new Checked<>(parent, ancestors.subList(0, i)));
        }
        updatePartial();
    }

    /**
     * Unchecks an item and its descendants. Under a checked ancestor, the
     * ancestor is unchecked and, on the way down to the item, the siblings of
     * each item are checked instead.
     */
    void uncheck(T item) {
        Objects.requireNonNull(item, "item");
        Object id = hierarchy.id(item);
        List<T> ancestors = hierarchy.ancestors(item);
        top.values().removeIf(checked -> checked.ancestors().stream().anyMatch(a -> hierarchy.id(a).equals(id)));
        if (top.remove(id) == null) {
            for (int i = 0; i < ancestors.size(); i++) {
                if (isTop(ancestors.get(i))) {
                    top.remove(hierarchy.id(ancestors.get(i)));
                    checkSiblingsDownTo(item, ancestors, i);
                    break;
                }
            }
        }
        updatePartial();
    }

    /** Checks the siblings of each item on the path from the checked ancestor at the index down to the item. */
    private void checkSiblingsDownTo(T item, List<T> ancestors, int from) {
        List<T> path = new ArrayList<>(ancestors);
        path.add(item);
        for (int i = from; i < path.size() - 1; i++) {
            Object nextId = hierarchy.id(path.get(i + 1));
            List<T> childAncestors = List.copyOf(path.subList(0, i + 1));
            hierarchy.children(path.get(i))
                    .filter(child -> !hierarchy.id(child).equals(nextId))
                    .forEach(child -> top.put(hierarchy.id(child), new Checked<>(child, childAncestors)));
        }
    }

    /**
     * Checks the same items again after the data changed, as they may have
     * moved: under new ancestors, under a checked one, or next to the last
     * unchecked siblings. An item no longer in the data, as the data provider
     * tells by throwing, stays in the value, without ancestors, for the app to
     * clear.
     */
    void refresh() {
        List<T> items = new ArrayList<>(items());
        top.clear();
        for (T item : items) {
            try {
                check(item);
            } catch (IllegalArgumentException gone) {
                top.put(hierarchy.id(item), new Checked<>(item, List.of()));
            }
        }
        updatePartial();
    }

    /** Unchecks everything. */
    void clear() {
        top.clear();
        partial = Map.of();
    }

    private void updatePartial() {
        Map<Object, T> ancestors = new LinkedHashMap<>();
        top.values().forEach(checked -> checked.ancestors().forEach(a -> ancestors.putIfAbsent(hierarchy.id(a), a)));
        partial = ancestors;
    }
}
