package org.vaadin.addons.tree;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.Focusable;
import com.vaadin.flow.component.HasAriaLabel;
import com.vaadin.flow.component.HasEnabled;
import com.vaadin.flow.component.HasSize;
import com.vaadin.flow.component.HasStyle;
import com.vaadin.flow.component.ItemLabelGenerator;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.shared.HasTooltip;
import com.vaadin.flow.data.provider.KeyMapper;
import com.vaadin.flow.data.provider.hierarchy.HierarchicalDataProvider;
import com.vaadin.flow.data.provider.hierarchy.HierarchicalQuery;
import com.vaadin.flow.data.provider.hierarchy.TreeData;
import com.vaadin.flow.data.provider.hierarchy.TreeDataProvider;
import com.vaadin.flow.dom.DisabledUpdateMode;
import com.vaadin.flow.shared.Registration;

/**
 * An accessible tree of items, rendered as an ARIA {@code tree} whose items are
 * {@code treeitem}s.
 * <p>
 * Items come from a {@link HierarchicalDataProvider}. The browser fetches them
 * a page at a time through the tree's own protocol (see ADR-0001), so only the
 * items it shows are sent to it: the child items of an item are fetched when it
 * is first expanded, and the next page of a level when the user scrolls near
 * its end.
 *
 * <pre>{@code
 * Tree<Folder> tree = new Tree<>();
 * tree.setItems(folderData);
 * tree.setItemLabelGenerator(Folder::name);
 * tree.setAriaLabel("Folders");
 * }</pre>
 *
 * @param <T>
 *            the type of the items
 */
