package org.vaadin.addons.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vaadin.browserless.BrowserlessTest;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.data.provider.hierarchy.TreeData;
import org.vaadin.addons.tree.Tree.TreeItem;

/** Dragging items and dropping them before, after or into others, which the app turns into moves. */
class DragAndDropTest extends BrowserlessTest {

    record Folder(String name) {
    }

    private final Folder docs = new Folder("Documents");
    private final Folder letters = new Folder("Letters");
    private final Folder music = new Folder("Music");

    private Tree<Folder> tree;
    private final List<DropEvent<Folder>> drops = new ArrayList<>();

    @BeforeEach
    void addTree() {
        TreeData<Folder> data = new TreeData<>();
        data.addRootItems(docs, music);
        data.addItem(docs, letters);
        tree = new Tree<>();
        tree.setItems(data);
        tree.setItemLabelGenerator(Folder::name);
        tree.addDropListener(drops::add);
        UI.getCurrent().add(tree);
    }

    @Test
    void itemsAreNotDraggableUnlessTheProviderSaysSoAndTakeDropsUnlessItSaysNot() {
        TreeItem documents = rootItem("Documents");
        assertFalse(documents.draggable());
        assertTrue(documents.droppable());

        tree.setItemDraggableProvider(folder -> folder.equals(music));
        tree.setItemDroppableProvider(folder -> !folder.equals(music));

        assertFalse(rootItem("Documents").draggable());
        assertTrue(rootItem("Music").draggable());
        assertFalse(rootItem("Music").droppable());
        assertThrows(NullPointerException.class, () -> tree.setItemDraggableProvider(null));
        assertThrows(NullPointerException.class, () -> tree.setItemDroppableProvider(null));
    }

    @Test
    void droppingAnItemFiresADropEventForTheAppToMoveIt() {
        tree.setItemDraggableProvider(folder -> true);
        String musicKey = rootItem("Music").key();
        String documentsKey = rootItem("Documents").key();

        tree.drop(musicKey, documentsKey, "INTO");
        tree.drop(musicKey, documentsKey, "BEFORE");
        tree.drop(documentsKey, musicKey, "AFTER");

        assertEquals(3, drops.size());
        DropEvent<Folder> into = drops.get(0);
        assertEquals(music, into.getDraggedItem());
        assertEquals(docs, into.getTargetItem());
        assertEquals(DropLocation.INTO, into.getLocation());
        assertTrue(into.isFromClient());
        assertEquals(DropLocation.BEFORE, drops.get(1).getLocation());
        assertEquals(DropLocation.AFTER, drops.get(2).getLocation());
    }

    @Test
    void aDropTheUserCannotMakeIsIgnored() {
        tree.setItemDraggableProvider(folder -> !folder.equals(docs));
        tree.setItemDroppableProvider(folder -> !folder.equals(letters));
        String documentsKey = rootItem("Documents").key();
        String musicKey = rootItem("Music").key();
        String lettersKey = tree.fetchChildren(documentsKey, 0, 50).items().get(0).key();

        tree.drop(documentsKey, musicKey, "INTO");     // not draggable
        tree.drop(musicKey, lettersKey, "INTO");       // takes no drops
        tree.drop(musicKey, musicKey, "BEFORE");       // on itself
        tree.drop(musicKey, "unknown", "INTO");
        tree.drop(musicKey, documentsKey, "SIDEWAYS");
        tree.setItemDraggableProvider(folder -> true);
        tree.drop(rootItem("Documents").key(), lettersKey, "AFTER"); // into its own descendant

        assertTrue(drops.isEmpty());
    }

    @Test
    void nothingIsDroppedWhileReadOnlyNorADisabledItemDragged() {
        tree.setItemDraggableProvider(folder -> true);
        tree.setItemEnabledProvider(folder -> !folder.equals(music));
        String musicKey = rootItem("Music").key();
        String documentsKey = rootItem("Documents").key();

        tree.drop(musicKey, documentsKey, "INTO");
        tree.asSingleSelect().setReadOnly(true);
        tree.drop(documentsKey, musicKey, "INTO");

        assertTrue(drops.isEmpty());
    }

    @Test
    void theTextAnnouncedWhenAnItemIsCutCanBeTranslated() {
        assertEquals("{0} cut. Ctrl+V moves it into the focused item, Ctrl+Shift+V before it, Escape cancels.",
                tree.getI18n().getCut());
        assertEquals("{0} leikattu", new TreeI18n().setCut("{0} leikattu").getCut());
        assertThrows(NullPointerException.class, () -> new TreeI18n().setCut(null));
    }

    private TreeItem rootItem(String label) {
        return tree.fetchChildren(null, 0, 50).items().stream().filter(item -> item.label().equals(label))
                .findFirst().orElseThrow();
    }
}
