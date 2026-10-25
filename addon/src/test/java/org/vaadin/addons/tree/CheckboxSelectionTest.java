package org.vaadin.addons.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vaadin.browserless.BrowserlessTest;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.internal.PendingJavaScriptInvocation;
import com.vaadin.flow.data.provider.hierarchy.AbstractBackEndHierarchicalDataProvider;
import com.vaadin.flow.data.provider.hierarchy.HierarchicalQuery;
import com.vaadin.flow.data.provider.hierarchy.TreeData;
import com.vaadin.flow.data.selection.MultiSelectionEvent;
import org.vaadin.addons.tree.Tree.SelectionMode;
import org.vaadin.addons.tree.Tree.TreeItem;

/** Checkbox selection: the value is the top-most checked items, and the state of every item follows from it. */
class CheckboxSelectionTest extends BrowserlessTest {

    record Folder(String name) {
    }

    private final Folder docs = new Folder("Documents");
    private final Folder invoices = new Folder("Invoices");
    private final Folder letters = new Folder("Letters");
    private final Folder y2025 = new Folder("2025");
    private final Folder y2026 = new Folder("2026");
    private final Folder music = new Folder("Music");

    private Tree<Folder> tree;

    @BeforeEach
    void addTree() {
        TreeData<Folder> data = new TreeData<>();
        data.addRootItems(docs, music);
        data.addItems(docs, invoices, letters);
        data.addItems(letters, y2025, y2026);
        tree = new Tree<>();
        tree.setItems(data);
        tree.setItemLabelGenerator(Folder::name);
        tree.setSelectionMode(SelectionMode.CHECKBOX);
        UI.getCurrent().add(tree);
    }

    @Test
    void checkboxModeHasAMultiSelectAndTellsAssistiveTechnology() {
        assertEquals(Set.of(), tree.asMultiSelect().getValue());
        assertThrows(IllegalStateException.class, () -> tree.asSingleSelect());
        assertEquals("true", tree.getElement().getAttribute("aria-multiselectable"));

        tree.setSelectionMode(SelectionMode.SINGLE);

        assertNull(tree.getElement().getAttribute("aria-multiselectable"));
        assertThrows(IllegalStateException.class, () -> tree.asMultiSelect());
    }

    @Test
    void checkingAParentChecksItsDescendantsWithoutListingThem() {
        tree.asMultiSelect().select(docs);

        assertEquals(Set.of(docs), tree.asMultiSelect().getValue());
        assertTrue(tree.isChecked(y2026));
        assertTrue(tree.asMultiSelect().isSelected(y2026));
        assertFalse(tree.isChecked(music));
        assertFalse(tree.isPartiallyChecked(docs));
    }

    @Test
    void checkingTheLastUncheckedChildOfAParentChecksTheParentInstead() {
        tree.asMultiSelect().select(y2025);
        assertEquals(Set.of(y2025), tree.asMultiSelect().getValue());

        tree.asMultiSelect().select(y2026);
        assertEquals(Set.of(letters), tree.asMultiSelect().getValue());

        tree.asMultiSelect().select(invoices);
        assertEquals(Set.of(docs), tree.asMultiSelect().getValue(), "all the child items of Documents, up again");
    }

    @Test
    void checkingAnItemTakesTheCheckedDescendantsIntoIt() {
        tree.asMultiSelect().select(y2025, invoices);

        tree.asMultiSelect().select(letters);

        assertEquals(Set.of(docs), tree.asMultiSelect().getValue());
    }

    @Test
    void uncheckingAnItemUnderACheckedAncestorChecksItsSiblingsInstead() {
        tree.asMultiSelect().select(docs);

        tree.asMultiSelect().deselect(y2026);

        assertEquals(Set.of(invoices, y2025), tree.asMultiSelect().getValue());
        assertTrue(tree.isPartiallyChecked(docs));
        assertTrue(tree.isPartiallyChecked(letters));
        assertFalse(tree.isChecked(letters));
        assertFalse(tree.isPartiallyChecked(y2025), "checked, not partially");
    }

    @Test
    void uncheckingAPartiallyCheckedItemUnchecksItsDescendants() {
        tree.asMultiSelect().select(y2025, invoices);

        tree.asMultiSelect().deselect(docs);

        assertEquals(Set.of(), tree.asMultiSelect().getValue());
        assertFalse(tree.isPartiallyChecked(docs));
    }

    @Test
    void settingTheValueKeepsTheTopMostCheckedItems() {
        tree.asMultiSelect().setValue(Set.of(y2025, y2026, music));

        assertEquals(Set.of(letters, music), tree.asMultiSelect().getValue());
    }

    @Test
    void aChangeFiresOneSelectionEventWithWhatWasAddedAndRemoved() {
        tree.asMultiSelect().select(y2025);
        List<MultiSelectionEvent<Tree<Folder>, Folder>> events = new ArrayList<>();
        tree.asMultiSelect().addSelectionListener(events::add);
        List<Object> values = new ArrayList<>();
        tree.asMultiSelect().addValueChangeListener(event -> values.add(event.getValue()));

        tree.asMultiSelect().select(y2026);
        tree.asMultiSelect().select(y2026);

        assertEquals(1, events.size());
        assertEquals(Set.of(letters), events.get(0).getAddedSelection());
        assertEquals(Set.of(y2025), events.get(0).getRemovedSelection());
        assertFalse(events.get(0).isFromClient());
        assertEquals(List.of(Set.of(letters)), values);
    }

