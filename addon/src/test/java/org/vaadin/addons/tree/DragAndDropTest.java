package org.vaadin.addons.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vaadin.browserless.BrowserlessTest;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.internal.PendingJavaScriptInvocation;
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
    void theTextsAnnouncedWhenAnItemIsCutCanBeTranslated() {
        assertEquals("{0} cut.", tree.getI18n().getCut());
        assertEquals("Ctrl+V moves it into the focused item, Ctrl+Shift+V before it, Escape cancels.",
                tree.getI18n().getCutHint());
        assertEquals("{0} leikattu", new TreeI18n().setCut("{0} leikattu").getCut());
        assertEquals("Ctrl+V liittää", new TreeI18n().setCutHint("Ctrl+V liittää").getCutHint());
        assertThrows(NullPointerException.class, () -> new TreeI18n().setCut(null));
        assertThrows(NullPointerException.class, () -> new TreeI18n().setCutHint(null));
    }

    @Test
    void theAppCutsAnItemAndPastesItWhereTheUserChoosesFromAMenuSay() {
        tree.setItemDraggableProvider(folder -> true);
        String musicKey = rootItem("Music").key();
        calls("cutItem");

        tree.cutItem(music);
        assertEquals(Optional.of(music), tree.getCutItem());
        assertEquals(List.of(List.of(musicKey)), calls("cutItem"), "the browser shows it cut, and says so");

        assertTrue(tree.pasteInto(docs));
        assertEquals(Optional.empty(), tree.getCutItem());
        assertEquals(1, drops.size());
        assertEquals(music, drops.get(0).getDraggedItem());
        assertEquals(docs, drops.get(0).getTargetItem());
        assertEquals(DropLocation.INTO, drops.get(0).getLocation());
        assertFalse(drops.get(0).isFromClient());
        assertEquals(1, calls("cutItem").size(), "the browser shows it no longer cut");

        tree.cutItem(music);
        assertTrue(tree.pasteBefore(docs));
        tree.cutItem(music);
        assertTrue(tree.pasteAfter(docs));
        assertEquals(List.of(DropLocation.INTO, DropLocation.BEFORE, DropLocation.AFTER),
                drops.stream().map(DropEvent::getLocation).toList());
    }

    @Test
    void pastingWhereTheCutItemCannotGoDropsNothingAndKeepsItCut() {
        tree.setItemDraggableProvider(folder -> true);
        tree.setItemDroppableProvider(folder -> !folder.equals(music));

        assertFalse(tree.pasteInto(docs), "nothing is cut");
        tree.cutItem(docs);
        assertFalse(tree.pasteInto(letters), "into its own descendant");
        assertFalse(tree.pasteBefore(docs), "on itself");
        assertFalse(tree.pasteInto(music), "takes no drops");

        assertTrue(drops.isEmpty());
        assertEquals(Optional.of(docs), tree.getCutItem());
        tree.cancelCut();
        assertEquals(Optional.empty(), tree.getCutItem());
    }

    @Test
    void onlyAnItemTheUserCanMoveIsCut() {
        tree.setItemDraggableProvider(folder -> !folder.equals(docs));
        tree.setItemEnabledProvider(folder -> !folder.equals(letters));

        tree.cutItem(docs);
        tree.cutItem(letters);
        assertEquals(Optional.empty(), tree.getCutItem());

        tree.asSingleSelect().setReadOnly(true);
        tree.cutItem(music);
        assertEquals(Optional.empty(), tree.getCutItem());
    }

    @Test
    void theServerKnowsWhatTheUserCutsWithTheKeyboard() {
        tree.setItemDraggableProvider(folder -> true);
        String musicKey = rootItem("Music").key();
        String documentsKey = rootItem("Documents").key();

        tree.cut(musicKey);
        assertEquals(Optional.of(music), tree.getCutItem());
        assertTrue(tree.pasteInto(docs), "cut with Ctrl+X, pasted from a menu");

        tree.cut(musicKey);
        tree.drop(musicKey, documentsKey, "BEFORE");
        assertEquals(Optional.empty(), tree.getCutItem(), "pasted with Ctrl+V");

        tree.cut(musicKey);
        tree.cut(null);
        assertEquals(Optional.empty(), tree.getCutItem(), "Escape");
    }

    /** The arguments of the calls of a function the server has queued for the browser, taking them off the queue. */
    private static List<List<Object>> calls(String function) {
        UI.getCurrent().getInternals().getStateTree().runExecutionsBeforeClientResponse();
        return UI.getCurrent().getInternals().dumpPendingJavaScriptInvocations().stream()
                .map(PendingJavaScriptInvocation::getInvocation)
                .filter(invocation -> invocation.getExpression().contains(function))
                .map(invocation -> invocation.getParameters().subList(1, invocation.getParameters().size()))
                .toList();
    }

    private TreeItem rootItem(String label) {
        return tree.fetchChildren(null, 0, 50).items().stream().filter(item -> item.label().equals(label))
                .findFirst().orElseThrow();
    }
}