@Tag("tfv-tree")
@JsModule("./tfv-tree/tfv-tree.ts")
public class Tree<T> extends Component
        implements HasAriaLabel, HasSize, HasStyle, HasEnabled, Focusable<Tree<T>>, HasTooltip {

    /** The largest page of items the browser can fetch at once. */
    static final int MAX_PAGE_SIZE = 500;

    /** How many items the browser fetches at once, unless set otherwise. */
    static final int DEFAULT_PAGE_SIZE = 50;

    /**
     * An item as the browser sees it: the key it refers to it by, its label,
     * whether it has child items and whether it is expanded.
     */
    record TreeItem(String key, String label, boolean expandable, boolean expanded) {
    }

    /** A page of child items, with the number of children the parent has in all. */
    record ItemPage(List<TreeItem> items, int size) {
    }

    private final KeyMapper<T> keyMapper = new KeyMapper<>();
    private HierarchicalDataProvider<T, ?> dataProvider = new TreeDataProvider<>(new TreeData<>());
    private ItemLabelGenerator<T> itemLabelGenerator = String::valueOf;
    private Registration dataProviderListener;
    /** The ids of the expanded items, kept across refreshes. */
    private final Set<Object> expandedIds = new HashSet<>();
    private int pageSize;

    /** Creates an empty tree. */
    public Tree() {
        keyMapper.setIdentifierGetter(dataProvider::getId);
        setPageSize(DEFAULT_PAGE_SIZE);
    }

    /**
     * Shows the items of the given tree data, kept in memory.
     *
     * @param treeData
     *            the items and their hierarchy, not {@code null}
     */
    public void setItems(TreeData<T> treeData) {
        setDataProvider(new TreeDataProvider<>(Objects.requireNonNull(treeData, "treeData")));
    }

    /**
     * Shows the items of the given data provider.
     *
     * @param dataProvider
     *            the source of the items and their hierarchy, not {@code null}
     */
    public void setDataProvider(HierarchicalDataProvider<T, ?> dataProvider) {
        this.dataProvider = Objects.requireNonNull(dataProvider, "dataProvider");
        keyMapper.setIdentifierGetter(dataProvider::getId);
        expandedIds.clear();
        if (isAttached()) {
            listenToDataProvider();
        }
        refresh();
    }

    /**
     * Gets the data provider the items come from.
     *
     * @return the data provider, never {@code null}
     */
    public HierarchicalDataProvider<T, ?> getDataProvider() {
        return dataProvider;
    }

    /**
     * Sets how each item is labelled. By default the label is the item's
     * {@code toString()}.
     *
     * @param itemLabelGenerator
     *            gives the label of an item, not {@code null}
     */
    public void setItemLabelGenerator(ItemLabelGenerator<T> itemLabelGenerator) {
        this.itemLabelGenerator = Objects.requireNonNull(itemLabelGenerator, "itemLabelGenerator");
        refresh();
    }

    /**
     * Gets how each item is labelled.
     *
     * @return the item label generator, never {@code null}
     */
    public ItemLabelGenerator<T> getItemLabelGenerator() {
        return itemLabelGenerator;
    }

    /**
     * Sets how many items the browser fetches at once: the root items, and the
     * child items of an expanded item, come a page at a time. The default is
     * {@value #DEFAULT_PAGE_SIZE}.
     *
     * @param pageSize
     *            the number of items in a page, from 1 to {@value #MAX_PAGE_SIZE}
     */
    public void setPageSize(int pageSize) {
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize must be between 1 and " + MAX_PAGE_SIZE + ": " + pageSize);
        }
        this.pageSize = pageSize;
        getElement().setProperty("pageSize", pageSize);
    }

    /**
     * Gets how many items the browser fetches at once.
     *
     * @return the number of items in a page
     */
    public int getPageSize() {
        return pageSize;
    }

    /**
     * Expands the given items, so that their child items are shown. Items
     * already expanded are left as they are. Expanding an item does not expand
     * its ancestors: its child items show once they are all expanded too.
     *
     * @param items
     *            the items to expand
     */
    @SafeVarargs
    public final void expand(T... items) {
        expand(Arrays.asList(items));
    }

    /**
     * Expands the given items, so that their child items are shown. Items
     * already expanded are left as they are. Expanding an item does not expand
     * its ancestors: its child items show once they are all expanded too.
     *
     * @param items
     *            the items to expand, not {@code null}
     */
    public void expand(Collection<T> items) {
        setExpanded(items, true, false);
    }

    /**
     * Collapses the given items, so that their child items are hidden. Items
     * already collapsed are left as they are. Their expanded descendants stay
     * expanded, and show again when the items are expanded again.
     *
     * @param items
     *            the items to collapse
     */
    @SafeVarargs
    public final void collapse(T... items) {
        collapse(Arrays.asList(items));
    }

    /**
     * Collapses the given items, so that their child items are hidden. Items
     * already collapsed are left as they are. Their expanded descendants stay
     * expanded, and show again when the items are expanded again.
     *
     * @param items
     *            the items to collapse, not {@code null}
     */
    public void collapse(Collection<T> items) {
        setExpanded(items, false, false);
    }

    /**
     * Tells whether an item is expanded.
     *
     * @param item
     *            the item, not {@code null}
     * @return {@code true} if the item is expanded
     */
    public boolean isExpanded(T item) {
        return expandedIds.contains(dataProvider.getId(Objects.requireNonNull(item, "item")));
    }

    /**
     * Listens to items being expanded, by the user or by {@link #expand}.
     *
     * @param listener
     *            the listener, not {@code null}
     * @return a handle to stop listening
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public Registration addExpandListener(ComponentEventListener<ExpandEvent<T>> listener) {
        return addListener(ExpandEvent.class, (ComponentEventListener) listener);
    }

    /**
     * Listens to items being collapsed, by the user or by {@link #collapse}.
     *
     * @param listener
     *            the listener, not {@code null}
     * @return a handle to stop listening
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public Registration addCollapseListener(ComponentEventListener<CollapseEvent<T>> listener) {
        return addListener(CollapseEvent.class, (ComponentEventListener) listener);
    }

    private void setExpanded(Collection<T> items, boolean expanded, boolean fromClient) {
        List<T> changed = new ArrayList<>();
        for (T item : items) {
            Object id = dataProvider.getId(Objects.requireNonNull(item, "item"));
            if (expanded ? expandedIds.add(id) : expandedIds.remove(id)) {
                changed.add(item);
            }
        }
        if (changed.isEmpty()) {
            return;
        }
        if (!fromClient) {
            // Items the browser has not fetched yet come expanded when it does
            changed.stream()
                    .filter(keyMapper::has)
                    .forEach(item -> getElement().callJsFunction("setItemExpanded", keyMapper.key(item), expanded));
        }
        fireEvent(expanded
                ? new ExpandEvent<>(this, fromClient, changed)
                : new CollapseEvent<>(this, fromClient, changed));
    }

    /**
     * Expands or collapses an item, as the user did in the browser.
     *
     * @param key
     *            the key of the item; an item no longer known is ignored
     * @param expanded
     *            {@code true} to expand the item, {@code false} to collapse it
     */
    @ClientCallable
    void setExpanded(String key, boolean expanded) {
        T item = keyMapper.get(key);
        if (item != null) {
            setExpanded(List.of(item), expanded, true);
        }
    }

    /**
     * Fetches a page of the child items of a parent, for the browser.
     * <p>
     * Allowed while the tree is disabled: a disabled tree still shows its items.
     *
     * @param parentKey
     *            the key of the parent item, or {@code null} for the root items
     * @param offset
     *            the index of the first child item to fetch
     * @param limit
     *            how many child items to fetch at most, capped at
     *            {@value #MAX_PAGE_SIZE}
     * @return the page, empty if the parent is no longer known
     */
    @ClientCallable(DisabledUpdateMode.ALWAYS)
    ItemPage fetchChildren(String parentKey, int offset, int limit) {
        if (offset < 0 || limit < 0) {
            throw new IllegalArgumentException("offset and limit must not be negative");
        }
        T parent = null;
        if (parentKey != null) {
            parent = keyMapper.get(parentKey);
            if (parent == null) {
                return new ItemPage(List.of(), 0);
            }
        }
        return fetchChildren(dataProvider, parent, offset, Math.min(limit, MAX_PAGE_SIZE));
    }

    private <F> ItemPage fetchChildren(HierarchicalDataProvider<T, F> provider, T parent, int offset, int limit) {
        int size = provider.getChildCount(new HierarchicalQuery<>(null, parent));
        List<TreeItem> items = provider
                .fetchChildren(new HierarchicalQuery<>(offset, limit, List.of(), null, null, parent))
                .map(item -> new TreeItem(keyMapper.key(item), itemLabelGenerator.apply(item),
                        provider.hasChildren(item), isExpanded(item)))
                .toList();
        return new ItemPage(items, size);
    }

    /**
     * Forgets the items sent so far, and has the browser fetch them again.
     * Expanded items stay expanded.
     */
    private void refresh() {
        keyMapper.removeAll();
        if (isAttached()) {
            getElement().callJsFunction("refresh");
        }
    }

    private void listenToDataProvider() {
        if (dataProviderListener != null) {
            dataProviderListener.remove();
        }
        dataProviderListener = dataProvider.addDataProviderListener(event -> refresh());
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        listenToDataProvider();
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        if (dataProviderListener != null) {
            dataProviderListener.remove();
            dataProviderListener = null;
        }
        super.onDetach(detachEvent);
    }
}
