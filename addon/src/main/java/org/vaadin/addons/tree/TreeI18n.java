package org.vaadin.addons.tree;

import java.io.Serializable;
import java.util.Objects;

/**
 * The texts a {@link Tree} shows by itself, in English unless set otherwise.
 * Set them with {@link Tree#setI18n(TreeI18n)}; changing them afterwards has no
 * effect until they are set again.
 *
 * <pre>{@code
 * tree.setI18n(new TreeI18n().setLoading("Ladataan…").setEmpty("Ei kohteita")
 *         .setLoadError("Kohteiden lataus epäonnistui")
 *         .setSelectionPath("Sisältää valitun kohteen").setEditor("Nimi"));
 * }</pre>
 */
public class TreeI18n implements Serializable {

    private String loading = "Loading…";
    private String empty = "No items";
    private String loadError = "Could not load the items";
    private String selectionPath = "Contains the selected item";
    private String editor = "Label";
    private String cut = "{0} cut. Ctrl+V moves it into the focused item, Ctrl+Shift+V before it, Escape cancels.";

    /**
     * Gets the text shown while items are being fetched.
     *
     * @return the loading text, never {@code null}
     */
    public String getLoading() {
        return loading;
    }

    /**
     * Sets the text shown while items are being fetched. Assistive technology
     * learns of the loading from {@code aria-busy} instead.
     *
     * @param loading
     *            the loading text, not {@code null}
     * @return this object, for chaining
     */
    public TreeI18n setLoading(String loading) {
        this.loading = Objects.requireNonNull(loading, "loading");
        return this;
    }

    /**
     * Gets the text shown when the tree has no items.
     *
     * @return the empty text, never {@code null}
     */
    public String getEmpty() {
        return empty;
    }

    /**
     * Sets the text shown when the tree has no items. It is also the
     * accessible description of the tree while it is empty.
     *
     * @param empty
     *            the empty text, not {@code null}
     * @return this object, for chaining
     */
    public TreeI18n setEmpty(String empty) {
        this.empty = Objects.requireNonNull(empty, "empty");
        return this;
    }

    /**
     * Gets the text shown when fetching items fails.
     *
     * @return the load error text, never {@code null}
     */
    public String getLoadError() {
        return loadError;
    }

    /**
     * Sets the text shown when fetching items fails. It is also announced to
     * assistive technology.
     *
     * @param loadError
     *            the load error text, not {@code null}
     * @return this object, for chaining
     */
    public TreeI18n setLoadError(String loadError) {
        this.loadError = Objects.requireNonNull(loadError, "loadError");
        return this;
    }

    /**
     * Gets the accessible description of each ancestor of the selected item.
     *
     * @return the selection path text, never {@code null}
     */
    public String getSelectionPath() {
        return selectionPath;
    }

    /**
     * Sets the accessible description of each ancestor of the selected item,
     * which tells assistive technology what the selection path marker shows:
     * that the item contains the selected item, collapsed or not. It is not
     * shown.
     *
     * @param selectionPath
     *            the selection path text, not {@code null}
     * @return this object, for chaining
     */
    public TreeI18n setSelectionPath(String selectionPath) {
        this.selectionPath = Objects.requireNonNull(selectionPath, "selectionPath");
        return this;
    }

    /**
     * Gets the accessible name of the text field that edits the label of an
     * item.
     *
     * @return the editor text, never {@code null}
     */
    public String getEditor() {
        return editor;
    }

    /**
     * Sets the accessible name of the text field that edits the label of an
     * item, see {@link Tree#setItemEditableProvider}. It is not shown.
     *
     * @param editor
     *            the editor text, not {@code null}
     * @return this object, for chaining
     */
    public TreeI18n setEditor(String editor) {
        this.editor = Objects.requireNonNull(editor, "editor");
        return this;
    }

    /**
     * Gets the text announced when the user cuts an item with Ctrl+X, to move
     * it with the keyboard; {@code {0}} stands for the item's label.
     *
     * @return the cut text, never {@code null}
     */
    public String getCut() {
        return cut;
    }

    /**
     * Sets the text announced to assistive technology when the user cuts an
     * item with Ctrl+X, to move it with the keyboard, see
     * {@link Tree#setItemDraggableProvider}; {@code {0}} stands for the item's
     * label. It is not shown.
     *
     * @param cut
     *            the cut text, not {@code null}
     * @return this object, for chaining
     */
    public TreeI18n setCut(String cut) {
        this.cut = Objects.requireNonNull(cut, "cut");
        return this;
    }
}
