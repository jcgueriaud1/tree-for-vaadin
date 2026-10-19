package org.vaadin.addons.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vaadin.browserless.BrowserlessTest;
import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.internal.PendingJavaScriptInvocation;
import com.vaadin.flow.data.provider.hierarchy.TreeData;
import com.vaadin.flow.data.provider.hierarchy.TreeDataProvider;
import org.vaadin.addons.tree.Tree.ItemPage;
import org.vaadin.addons.tree.Tree.TreeItem;

/** The server side of the Tree: what the browser gets when it fetches items, and expanding and collapsing them. */
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

    @Test
    void anItemWithChildItemsIsExpandable() {
        List<TreeItem> roots = tree.fetchChildren(null, 0, 50).items();

        assertTrue(roots.get(0).expandable(), "Documents has Letters");
        assertFalse(roots.get(1).expandable(), "Music has no child items");
    }

    @Test
    void theChildItemsOfAnItemAreFetchedByItsKey() {
        String documentsKey = tree.fetchChildren(null, 0, 50).items().get(0).key();

        ItemPage page = tree.fetchChildren(documentsKey, 0, 50);

        assertEquals(List.of("Letters"), labels(page));
        assertEquals(1, page.size());
    }

    @Test
    void theChildItemsOfAnItemNoLongerKnownAreAnEmptyPage() {
        ItemPage page = tree.fetchChildren("unknown", 0, 50);

        assertEquals(0, page.size());
        assertTrue(page.items().isEmpty());
    }

    @Test
    void itemsAreCollapsedUntilExpanded() {
        assertFalse(tree.isExpanded(docs));

        tree.expand(docs);
        assertTrue(tree.isExpanded(docs));

        tree.collapse(docs);
        assertFalse(tree.isExpanded(docs));
    }

    @Test
    void expandingAndCollapsingFireEventsFromTheServer() {
        List<ComponentEvent<?>> events = recordEvents();

        tree.expand(docs, music);
        tree.collapse(docs);

        assertEquals(2, events.size());
        ExpandEvent<?> expand = (ExpandEvent<?>) events.get(0);
        assertEquals(List.of(docs, music), expand.getItems());
        assertFalse(expand.isFromClient());
        CollapseEvent<?> collapse = (CollapseEvent<?>) events.get(1);
        assertEquals(List.of(docs), collapse.getItems());
        assertFalse(collapse.isFromClient());
    }

    @Test
    void onlyItemsWhoseStateChangesAreInTheEvent() {
        tree.expand(docs);
        List<ComponentEvent<?>> events = recordEvents();

        tree.expand(docs, music);
        tree.expand(docs);
        tree.collapse(letters);

        assertEquals(1, events.size());
        assertEquals(List.of(music), ((ExpandEvent<?>) events.get(0)).getItems());
    }

    @Test
    void theUserExpandingAndCollapsingFiresEventsFromTheClient() {
        String documentsKey = tree.fetchChildren(null, 0, 50).items().get(0).key();
        List<ComponentEvent<?>> events = recordEvents();

        tree.setExpanded(documentsKey, true);
        assertTrue(tree.isExpanded(docs));
        tree.setExpanded(documentsKey, false);
        assertFalse(tree.isExpanded(docs));

        assertEquals(2, events.size());
        assertEquals(List.of(docs), ((ExpandEvent<?>) events.get(0)).getItems());
        assertTrue(events.get(0).isFromClient());
        assertEquals(List.of(docs), ((CollapseEvent<?>) events.get(1)).getItems());
        assertTrue(events.get(1).isFromClient());
    }

    @Test
    void theUserExpandingAnItemNoLongerKnownIsIgnored() {
        List<ComponentEvent<?>> events = recordEvents();

        tree.setExpanded("unknown", true);

        assertTrue(events.isEmpty());
    }

    @Test
    void anExpandedItemComesExpandedWhenTheBrowserFetchesIt() {
        tree.expand(docs);

        TreeItem documents = tree.fetchChildren(null, 0, 50).items().get(0);

        assertTrue(documents.expanded());
        assertFalse(tree.fetchChildren(null, 0, 50).items().get(1).expanded());
    }

    @Test
    void expandingAnItemTheBrowserHasTellsTheBrowser() {
        String documentsKey = tree.fetchChildren(null, 0, 50).items().get(0).key();
        setItemExpandedCalls();

        tree.expand(docs);
        tree.collapse(docs);

        assertEquals(List.of(List.of(documentsKey, true), List.of(documentsKey, false)), setItemExpandedCalls());
    }

    @Test
    void expandingAnItemTheBrowserHasNotFetchedDoesNotTellTheBrowser() {
        tree.fetchChildren(null, 0, 50);
        setItemExpandedCalls();

        tree.expand(letters);

        assertEquals(List.of(), setItemExpandedCalls());
    }

    @Test
    void expandedItemsStayExpandedWhenTheDataIsRefreshed() {
        tree.expand(docs);

        tree.getDataProvider().refreshAll();

        assertTrue(tree.isExpanded(docs));
        assertTrue(tree.fetchChildren(null, 0, 50).items().get(0).expanded());
    }

    @Test
    void newItemsStartCollapsed() {
        tree.expand(docs);

        TreeData<Folder> data = new TreeData<>();
        data.addRootItems(docs);
        tree.setItems(data);

        assertFalse(tree.isExpanded(docs));
    }

    @Test
    void thePageSizeIsFiftyUnlessSet() {
        assertEquals(50, tree.getPageSize());
        assertEquals(50, tree.getElement().getProperty("pageSize", 0));

        tree.setPageSize(10);

        assertEquals(10, tree.getPageSize());
        assertEquals(10, tree.getElement().getProperty("pageSize", 0));
    }

    @Test
    void thePageSizeIsBetweenOneAndTheMaximum() {
        assertThrows(IllegalArgumentException.class, () -> tree.setPageSize(0));
        assertThrows(IllegalArgumentException.class, () -> tree.setPageSize(Tree.MAX_PAGE_SIZE + 1));
    }

    private List<ComponentEvent<?>> recordEvents() {
        List<ComponentEvent<?>> events = new ArrayList<>();
        tree.addExpandListener(events::add);
        tree.addCollapseListener(events::add);
        return events;
    }

    /** The arguments of the setItemExpanded calls the server has queued for the browser, taking them off the queue. */
    private static List<List<Object>> setItemExpandedCalls() {
        UI.getCurrent().getInternals().getStateTree().runExecutionsBeforeClientResponse();
        return UI.getCurrent().getInternals().dumpPendingJavaScriptInvocations().stream()
                .map(PendingJavaScriptInvocation::getInvocation)
                .filter(invocation -> invocation.getExpression().contains("setItemExpanded"))
                .map(invocation -> invocation.getParameters().subList(1, invocation.getParameters().size()))
                .toList();
    }

    private static List<String> labels(ItemPage page) {
        return page.items().stream().map(TreeItem::label).toList();
    }
}
