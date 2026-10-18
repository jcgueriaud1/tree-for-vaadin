package org.vaadin.addons.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vaadin.browserless.BrowserlessTest;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.data.provider.hierarchy.TreeData;
import com.vaadin.flow.data.provider.hierarchy.TreeDataProvider;
import org.vaadin.addons.tree.Tree.ItemPage;
import org.vaadin.addons.tree.Tree.TreeItem;

/** The server side of the Tree: what the browser gets when it fetches the root items. */
class TreeTest extends BrowserlessTest {

    record Folder(String name) {
    }

    private final Folder docs = new Folder("Documents");
    private final Folder music = new Folder("Music");
    private final Folder letters = new Folder("Letters");

    private Tree<Folder> tree;

    @BeforeEach
    void addTree() {
        TreeData<Folder> data = new TreeData<>();
        data.addRootItems(docs, music);
        data.addItem(docs, letters);
        tree = new Tree<>();
        tree.setItems(data);
        tree.setItemLabelGenerator(Folder::name);
        UI.getCurrent().add(tree);
    }

    @Test
    void theRootPageHasTheRootItemsLabelledByTheGenerator() {
        ItemPage page = tree.fetchChildren(null, 0, 50);

        assertEquals(List.of("Documents", "Music"), labels(page));
        assertEquals(2, page.size());
    }

    @Test
    void eachRootItemHasItsOwnKey() {
        List<TreeItem> items = tree.fetchChildren(null, 0, 50).items();

        assertNotEquals(items.get(0).key(), items.get(1).key());
        assertEquals(items.get(0).key(), tree.fetchChildren(null, 0, 50).items().get(0).key(),
                "the same item keeps its key");
    }

    @Test
    void theRootItemsComeInPages() {
        TreeData<Folder> data = new TreeData<>();
        IntStream.range(0, 120).forEach(i -> data.addItem(null, new Folder("Folder " + i)));
        tree.setItems(data);

        ItemPage page = tree.fetchChildren(null, 50, 50);

        assertEquals(120, page.size());
        assertEquals(50, page.items().size());
        assertEquals("Folder 50", page.items().get(0).label());
    }

    @Test
    void aPageIsCappedAtTheMaximumPageSize() {
        TreeData<Folder> data = new TreeData<>();
        IntStream.range(0, Tree.MAX_PAGE_SIZE + 10).forEach(i -> data.addItem(null, new Folder("Folder " + i)));
        tree.setItems(data);

        assertEquals(Tree.MAX_PAGE_SIZE, tree.fetchChildren(null, 0, Integer.MAX_VALUE).items().size());
    }

    @Test
    void aNegativeOffsetOrLimitIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> tree.fetchChildren(null, -1, 50));
        assertThrows(IllegalArgumentException.class, () -> tree.fetchChildren(null, 0, -1));
    }

    @Test
    void withoutALabelGeneratorItemsAreLabelledWithToString() {
        Tree<String> plain = new Tree<>();
        TreeData<String> data = new TreeData<>();
        data.addRootItems("Alpha", "Beta");
        plain.setDataProvider(new TreeDataProvider<>(data));

        assertEquals(List.of("Alpha", "Beta"), labels(plain.fetchChildren(null, 0, 50)));
    }

    @Test
    void aTreeWithoutItemsHasAnEmptyRootPage() {
        ItemPage page = new Tree<String>().fetchChildren(null, 0, 50);

        assertEquals(0, page.size());
        assertTrue(page.items().isEmpty());
    }

    @Test
    void theTreeIsLabelledForAssistiveTechnology() {
        tree.setAriaLabel("Folders");

        assertEquals("Folders", tree.getElement().getAttribute("aria-label"));
        assertEquals("Folders", tree.getAriaLabel().orElseThrow());
    }

    @Test
    void theTreeIsACustomElementSizedAndStyledLikeOtherComponents() {
        tree.setWidth("300px");
        tree.addClassName("files");

        assertEquals("tfv-tree", tree.getElement().getTag());
        assertEquals("300px", tree.getWidth());
        assertTrue(tree.hasClassName("files"));
    }

    private static List<String> labels(ItemPage page) {
        return page.items().stream().map(TreeItem::label).toList();
    }
}
