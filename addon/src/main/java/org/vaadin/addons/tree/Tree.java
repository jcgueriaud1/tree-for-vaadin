package org.vaadin.addons.tree;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import com.vaadin.flow.component.AbstractField.ComponentValueChangeEvent;
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
import com.vaadin.flow.component.dependency.Uses;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.shared.HasTooltip;
import com.vaadin.flow.data.provider.DataChangeEvent;
import com.vaadin.flow.data.provider.DataChangeEvent.DataRefreshEvent;
import com.vaadin.flow.data.provider.KeyMapper;
import com.vaadin.flow.data.provider.hierarchy.HierarchicalDataProvider;
import com.vaadin.flow.data.provider.hierarchy.HierarchicalQuery;
import com.vaadin.flow.data.provider.hierarchy.TreeData;
import com.vaadin.flow.data.provider.hierarchy.TreeDataProvider;
import com.vaadin.flow.data.selection.MultiSelect;
import com.vaadin.flow.data.selection.SingleSelect;
import com.vaadin.flow.dom.DisabledUpdateMode;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.function.SerializableFunction;
import com.vaadin.flow.function.SerializablePredicate;
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
 * <p>
 * The user selects an item by clicking it; the selected item is the value of
 * {@link #asSingleSelect()}. The ancestors of the selected item are marked as
 * its selection path, if the data provider implements
 * {@link HierarchicalDataProvider#getParent(Object) getParent}.
 * <p>
 * Each item can show an icon in front of its label, and a tooltip, from
 * {@link #setItemIconGenerator} and {@link #setItemTooltipGenerator}. Items
 * can be disabled with {@link #setItemEnabledProvider}: the user can focus and
 * expand them, but not select them. {@link #addContextMenu()} adds a context
 * menu for the items. The user can edit the labels of the items
 * {@link #setItemEditableProvider} allows, firing an {@link EditEvent}.
 * <p>
 * When the data changes, {@link HierarchicalDataProvider#refreshAll()} has the
 * browser fetch the items again: items that still exist, by their id, stay
 * expanded, and the value is kept even if its item is gone, for the app to
 * clear. {@link HierarchicalDataProvider#refreshItem(Object, boolean)} updates
 * one item, and fetches its child items again if asked to. If fetching child
 * items fails, the exception goes to the session's
 * {@link com.vaadin.flow.server.ErrorHandler ErrorHandler}, which logs it by
 * default, and the item collapses back while its group briefly shows an
 * error. The texts the tree shows by itself are set with {@link #setI18n}.
 *
 * <pre>{@code
 * Tree<Folder> tree = new Tree<>();
 * tree.setItems(folderData);
 * tree.setItemLabelGenerator(Folder::name);
 * tree.setItemIconGenerator(folder -> "vaadin:folder");
 * tree.setAriaLabel("Folders");
 * tree.asSingleSelect().addValueChangeListener(event -> open(event.getValue()));
 * }</pre>
 *
 * @param <T>
 *            the type of the items
 */
@Tag("tfv-tree")
@JsModule("./tfv-tree/tfv-tree.ts")
@Uses(Icon.class)
public class Tree<T> extends Component
        implements HasAriaLabel, HasSize, HasStyle, HasEnabled, Focusable<Tree<T>>, HasTooltip {

    /** The largest page of items the browser can fetch at once. */
    static final int MAX_PAGE_SIZE = 500;

    /** How many items the browser fetches at once, unless set otherwise. */
    static final int DEFAULT_PAGE_SIZE = 50;

    /** How the user selects items. */
    public enum SelectionMode {
        /** The user selects one item at a time, the value of {@link Tree#asSingleSelect()}. */
        SINGLE
    }

    /**
     * An item as the browser sees it: the key it refers to it by, its label,
     * its icon and tooltip if any, whether it has child items, whether it is
     * expanded, whether it is the selected item, whether it is on the
     * selection path, whether it is disabled and whether the user can edit its
     * label.
     */
    record TreeItem(String key, String label, String icon, String tooltip, boolean expandable, boolean expanded,
            boolean selected, boolean onSelectionPath, boolean disabled, boolean editable) implements Serializable {
    }

    /** A page of child items, with the number of children the parent has in all. */
    record ItemPage(List<TreeItem> items, int size) {
    }

    private final KeyMapper<T> keyMapper = new KeyMapper<>();
    private HierarchicalDataProvider<T, ?> dataProvider = new TreeDataProvider<>(new TreeData<>());
    private ItemLabelGenerator<T> itemLabelGenerator = String::valueOf;
    private SerializableFunction<T, String> itemIconGenerator = item -> null;
    private SerializableFunction<T, String> itemTooltipGenerator = item -> null;
    private SerializablePredicate<T> itemEnabledProvider = item -> true;
    private SerializablePredicate<T> itemEditableProvider = item -> false;
    private Registration dataProviderListener;
    /** The ids of the expanded items, kept across refreshes. */
    private final Set<Object> expandedIds = new HashSet<>();
    private int pageSize;
    private SelectionMode selectionMode = SelectionMode.SINGLE;
    private final SingleSelect<Tree<T>, T> singleSelect = new TreeSingleSelect();
    private T selectedItem;
    /** The ancestors of the selected item, and their ids. */
    private List<T> selectionPath = List.of();
    private Set<Object> selectionPathIds = Set.of();
    private TreeI18n i18n = new TreeI18n();

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
        setSelectedItem(null, false);
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
     * Sets the icon shown in front of each item's label, by its name in an
     * icon set, such as {@code vaadin:folder}, shown with
     * {@code <vaadin-icon>}. The {@code vaadin} icon set comes with the tree;
     * other icon sets must be loaded by the app. Icons are
     * decorative: they are hidden from assistive technology, and the label
     * stays the accessible name of the item. By default items have no icon.
     *
     * @param itemIconGenerator
     *            gives the icon name of an item, or {@code null} for no icon;
     *            not {@code null}
     */
    public void setItemIconGenerator(SerializableFunction<T, String> itemIconGenerator) {
        this.itemIconGenerator = Objects.requireNonNull(itemIconGenerator, "itemIconGenerator");
        refresh();
    }

    /**
     * Gets the icon shown in front of each item's label.
     *
     * @return the item icon generator, never {@code null}
     */
    public SerializableFunction<T, String> getItemIconGenerator() {
        return itemIconGenerator;
    }

    /**
     * Sets the tooltip of each item, shown when the user hovers over the item
     * or focuses it with the keyboard. The tooltip is also the accessible
     * description of the item. By default items have no tooltip.
     *
     * @param itemTooltipGenerator
     *            gives the tooltip text of an item, or {@code null} or an
     *            empty string for no tooltip; not {@code null}
     */
    public void setItemTooltipGenerator(SerializableFunction<T, String> itemTooltipGenerator) {
        this.itemTooltipGenerator = Objects.requireNonNull(itemTooltipGenerator, "itemTooltipGenerator");
        refresh();
    }

    /**
     * Gets the tooltip of each item.
     *
     * @return the item tooltip generator, never {@code null}
     */
    public SerializableFunction<T, String> getItemTooltipGenerator() {
        return itemTooltipGenerator;
    }

    /**
     * Sets which items are enabled. A disabled item looks muted and is
     * {@code aria-disabled}: the user can still focus it, see its tooltip, and
     * expand and collapse it, but cannot select it. The server can still
     * select it with {@link #asSingleSelect()}. By default every item is
     * enabled.
     *
     * @param itemEnabledProvider
     *            tells whether an item is enabled; not {@code null}
     */
    public void setItemEnabledProvider(SerializablePredicate<T> itemEnabledProvider) {
        this.itemEnabledProvider = Objects.requireNonNull(itemEnabledProvider, "itemEnabledProvider");
        refresh();
    }

    /**
     * Gets which items are enabled.
     *
     * @return the item enabled provider, never {@code null}
     */
    public SerializablePredicate<T> getItemEnabledProvider() {
        return itemEnabledProvider;
    }

    /**
     * Sets which items the user can rename: edit the label of in place, in a
     * text field, on F2 or double-click, or when {@link #editItem} is called.
     * Enter, or focus leaving the field, fires an {@link EditEvent}; Escape
     * cancels. The tree then shows the label the item label generator gives
     * the item, so the app renames the item by changing it in the listener, or
     * rejects the label by leaving it as it is. Disabled items, and any item
     * while the selection is read-only, cannot be edited. By default no item
     * can be edited.
     *
     * @param itemEditableProvider
     *            tells whether the user can edit the label of an item; not
     *            {@code null}
     * @see #addEditListener
     */
    public void setItemEditableProvider(SerializablePredicate<T> itemEditableProvider) {
        this.itemEditableProvider = Objects.requireNonNull(itemEditableProvider, "itemEditableProvider");
        refresh();
    }

    /**
     * Gets which items the user can rename.
     *
     * @return the item editable provider, never {@code null}
     */
    public SerializablePredicate<T> getItemEditableProvider() {
        return itemEditableProvider;
    }

    /**
     * Listens to labels being edited by the user.
     *
     * @param listener
     *            the listener, not {@code null}
     * @return a handle to stop listening
     * @see #setItemEditableProvider
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public Registration addEditListener(ComponentEventListener<EditEvent<T>> listener) {
        return addListener(EditEvent.class, (ComponentEventListener) listener);
    }

    /**
     * Has the user edit the label of an item, as on F2: from a context menu
     * item, for instance. The browser must show the item: call
     * {@link #scrollToItem} first if it may not. Nothing happens if the user
     * cannot edit it.
     *
     * @param item
     *            the item, not {@code null}
     */
    public void editItem(T item) {
        Objects.requireNonNull(item, "item");
        if (keyMapper.has(item)) {
            getElement().callJsFunction("editItem", keyMapper.key(item));
        }
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
     * Sets the texts the tree shows by itself: while loading, when it has no
     * items, and when fetching items fails. They are in English by default.
     * Changes made to the object afterwards take effect when it is set again.
     *
     * @param i18n
     *            the texts, not {@code null}
     */
    public void setI18n(TreeI18n i18n) {
        this.i18n = Objects.requireNonNull(i18n, "i18n");
        getElement().setPropertyBean("i18n", i18n);
    }

    /**
     * Gets the texts the tree shows by itself.
     *
     * @return the texts, never {@code null}
     */
    public TreeI18n getI18n() {
        return i18n;
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

    /**
     * Sets how the user selects items. {@link SelectionMode#SINGLE} is the only
     * mode for now, and the default.
     *
     * @param selectionMode
     *            the selection mode, not {@code null}
     */
    public void setSelectionMode(SelectionMode selectionMode) {
        this.selectionMode = Objects.requireNonNull(selectionMode, "selectionMode");
    }

    /**
     * Gets how the user selects items.
     *
     * @return the selection mode, never {@code null}
     */
    public SelectionMode getSelectionMode() {
        return selectionMode;
    }

    /**
     * Gets the selection of the tree as a single value: the selected item, or
     * {@code null} when there is none. Every item can be selected, parents too.
     * <p>
     * The user selects an item by clicking it, and cannot clear the selection:
     * clicking the selected item again keeps it selected. Setting the value to
     * {@code null} clears it. Setting the value does not expand the ancestors
     * of the item; {@link #scrollToItem} does. Expanding and collapsing items
     * never changes the selection.
     *
     * @return the single selection, never {@code null}
     * @throws IllegalStateException
     *             if the selection mode is not {@link SelectionMode#SINGLE}
     */
    public SingleSelect<Tree<T>, T> asSingleSelect() {
        if (selectionMode != SelectionMode.SINGLE) {
            throw new IllegalStateException("The tree is not in single selection mode: " + selectionMode);
        }
        return singleSelect;
    }

    /**
     * Gets the selection of the tree as a set of items, in a selection mode
     * where several items can be selected. There is no such mode yet.
     *
     * @return never returns
     * @throws IllegalStateException
     *             always, as the selection mode is {@link SelectionMode#SINGLE}
     */
    public MultiSelect<Tree<T>, T> asMultiSelect() {
        throw new IllegalStateException("The tree is not in a multiple selection mode: " + selectionMode);
    }

    /**
     * Adds a context menu for the items of the tree, which tells the item it
     * opens on. Add menu items to it.
     *
     * @return the context menu
     * @see TreeContextMenu
     */
    public TreeContextMenu<T> addContextMenu() {
        return new TreeContextMenu<>(this);
    }

    /**
     * Gets the item the browser knows by a key.
     *
     * @param key
     *            the key
     * @return the item, or {@code null} if the key is no longer known
     */
    T itemForKey(String key) {
        return keyMapper.get(key);
    }

    /**
     * Expands the ancestors of an item and scrolls it into view, fetching the
     * items on the way that the browser has not fetched yet. The selection is
     * left as it is.
     *
     * @param item
     *            the item, not {@code null}
     * @throws UnsupportedOperationException
     *             if the data provider does not implement
     *             {@link HierarchicalDataProvider#getParent(Object) getParent}
     */
    public void scrollToItem(T item) {
        List<T> ancestors = ancestors(Objects.requireNonNull(item, "item"));
        expand(ancestors);
        Serializable[] keys = Stream.concat(ancestors.stream(), Stream.of(item)).map(keyMapper::key)
                .toArray(Serializable[]::new);
        getElement().callJsFunction("scrollToItem", keys);
    }

    /** The ancestors of an item, the root item first. */
    private List<T> ancestors(T item) {
        List<T> ancestors = new ArrayList<>();
        for (T parent = dataProvider.getParent(item); parent != null; parent = dataProvider.getParent(parent)) {
            ancestors.addFirst(parent);
        }
        return ancestors;
    }

    private Object idOf(T item) {
        return item == null ? null : dataProvider.getId(item);
    }

    private void setSelectedItem(T item, boolean fromClient) {
        if (Objects.equals(idOf(item), idOf(selectedItem))) {
            return;
        }
        T oldItem = selectedItem;
        selectedItem = item;
        updateSelectionPath();
        sendSelection();
        fireEvent(new ComponentValueChangeEvent<>(this, singleSelect, oldItem, fromClient));
    }

    /**
     * The selection path is shown only if the data provider can tell the parent
     * of an item, and the selected item is still in the data.
     */
    private void updateSelectionPath() {
        selectionPath = List.of();
        if (selectedItem != null) {
            try {
                selectionPath = ancestors(selectedItem);
            } catch (UnsupportedOperationException e) {
                // Documented: no selection path without getParent
            } catch (IllegalArgumentException e) {
                // The selected item is gone from the data, as TreeData tells; the app clears the value
            }
        }
        selectionPathIds = new HashSet<>(selectionPath.stream().map(dataProvider::getId).toList());
    }

    /**
     * Tells the browser the selected item and its selection path, by the keys of
     * those it has; it learns of the others as it fetches them.
     */
    private void sendSelection() {
        List<Serializable> arguments = new ArrayList<>();
        arguments.add(selectedItem != null && keyMapper.has(selectedItem) ? keyMapper.key(selectedItem) : null);
        selectionPath.stream().filter(keyMapper::has).map(keyMapper::key).forEach(arguments::add);
        getElement().callJsFunction("setSelection", arguments.toArray(Serializable[]::new));
    }

    /**
     * Selects an item, as the user did in the browser.
     *
     * @param key
     *            the key of the item; an item no longer known is ignored,
     *            and a disabled one, or any while read-only, is not selected
     */
    @ClientCallable
    void select(String key) {
        T item = keyMapper.get(key);
        if (item == null) {
            return;
        }
        if (singleSelect.isReadOnly() || !itemEnabledProvider.test(item)) {
            sendSelection();
        } else {
            setSelectedItem(item, true);
        }
    }

    /**
     * Renames an item, as the user did in the browser: fires an
     * {@link EditEvent} if the user can edit the item, then sends the item
     * back, with the label the app gives it, in place of the one the browser
     * shows meanwhile.
     *
     * @param key
     *            the key of the item; an item no longer known is ignored
     * @param label
     *            the label the user typed
     */
    @ClientCallable
    void edit(String key, String label) {
        T item = keyMapper.get(key);
        if (item == null) {
            return;
        }
        if (itemEditableProvider.test(item) && itemEnabledProvider.test(item) && !singleSelect.isReadOnly()) {
            fireEvent(new EditEvent<>(this, true, item, Objects.requireNonNullElse(label, "")));
        }
        // The listener may have refreshed the data, and the item with it
        if (keyMapper.has(item)) {
            getElement().callJsFunction("refreshItem", treeItem(item), false);
        }
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
     * Expands or collapses items, as the user did in the browser: one item
     * with its toggle or the arrow keys, or the siblings of an item with
     * {@code *}. Fires one event for all of them.
     *
     * @param expanded
     *            {@code true} to expand the items, {@code false} to collapse
     *            them
     * @param keys
     *            the keys of the items; items no longer known are ignored
     */
    @ClientCallable
    void setExpanded(boolean expanded, String... keys) {
        List<T> items = Arrays.stream(keys).map(keyMapper::get).filter(Objects::nonNull).toList();
        setExpanded(items, expanded, true);
    }

    /**
     * Fetches a page of the child items of a parent, for the browser.
     * <p>
     * Allowed while the tree is disabled: a disabled tree still shows its items.
     * An exception from the data provider is left to Flow, which rejects the
     * browser's promise and hands the exception to the session's error handler.
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
                .map(this::treeItem)
                .toList();
        return new ItemPage(items, size);
    }

    private TreeItem treeItem(T item) {
        Object id = dataProvider.getId(item);
        return new TreeItem(keyMapper.key(item), itemLabelGenerator.apply(item), itemIconGenerator.apply(item),
                itemTooltipGenerator.apply(item), dataProvider.hasChildren(item), isExpanded(item),
                Objects.equals(id, idOf(selectedItem)), selectionPathIds.contains(id), !itemEnabledProvider.test(item),
                itemEditableProvider.test(item));
    }

    /**
     * Forgets the items sent so far, and has the browser fetch them again.
     * Expanded items stay expanded, and the selected item selected.
     */
    private void refresh() {
        keyMapper.removeAll();
        updateSelectionPath();
        if (isAttached()) {
            getElement().callJsFunction("refresh");
        }
    }

    private void listenToDataProvider() {
        if (dataProviderListener != null) {
            dataProviderListener.remove();
        }
        dataProviderListener = dataProvider.addDataProviderListener(this::onDataChange);
    }

    private void onDataChange(DataChangeEvent<T> event) {
        if (event instanceof DataRefreshEvent<T> refreshEvent) {
            refreshItem(refreshEvent.getItem(), refreshEvent.isRefreshChildren());
        } else {
            refresh();
        }
    }

    /**
     * Sends the browser an item again, and has it fetch the item's child items
     * again if asked to. An item the browser has not fetched comes up to date
     * when it does.
     */
    private void refreshItem(T item, boolean refreshChildren) {
        if (!keyMapper.has(item)) {
            return;
        }
        keyMapper.refresh(item);
        getElement().callJsFunction("refreshItem", treeItem(item), refreshChildren);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        listenToDataProvider();
    }

    /** The selection of the tree as a value, see {@link Tree#asSingleSelect()}. */
    private class TreeSingleSelect implements SingleSelect<Tree<T>, T> {

        @Override
        public void setValue(T value) {
            setSelectedItem(value, false);
        }

        @Override
        public T getValue() {
            return selectedItem;
        }

        @Override
        @SuppressWarnings({ "unchecked", "rawtypes" })
        public Registration addValueChangeListener(
                ValueChangeListener<? super ComponentValueChangeEvent<Tree<T>, T>> listener) {
            Objects.requireNonNull(listener, "listener");
            return addListener(ComponentValueChangeEvent.class,
                    (ComponentEventListener) event -> listener.valueChanged((ComponentValueChangeEvent<Tree<T>, T>) event));
        }

        @Override
        public Element getElement() {
            return Tree.this.getElement();
        }
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
