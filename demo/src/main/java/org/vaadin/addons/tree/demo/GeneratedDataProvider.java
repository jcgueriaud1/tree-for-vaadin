package org.vaadin.addons.tree.demo;

import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.vaadin.flow.data.provider.hierarchy.AbstractBackEndHierarchicalDataProvider;
import com.vaadin.flow.data.provider.hierarchy.HierarchicalQuery;

/**
 * A tree of any size made up as it is fetched, to see how the tree copes with
 * many items: a number of root items, each with the same number of child
 * items, down to a number of levels. Nothing is kept in memory.
 */
public class GeneratedDataProvider extends AbstractBackEndHierarchicalDataProvider<GeneratedDataProvider.Item, Void> {

    /** An item, by its path of 1-based positions from the root, such as 3.1.4. */
    public record Item(int[] path) {

        /** The item's label, such as "Item 3.1.4". */
        public String name() {
            return "Item " + Arrays.stream(path).mapToObj(String::valueOf).collect(Collectors.joining("."));
        }

        /** 1 for a root item. */
        int level() {
            return path.length;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Item item && Arrays.equals(path, item.path);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(path);
        }

        @Override
        public String toString() {
            return name();
        }
    }

    private final int rootCount;
    private final int childCount;
    private final int levels;

    /**
     * Creates a tree of the given shape.
     *
     * @param rootCount
     *            how many root items
     * @param childCount
     *            how many child items each item above the last level has
     * @param levels
     *            how many levels, 1 for root items only
     */
    public GeneratedDataProvider(int rootCount, int childCount, int levels) {
        this.rootCount = rootCount;
        this.childCount = childCount;
        this.levels = levels;
    }

    /** How many items the tree has in all. */
    public long size() {
        long size = 0;
        long levelSize = rootCount;
        for (int level = 1; level <= levels; level++) {
            size += levelSize;
            levelSize *= childCount;
        }
        return size;
    }

    /** The last item of the tree, at its deepest level. */
    public Item lastItem() {
        int[] path = new int[childCount == 0 ? 1 : levels];
        Arrays.fill(path, childCount);
        path[0] = rootCount;
        return new Item(path);
    }

    /** The root items. */
    public Stream<Item> rootItems() {
        return IntStream.rangeClosed(1, rootCount).mapToObj(index -> new Item(new int[] { index }));
    }

    @Override
    public int getChildCount(HierarchicalQuery<Item, Void> query) {
        return query.getParent() == null ? rootCount : hasChildren(query.getParent()) ? childCount : 0;
    }

    @Override
    public boolean hasChildren(Item item) {
        return item.level() < levels && childCount > 0;
    }

    @Override
    public Item getParent(Item item) {
        return item.level() == 1 ? null : new Item(Arrays.copyOf(item.path(), item.level() - 1));
    }

    @Override
    protected Stream<Item> fetchChildrenFromBackEnd(HierarchicalQuery<Item, Void> query) {
        Item parent = query.getParent();
        int count = getChildCount(query);
        int from = Math.min(query.getOffset(), count);
        int to = Math.min(from + query.getLimit(), count);
        return IntStream.rangeClosed(from + 1, to).mapToObj(index -> {
            int[] path = parent == null ? new int[] { index } : Arrays.copyOf(parent.path(), parent.level() + 1);
            path[path.length - 1] = index;
            return new Item(path);
        });
    }
}
