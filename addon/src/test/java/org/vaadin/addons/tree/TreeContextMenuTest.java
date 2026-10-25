package org.vaadin.addons.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vaadin.browserless.BrowserlessTest;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.data.provider.hierarchy.TreeData;
import com.vaadin.flow.internal.JacksonUtils;

/** The context menu of a tree: which item it opens on, and whether it opens. */
class TreeContextMenuTest extends BrowserlessTest {

    record Folder(String name) {
    }

    private final Folder docs = new Folder("Documents");
    private final Folder music = new Folder("Music");

    private Tree<Folder> tree;
    private TreeContextMenu<Folder> menu;

    @BeforeEach
    void addTree() {
        TreeData<Folder> data = new TreeData<>();
        data.addRootItems(docs, music);
        tree = new Tree<>();
        tree.setItems(data);
        tree.setItemLabelGenerator(Folder::name);
        UI.getCurrent().add(tree);
        menu = tree.addContextMenu();
    }

    @Test
    void theContextMenuOfATreeTargetsIt() {
        assertSame(tree, menu.getTarget());
        assertSame(tree, new TreeContextMenu<>(tree).getTarget());
    }

    @Test
    void onlyATreeCanBeTheTarget() {
        assertThrows(IllegalArgumentException.class, () -> new TreeContextMenu<Folder>().setTarget(new Div()));
    }

    @Test
    void theMenuKnowsTheItemItOpensOn() {
        assertEquals(Optional.empty(), menu.getTargetItem());

        assertTrue(menu.onBeforeOpenMenu(detail(key(music))));
        assertEquals(Optional.of(music), menu.getTargetItem());

        assertTrue(menu.onBeforeOpenMenu(detail("")));
        assertEquals(Optional.empty(), menu.getTargetItem(), "opened outside the items");
    }

    @Test
    void anItemNoLongerKnownIsNoItem() {
        menu.onBeforeOpenMenu(detail("unknown"));

        assertEquals(Optional.empty(), menu.getTargetItem());
    }

    @Test
    void theDynamicContentHandlerGetsTheItemAndDecidesWhetherTheMenuOpens() {
        List<Folder> handled = new ArrayList<>();
        menu.setDynamicContentHandler(folder -> {
            handled.add(folder);
            return folder != null && folder.equals(docs);
        });

        assertTrue(menu.onBeforeOpenMenu(detail(key(docs))));
        assertFalse(menu.onBeforeOpenMenu(detail(key(music))));
        assertFalse(menu.onBeforeOpenMenu(detail("")));

        assertEquals(Arrays.asList(docs, music, null), handled);
        assertNull(new TreeContextMenu<Folder>().getDynamicContentHandler());
    }

    private static ObjectNode detail(String key) {
        ObjectNode detail = JacksonUtils.createObjectNode();
        detail.put("key", key);
        return detail;
    }

    /** The key of a root item, fetching the root items for it. */
    private String key(Folder rootItem) {
        return tree.fetchChildren(null, 0, 50).items().stream()
                .filter(item -> item.label().equals(rootItem.name())).findFirst().orElseThrow().key();
    }
}
