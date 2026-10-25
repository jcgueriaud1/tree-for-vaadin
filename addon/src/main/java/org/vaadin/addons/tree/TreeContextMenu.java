package org.vaadin.addons.tree;

import java.util.Optional;

import tools.jackson.databind.node.ObjectNode;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.contextmenu.ContextMenu;
import com.vaadin.flow.function.SerializablePredicate;

/**
 * A context menu for the items of a {@link Tree}. It opens on the item the
 * user right-clicks or long-presses, or on the focused item when the user
 * presses Shift+F10 or the Menu key, and tells which item that is with
 * {@link #getTargetItem()}, in the menu items' click listeners for instance.
 *
 * <pre>{@code
 * TreeContextMenu<Folder> menu = tree.addContextMenu();
 * menu.addItem("Open", event -> menu.getTargetItem().ifPresent(this::open));
 * menu.setDynamicContentHandler(folder -> folder != null); // no menu outside the items
 * }</pre>
 *
 * @param <T>
 *            the type of the tree's items
 */
public class TreeContextMenu<T> extends ContextMenu {

    private SerializablePredicate<T> dynamicContentHandler;
    private T targetItem;

    /** Creates a context menu without a target; set it with {@link #setTarget(Component)}. */
    public TreeContextMenu() {
    }

    /**
     * Creates a context menu for the items of a tree.
     *
     * @param target
     *            the tree, not {@code null}
     */
    public TreeContextMenu(Tree<T> target) {
        super(target);
    }

    /**
     * Sets the tree the menu is for.
     *
     * @param target
     *            the tree, or {@code null} for none
     * @throws IllegalArgumentException
     *             if the target is not a {@link Tree}
     */
    @Override
    public void setTarget(Component target) {
        if (target != null && !(target instanceof Tree)) {
            throw new IllegalArgumentException("The target of a TreeContextMenu must be a Tree, not " + target);
        }
        super.setTarget(target);
    }

    /**
     * Gets the item the menu was last opened on.
     *
     * @return the item, or empty if the menu was opened outside the items, or
     *         has not been opened
     */
    public Optional<T> getTargetItem() {
        return Optional.ofNullable(targetItem);
    }

    /**
     * Sets a handler run before the menu opens, with the item it opens on, or
     * {@code null} outside the items. It can change the menu items for that
     * item, and returns whether to open the menu.
     *
     * @param dynamicContentHandler
     *            the handler, or {@code null} to always open the menu
     */
    public void setDynamicContentHandler(SerializablePredicate<T> dynamicContentHandler) {
        this.dynamicContentHandler = dynamicContentHandler;
    }

    /**
     * Gets the handler run before the menu opens.
     *
     * @return the handler, or {@code null} if there is none
     */
    public SerializablePredicate<T> getDynamicContentHandler() {
        return dynamicContentHandler;
    }

    /** Learns the item the menu opens on from the key the browser sends, see {@code getContextMenuBeforeOpenDetail}. */
    @Override
    @SuppressWarnings("unchecked")
    protected boolean onBeforeOpenMenu(ObjectNode eventDetail) {
        String key = eventDetail.path("key").asString("");
        targetItem = key.isEmpty() ? null : ((Tree<T>) getTarget()).itemForKey(key);
        return dynamicContentHandler == null || dynamicContentHandler.test(targetItem);
    }
}