    @Test
    void singleSelectionListenersHearNothingOfCheckboxes() {
        tree.setSelectionMode(SelectionMode.SINGLE);
        List<Object> heard = new ArrayList<>();
        tree.asSingleSelect().addValueChangeListener(heard::add);
        tree.setSelectionMode(SelectionMode.CHECKBOX);

        tree.asMultiSelect().select(docs);

        assertTrue(heard.isEmpty());
    }

    @Test
    void theBrowserGetsTheStateOfEachItemItFetches() {
        assertNull(treeItem(null, "Documents", inSingleMode()).checked(), "no checkbox in single selection mode");
        tree.setSelectionMode(SelectionMode.CHECKBOX);
        tree.asMultiSelect().select(y2025);

        TreeItem documents = treeItem(null, "Documents", tree);
        assertEquals("mixed", documents.checked());
        assertEquals("false", treeItem(null, "Music", tree).checked());
        TreeItem lettersItem = treeItem(documents.key(), "Letters", tree);
        assertEquals("mixed", lettersItem.checked());
        assertEquals("true", treeItem(lettersItem.key(), "2025", tree).checked());

        tree.asMultiSelect().select(docs);
        assertEquals("true", treeItem(lettersItem.key(), "2026", tree).checked(), "a descendant of a checked item");
    }

    @Test
    void aChangeTellsTheBrowserTheCheckedAndPartiallyCheckedItemsItHas() {
        String documentsKey = treeItem(null, "Documents", tree).key();
        String lettersKey = treeItem(documentsKey, "Letters", tree).key();
        calls("setChecked");

        tree.asMultiSelect().select(y2025);

        assertEquals(List.of(List.of(List.of(), List.of(documentsKey, lettersKey))), calls("setChecked"),
                "2025 is not fetched: the browser learns of it when it fetches it");

        String y2025Key = treeItem(lettersKey, "2025", tree).key();
        tree.asMultiSelect().select(y2026);

        assertEquals(List.of(List.of(List.of(lettersKey), List.of(documentsKey))), calls("setChecked"));
        assertFalse(y2025Key.isEmpty());
    }

    @Test
    void theUserChecksAndUnchecksItems() {
        String documentsKey = treeItem(null, "Documents", tree).key();
        List<MultiSelectionEvent<Tree<Folder>, Folder>> events = new ArrayList<>();
        tree.asMultiSelect().addSelectionListener(events::add);

        tree.setChecked(documentsKey, true);
        assertEquals(Set.of(docs), tree.asMultiSelect().getValue());
        assertTrue(events.get(0).isFromClient());

        tree.setChecked(documentsKey, false);
        assertEquals(Set.of(), tree.asMultiSelect().getValue());

        tree.setChecked("unknown", true);
        assertEquals(2, events.size());
    }

    @Test
    void theUserCannotCheckWhileReadOnlyNorADisabledItemAndTheBrowserIsToldTheTruth() {
        tree.setItemEnabledProvider(folder -> !folder.equals(music));
        String musicKey = treeItem(null, "Music", tree).key();
        String documentsKey = treeItem(null, "Documents", tree).key();
        calls("setChecked");

        tree.setChecked(musicKey, true);
        tree.asMultiSelect().setReadOnly(true);
        tree.setChecked(documentsKey, true);

        assertEquals(Set.of(), tree.asMultiSelect().getValue());
        assertEquals(2, calls("setChecked").size(), "each undone in the browser");
    }

    @Test
    void switchingTheSelectionModeClearsTheSelection() {
        tree.asMultiSelect().select(docs);

        tree.setSelectionMode(SelectionMode.SINGLE);
        tree.setSelectionMode(SelectionMode.CHECKBOX);

        assertEquals(Set.of(), tree.asMultiSelect().getValue());
    }

    @Test
    void checkboxSelectionNeedsGetParent() {
        tree.setDataProvider(new AbstractBackEndHierarchicalDataProvider<Folder, Void>() {
            @Override
            public int getChildCount(HierarchicalQuery<Folder, Void> query) {
                return query.getParent() == null ? 1 : 0;
            }

            @Override
            public boolean hasChildren(Folder item) {
                return false;
            }

            @Override
            protected Stream<Folder> fetchChildrenFromBackEnd(HierarchicalQuery<Folder, Void> query) {
                return query.getParent() == null ? Stream.of(docs) : Stream.empty();
            }
        });

        assertThrows(UnsupportedOperationException.class, () -> tree.asMultiSelect().select(docs));
    }

    private Tree<Folder> inSingleMode() {
        tree.setSelectionMode(SelectionMode.SINGLE);
        return tree;
    }

    /** An item of a page the browser fetches, by its label. */
    private static TreeItem treeItem(String parentKey, String label, Tree<Folder> tree) {
        return tree.fetchChildren(parentKey, 0, 50).items().stream().filter(item -> item.label().equals(label))
                .findFirst().orElseThrow();
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
}
