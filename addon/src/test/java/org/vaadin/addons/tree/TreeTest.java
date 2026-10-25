package org.vaadin.addons.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vaadin.browserless.BrowserlessTest;
import com.vaadin.flow.component.AbstractField.ComponentValueChangeEvent;
import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.internal.PendingJavaScriptInvocation;
import com.vaadin.flow.data.provider.hierarchy.AbstractBackEndHierarchicalDataProvider;
import com.vaadin.flow.data.provider.hierarchy.HierarchicalQuery;
import com.vaadin.flow.data.provider.hierarchy.TreeData;
import com.vaadin.flow.data.provider.hierarchy.TreeDataProvider;
import org.vaadin.addons.tree.Tree.ItemPage;
import org.vaadin.addons.tree.Tree.TreeItem;

/** The server side of the Tree: what the browser gets when it fetches items, expanding and collapsing, and selection. */
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
    void itemsHaveNoIconOrTooltipUnlessGenerated() {
        TreeItem documents = tree.fetchChildren(null, 0, 50).items().get(0);

        assertNull(documents.icon());
        assertNull(documents.tooltip());
    }

    @Test
    void theIconAndTooltipOfAnItemComeFromTheGenerators() {
        tree.setItemIconGenerator(folder -> folder.equals(docs) ? "vaadin:folder" : null);
        tree.setItemTooltipGenerator(folder -> folder.equals(music) ? null : folder.name() + " folder");

        List<TreeItem> items = tree.fetchChildren(null, 0, 50).items();

        assertEquals("vaadin:folder", items.get(0).icon());
        assertEquals("Documents folder", items.get(0).tooltip());
        assertNull(items.get(1).icon(), "null means no icon");
        assertNull(items.get(1).tooltip(), "null means no tooltip");
        assertEquals("Documents", items.get(0).label(), "the label is left as it is");
    }

    @Test
    void settingAGeneratorHasTheBrowserFetchTheItemsAgain() {
        tree.fetchChildren(null, 0, 50);
        calls("refresh");

        tree.setItemIconGenerator(folder -> "vaadin:folder");
        assertEquals(1, calls("refresh").size());
        tree.setItemTooltipGenerator(Folder::name);
        assertEquals(1, calls("refresh").size());
    }

    @Test
    void theTextsAreInEnglishUnlessSet() {
        TreeI18n i18n = tree.getI18n();

        assertEquals("Loading…", i18n.getLoading());
        assertEquals("No items", i18n.getEmpty());
        assertEquals("Could not load the items", i18n.getLoadError());
        assertEquals("Contains the selected item", i18n.getSelectionPath());
        assertEquals("Label", i18n.getEditor());
    }

    @Test
    void theTextsSetAreSentToTheBrowser() {
        TreeI18n i18n = new TreeI18n().setLoading("Ladataan…").setEmpty("Ei kohteita")
                .setLoadError("Lataus epäonnistui").setSelectionPath("Sisältää valitun kohteen").setEditor("Nimi");

        tree.setI18n(i18n);

        assertEquals(i18n, tree.getI18n());
        JsonNode sent = (JsonNode) tree.getElement().getPropertyRaw("i18n");
        assertEquals("Ladataan…", sent.get("loading").asString());
        assertEquals("Ei kohteita", sent.get("empty").asString());
        assertEquals("Lataus epäonnistui", sent.get("loadError").asString());
        assertEquals("Sisältää valitun kohteen", sent.get("selectionPath").asString());
        assertEquals("Nimi", sent.get("editor").asString());
    }

    @Test
    void theTextsAreNotNull() {
        assertThrows(NullPointerException.class, () -> tree.setI18n(null));
        assertThrows(NullPointerException.class, () -> new TreeI18n().setLoading(null));
        assertThrows(NullPointerException.class, () -> new TreeI18n().setEmpty(null));
        assertThrows(NullPointerException.class, () -> new TreeI18n().setLoadError(null));
        assertThrows(NullPointerException.class, () -> new TreeI18n().setSelectionPath(null));
        assertThrows(NullPointerException.class, () -> new TreeI18n().setEditor(null));
    }

    @Test
    void theGeneratorsAreNotNull() {
        assertThrows(NullPointerException.class, () -> tree.setItemIconGenerator(null));
        assertThrows(NullPointerException.class, () -> tree.setItemTooltipGenerator(null));
        assertThrows(NullPointerException.class, () -> tree.setItemEnabledProvider(null));
        assertThrows(NullPointerException.class, () -> tree.setItemEditableProvider(null));
    }

    @Test
    void itemsAreNotEditableUnlessTheProviderSaysSo() {
        assertEquals(List.of(false, false), editable(tree.fetchChildren(null, 0, 50)));

        tree.setItemEditableProvider(folder -> folder.equals(music));

        assertEquals(List.of(false, true), editable(tree.fetchChildren(null, 0, 50)));
        assertEquals(1, calls("refresh").size(), "the browser fetches the items again");
    }

    @Test
    void theUserEditingALabelFiresAnEditEventAndGetsTheItemBackAsTheAppLabelsIt() {
        tree.setItemEditableProvider(folder -> true);
        String musicKey = key(music);
        List<EditEvent<Folder>> events = new ArrayList<>();
        tree.addEditListener(events::add);
        calls("refreshItem");

        tree.edit(musicKey, "Songs");

        assertEquals(1, events.size());
        assertEquals(music, events.get(0).getItem());
        assertEquals("Songs", events.get(0).getLabel());
        assertTrue(events.get(0).isFromClient());
        List<List<Object>> sentBack = calls("refreshItem");
        assertEquals(1, sentBack.size());
        TreeItem item = (TreeItem) sentBack.get(0).get(0);
        assertEquals(musicKey, item.key());
        assertEquals("Music", item.label(), "the label the app gives the item, which it has not renamed");
        assertEquals(false, sentBack.get(0).get(1), "its child items are left as they are");
    }

    @Test
    void anEditTheUserCannotMakeIsIgnoredAndTheItemSentBack() {
        tree.setItemEditableProvider(folder -> folder.equals(docs));
        tree.setItemEnabledProvider(folder -> !folder.equals(docs));
        String docsKey = key(docs);
        String musicKey = key(music);
        List<EditEvent<Folder>> events = new ArrayList<>();
        tree.addEditListener(events::add);
        calls("refreshItem");

        tree.edit(musicKey, "Songs");
        tree.edit(docsKey, "Papers");
        tree.setItemEnabledProvider(folder -> true);
        docsKey = key(docs);
        tree.asSingleSelect().setReadOnly(true);
        tree.edit(docsKey, "Papers");
        tree.edit("unknown", "Nothing");

        assertTrue(events.isEmpty());
        assertEquals(3, calls("refreshItem").size(), "not editable, disabled, read-only; the unknown one is gone");
    }

    @Test
    void theServerEditsAnItemTheBrowserShows() {
        tree.editItem(music);
        assertTrue(calls("editItem").isEmpty(), "not fetched yet");

        String musicKey = key(music);
        tree.editItem(music);

        assertEquals(List.of(List.of(musicKey)), calls("editItem"));
    }

    @Test
    void itemsAreEnabledUnlessTheProviderSaysOtherwise() {
        assertEquals(List.of(false, false), disabled(tree.fetchChildren(null, 0, 50)));

        tree.setItemEnabledProvider(folder -> !folder.equals(music));

        assertEquals(List.of(false, true), disabled(tree.fetchChildren(null, 0, 50)));
    }

    @Test
    void settingTheEnabledProviderHasTheBrowserFetchTheItemsAgain() {
        tree.fetchChildren(null, 0, 50);
        calls("refresh");

        tree.setItemEnabledProvider(folder -> true);

        assertEquals(1, calls("refresh").size());
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

        tree.setExpanded(true, documentsKey);
        assertTrue(tree.isExpanded(docs));
        tree.setExpanded(false, documentsKey);
        assertFalse(tree.isExpanded(docs));

        assertEquals(2, events.size());
        assertEquals(List.of(docs), ((ExpandEvent<?>) events.get(0)).getItems());
        assertTrue(events.get(0).isFromClient());
        assertEquals(List.of(docs), ((CollapseEvent<?>) events.get(1)).getItems());
        assertTrue(events.get(1).isFromClient());
    }

    @Test
    void theUserExpandingSiblingsFiresOneEventForThemAll() {
        List<String> keys = tree.fetchChildren(null, 0, 50).items().stream().map(TreeItem::key).toList();
        List<ComponentEvent<?>> events = recordEvents();

        tree.setExpanded(true, keys.get(0), "unknown", keys.get(1));

        assertEquals(1, events.size());
        assertEquals(List.of(docs, music), ((ExpandEvent<?>) events.get(0)).getItems());
        assertTrue(events.get(0).isFromClient());
    }

    @Test
    void expandingRecursivelyExpandsTheItemsAndTheirDescendantsWithChildItemsToTheDepth() {
        Folder y2026 = new Folder("2026");
        TreeData<Folder> data = deepData(y2026);
        tree.setItems(data);
        List<ComponentEvent<?>> events = recordEvents();

        tree.expandRecursively(List.of(docs, music), 0);
        assertEquals(List.of(docs), ((ExpandEvent<?>) events.get(0)).getItems(), "Music has no child items");

        tree.expandRecursively(List.of(docs), 2);
        assertEquals(List.of(letters), ((ExpandEvent<?>) events.get(1)).getItems(), "2026 has no child items");
        assertFalse(events.get(1).isFromClient());
        assertEquals(2, events.size());
        assertTrue(tree.isExpanded(letters));
    }

    @Test
    void collapsingRecursivelyCollapsesTheItemsAndTheirDescendantsToTheDepth() {
        tree.setItems(deepData(new Folder("2026")));
        tree.expand(docs, letters);
        List<ComponentEvent<?>> events = recordEvents();

        tree.collapseRecursively(List.of(docs), 0);
        assertTrue(tree.isExpanded(letters), "below the depth");

        tree.collapseRecursively(List.of(docs), 1);

        assertFalse(tree.isExpanded(docs));
        assertFalse(tree.isExpanded(letters));
        assertEquals(List.of(docs), ((CollapseEvent<?>) events.get(0)).getItems());
        assertEquals(List.of(letters), ((CollapseEvent<?>) events.get(1)).getItems());
    }

    @Test
    void expandingRecursivelyFollowsTheFilterOfTheDataProvider() {
        Folder y2026 = new Folder("2026");
        TreeDataProvider<Folder> provider = new TreeDataProvider<>(deepData(y2026));
        tree.setDataProvider(provider);
        provider.setFilter(folder -> folder.equals(music));
        List<ComponentEvent<?>> events = recordEvents();

        tree.expandRecursively(provider.getTreeData().getRootItems(), Integer.MAX_VALUE);

        assertTrue(events.isEmpty(), "nothing under Documents matches, and Music has no child items");
        assertThrows(IllegalArgumentException.class, () -> tree.expandRecursively(List.of(docs), -1));
    }

    @Test
    void anItemWhoseChildItemsAreAllFilteredOutHasNoToggle() {
        TreeDataProvider<Folder> provider = new TreeDataProvider<>(deepData(new Folder("2026")));
        tree.setDataProvider(provider);

        provider.setFilter(folder -> !folder.name().equals("2026"));

        TreeItem documents = tree.fetchChildren(null, 0, 50).items().get(0);
        assertTrue(documents.expandable());
        TreeItem lettersItem = tree.fetchChildren(documents.key(), 0, 50).items().get(0);
        assertEquals("Letters", lettersItem.label());
        assertFalse(lettersItem.expandable(), "its only child item is filtered out");
    }

    @Test
    void theUserExpandingAnItemNoLongerKnownIsIgnored() {
        List<ComponentEvent<?>> events = recordEvents();

        tree.setExpanded(true, "unknown");

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
    void itemsStillInTheDataStayExpandedWhenOthersAreRemoved() {
        TreeData<Folder> data = new TreeData<>();
        data.addRootItems(docs, music);
        data.addItem(docs, letters);
        data.addItem(music, new Folder("Jazz"));
        tree.setItems(data);
        tree.expand(docs, music);

        data.removeItem(music);
        tree.getDataProvider().refreshAll();

        List<TreeItem> roots = tree.fetchChildren(null, 0, 50).items();
        assertEquals(List.of("Documents"), roots.stream().map(TreeItem::label).toList());
        assertTrue(roots.get(0).expanded());
    }

    @Test
    void refreshingAllHasTheBrowserFetchTheItemsAgain() {
        tree.fetchChildren(null, 0, 50);
        calls("refresh");

        tree.getDataProvider().refreshAll();

        assertEquals(1, calls("refresh").size());
    }

    @Test
    void refreshingAnItemSendsItToTheBrowserAgain() {
        TreeData<Folder> data = new TreeData<>();
        data.addRootItems(docs);
        tree.setItems(data);
        tree.setItemTooltipGenerator(Folder::name);
        String documentsKey = key(docs);
        calls("refresh");

        data.addItem(docs, letters);
        tree.getDataProvider().refreshItem(docs, true);
        tree.getDataProvider().refreshItem(docs);

        // Both refresh and refreshItem: no refresh of all the items, which has no arguments
        List<List<Object>> calls = calls("refresh");
        assertEquals(2, calls.size());
        TreeItem documents = (TreeItem) calls.get(0).get(0);
        assertEquals(documentsKey, documents.key());
        assertEquals("Documents", documents.tooltip());
        assertTrue(documents.expandable(), "it has a child item now");
        assertEquals(true, calls.get(0).get(1), "its child items are fetched again");
        assertEquals(false, calls.get(1).get(1));
    }

    @Test
    void refreshingAnItemTheBrowserHasNotFetchedTellsTheBrowserNothing() {
        tree.fetchChildren(null, 0, 50);
        calls("refresh");

        tree.getDataProvider().refreshItem(letters, true);

        assertEquals(List.of(), calls("refresh"));
    }

    @Test
    void anExceptionFetchingChildItemsIsLeftToFlowsErrorHandling() {
        Tree<Folder> failing = new Tree<>();
        failing.setDataProvider(new BackendWithoutGetParent() {
            @Override
            protected Stream<Folder> fetchChildrenFromBackEnd(HierarchicalQuery<Folder, Void> query) {
                throw new IllegalStateException("The backend is down");
            }
        });

        assertThrows(IllegalStateException.class, () -> failing.fetchChildren(null, 0, 50));
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

    @Test
    void theTreeSelectsASingleItemAndNothingAtFirst() {
        assertEquals(Tree.SelectionMode.SINGLE, tree.getSelectionMode());
        assertNull(tree.asSingleSelect().getValue());
        assertTrue(tree.asSingleSelect().isEmpty());
    }

    @Test
    void thereIsNoMultiSelectInSingleSelectionMode() {
        tree.setSelectionMode(Tree.SelectionMode.SINGLE);

        assertThrows(IllegalStateException.class, tree::asMultiSelect);
    }

    @Test
    void anyItemCanBeSelectedFromTheServerParentsToo() {
        List<ComponentValueChangeEvent<Tree<Folder>, Folder>> events = recordValueChanges();

        tree.asSingleSelect().setValue(letters);
        tree.asSingleSelect().setValue(docs);

        assertEquals(docs, tree.asSingleSelect().getValue());
        assertEquals(2, events.size());
        assertNull(events.get(0).getOldValue());
        assertEquals(letters, events.get(0).getValue());
        assertEquals(letters, events.get(1).getOldValue());
        assertEquals(docs, events.get(1).getValue());
        assertFalse(events.get(1).isFromClient());
        assertEquals(tree, events.get(1).getSource());
        assertEquals(tree.asSingleSelect(), events.get(1).getHasValue());
    }

    @Test
    void theServerClearsTheSelectionWithNull() {
        tree.asSingleSelect().setValue(docs);
        List<ComponentValueChangeEvent<Tree<Folder>, Folder>> events = recordValueChanges();

        tree.asSingleSelect().setValue(null);

        assertNull(tree.asSingleSelect().getValue());
        assertEquals(1, events.size());
        assertEquals(docs, events.get(0).getOldValue());
    }

    @Test
    void selectingTheSelectedItemAgainChangesNothing() {
        tree.asSingleSelect().setValue(docs);
        List<ComponentValueChangeEvent<Tree<Folder>, Folder>> events = recordValueChanges();

        tree.asSingleSelect().setValue(docs);
        tree.select(key(docs));

        assertTrue(events.isEmpty());
    }

    @Test
    void theUserSelectingAnItemFiresAValueChangeFromTheClient() {
        List<ComponentValueChangeEvent<Tree<Folder>, Folder>> events = recordValueChanges();

        tree.select(key(music));

        assertEquals(music, tree.asSingleSelect().getValue());
        assertEquals(1, events.size());
        assertTrue(events.get(0).isFromClient());
    }

    @Test
    void theUserSelectingAnItemNoLongerKnownIsIgnored() {
        tree.asSingleSelect().setValue(docs);

        tree.select("unknown");

        assertEquals(docs, tree.asSingleSelect().getValue());
    }

    @Test
    void theUserCannotSelectWhileReadOnly() {
        String musicKey = key(music);
        tree.asSingleSelect().setReadOnly(true);
        setSelectionCalls();

        tree.select(musicKey);

        assertNull(tree.asSingleSelect().getValue());
        assertEquals(List.of(Arrays.asList((Object) null)), setSelectionCalls(), "the browser is told to undo it");
    }

    @Test
    void theUserCannotSelectADisabledItem() {
        tree.setItemEnabledProvider(folder -> !folder.equals(music));
        String musicKey = key(music);
        setSelectionCalls();

        tree.select(musicKey);

        assertNull(tree.asSingleSelect().getValue());
        assertEquals(List.of(Arrays.asList((Object) null)), setSelectionCalls(), "the browser is told to undo it");
    }

    @Test
    void theServerCanSelectADisabledItem() {
        tree.setItemEnabledProvider(folder -> !folder.equals(music));

        tree.asSingleSelect().setValue(music);

        assertEquals(music, tree.asSingleSelect().getValue());
        assertTrue(tree.fetchChildren(null, 0, 50).items().get(1).selected());
    }

    @Test
    void selectingFromTheServerExpandsNothing() {
        tree.asSingleSelect().setValue(letters);

        assertFalse(tree.isExpanded(docs));
    }

    @Test
    void theSelectedItemAndItsSelectionPathComeMarkedWhenTheBrowserFetchesThem() {
        tree.asSingleSelect().setValue(letters);

        TreeItem documents = tree.fetchChildren(null, 0, 50).items().get(0);
        TreeItem lettersItem = tree.fetchChildren(documents.key(), 0, 50).items().get(0);

        assertTrue(documents.onSelectionPath());
        assertFalse(documents.selected());
        assertTrue(lettersItem.selected());
        assertFalse(lettersItem.onSelectionPath());
        assertFalse(tree.fetchChildren(null, 0, 50).items().get(1).onSelectionPath(), "Music is not an ancestor");
    }

    @Test
    void selectingTellsTheBrowserTheKeysOfTheItemsItHas() {
        String documentsKey = key(docs);
        setSelectionCalls();

        tree.asSingleSelect().setValue(letters);
        String lettersKey = tree.fetchChildren(documentsKey, 0, 50).items().get(0).key();
        tree.asSingleSelect().setValue(null);
        tree.select(lettersKey);

        assertEquals(List.of(Arrays.asList(null, documentsKey), Arrays.asList((Object) null),
                List.of(lettersKey, documentsKey)), setSelectionCalls());
    }

    @Test
    void collapsingAndExpandingKeepTheSelection() {
        tree.expand(docs);
        tree.asSingleSelect().setValue(letters);

        tree.collapse(docs);

        assertEquals(letters, tree.asSingleSelect().getValue());
        assertTrue(tree.fetchChildren(null, 0, 50).items().get(0).onSelectionPath());
    }

    @Test
    void theSelectionStaysWhenTheDataIsRefreshedAndGoesWithNewItems() {
        tree.asSingleSelect().setValue(letters);

        tree.getDataProvider().refreshAll();
        assertEquals(letters, tree.asSingleSelect().getValue());

        List<ComponentValueChangeEvent<Tree<Folder>, Folder>> events = recordValueChanges();
        tree.setItems(new TreeData<>());
        assertNull(tree.asSingleSelect().getValue());
        assertEquals(1, events.size());
        assertFalse(events.get(0).isFromClient());
    }

    @Test
    void theValueIsKeptWhenItsItemIsRemovedFromTheData() {
        TreeData<Folder> data = new TreeData<>();
        data.addRootItems(docs);
        data.addItem(docs, letters);
        tree.setItems(data);
        tree.asSingleSelect().setValue(letters);
        List<ComponentValueChangeEvent<Tree<Folder>, Folder>> events = recordValueChanges();

        data.removeItem(letters);
        tree.getDataProvider().refreshAll();

        assertEquals(letters, tree.asSingleSelect().getValue(), "the app clears it");
        assertEquals(List.of(), events);
        assertFalse(tree.fetchChildren(null, 0, 50).items().get(0).onSelectionPath());
    }

    @Test
    void withoutGetParentThereIsNoSelectionPath() {
        Tree<Folder> backend = new Tree<>();
        backend.setDataProvider(new BackendWithoutGetParent());

        backend.asSingleSelect().setValue(letters);

        TreeItem documents = backend.fetchChildren(null, 0, 50).items().get(0);
        assertFalse(documents.onSelectionPath());
        assertTrue(backend.fetchChildren(documents.key(), 0, 50).items().get(0).selected());
    }

    @Test
    void scrollingToAnItemExpandsItsAncestorsAndTellsTheBrowserTheirKeys() {
        Folder year = new Folder("2026");
        TreeData<Folder> data = new TreeData<>();
        data.addRootItems(docs);
        data.addItem(docs, letters);
        data.addItem(letters, year);
        tree.setItems(data);
        List<ComponentEvent<?>> events = recordEvents();
        scrollToItemCalls();

        tree.scrollToItem(year);

        assertTrue(tree.isExpanded(docs));
        assertTrue(tree.isExpanded(letters));
        assertEquals(List.of(docs, letters), ((ExpandEvent<?>) events.get(0)).getItems());
        assertNull(tree.asSingleSelect().getValue(), "scrolling selects nothing");
        String documentsKey = key(docs);
        String lettersKey = tree.fetchChildren(documentsKey, 0, 50).items().get(0).key();
        String yearKey = tree.fetchChildren(lettersKey, 0, 50).items().get(0).key();
        assertEquals(List.of(List.of(documentsKey, lettersKey, yearKey)), scrollToItemCalls());
    }

    @Test
    void scrollingToAnItemNeedsGetParent() {
        Tree<Folder> backend = new Tree<>();
        backend.setDataProvider(new BackendWithoutGetParent());

        assertThrows(UnsupportedOperationException.class, () -> backend.scrollToItem(letters));
    }

    /** Documents holding Letters, from a backend that cannot tell the parent of an item. */
    private class BackendWithoutGetParent extends AbstractBackEndHierarchicalDataProvider<Folder, Void> {

        @Override
        public int getChildCount(HierarchicalQuery<Folder, Void> query) {
            return (int) children(query.getParent()).count();
        }

        @Override
        public boolean hasChildren(Folder item) {
            return item.equals(docs);
        }

        @Override
        protected Stream<Folder> fetchChildrenFromBackEnd(HierarchicalQuery<Folder, Void> query) {
            return children(query.getParent()).skip(query.getOffset()).limit(query.getLimit());
        }

        private Stream<Folder> children(Folder parent) {
            return parent == null ? Stream.of(docs) : parent.equals(docs) ? Stream.of(letters) : Stream.empty();
        }
    }

    /** The key of a root item, fetching the root items for it. */
    private String key(Folder rootItem) {
        return tree.fetchChildren(null, 0, 50).items().stream()
                .filter(item -> item.label().equals(rootItem.name())).findFirst().orElseThrow().key();
    }

    /** Documents holding Letters holding the given folder, and Music. */
    private TreeData<Folder> deepData(Folder inLetters) {
        TreeData<Folder> data = new TreeData<>();
        data.addRootItems(docs, music);
        data.addItem(docs, letters);
        data.addItem(letters, inLetters);
        return data;
    }

    private static List<Boolean> editable(ItemPage page) {
        return page.items().stream().map(TreeItem::editable).toList();
    }

    private static List<Boolean> disabled(ItemPage page) {
        return page.items().stream().map(TreeItem::disabled).toList();
    }

    private List<ComponentValueChangeEvent<Tree<Folder>, Folder>> recordValueChanges() {
        List<ComponentValueChangeEvent<Tree<Folder>, Folder>> events = new ArrayList<>();
        tree.asSingleSelect().addValueChangeListener(events::add);
        return events;
    }

    private List<ComponentEvent<?>> recordEvents() {
        List<ComponentEvent<?>> events = new ArrayList<>();
        tree.addExpandListener(events::add);
        tree.addCollapseListener(events::add);
        return events;
    }

    private static List<List<Object>> setItemExpandedCalls() {
        return calls("setItemExpanded");
    }

    private static List<List<Object>> setSelectionCalls() {
        return calls("setSelection");
    }

    private static List<List<Object>> scrollToItemCalls() {
        return calls("scrollToItem");
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

    private static List<String> labels(ItemPage page) {
        return page.items().stream().map(TreeItem::label).toList();
    }
}
