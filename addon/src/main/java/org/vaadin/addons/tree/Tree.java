package org.vaadin.addons.tree;

import java.util.List;
import java.util.Objects;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.Component;
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
 * items it shows are sent to it.
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

    /** An item as the browser sees it: the key it refers to it by and its label. */
    record TreeItem(String key, String label) {
    }

    /** A page of child items, with the number of children the parent has in all. */
    record ItemPage(List<TreeItem> items, int size) {
    }

    private final KeyMapper<T> keyMapper = new KeyMapper<>();
    private HierarchicalDataProvider<T, ?> dataProvider = new TreeDataProvider<>(new TreeData<>());
    private ItemLabelGenerator<T> itemLabelGenerator = String::valueOf;
    private Registration dataProviderListener;

    /** Creates an empty tree. */
    public Tree() {
        keyMapper.setIdentifierGetter(dataProvider::getId);
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
                .map(item -> new TreeItem(keyMapper.key(item), itemLabelGenerator.apply(item)))
                .toList();
        return new ItemPage(items, size);
    }

    /** Forgets the items sent so far, and has the browser fetch them again. */
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
