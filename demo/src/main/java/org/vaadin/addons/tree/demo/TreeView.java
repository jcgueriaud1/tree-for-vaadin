package org.vaadin.addons.tree.demo;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Main;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Section;
import com.vaadin.flow.data.provider.hierarchy.TreeData;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import org.vaadin.addons.tree.Tree;

/** A tree of folders kept in memory, and a large product catalogue fetched from a backend. */
@Route("")
@PageTitle("Tree for Vaadin")
public class TreeView extends Main {

    /** A folder of the demo's file system. */
    public record Folder(String name) {
    }

    public TreeView() {
        add(new H1("Tree for Vaadin"), new ThemeSwitcher(), folders(), catalogue());
    }

    private static Section folders() {
        TreeData<Folder> data = folderData();
        Tree<Folder> tree = new Tree<>();
        tree.setItems(data);
        tree.setItemLabelGenerator(Folder::name);
        tree.setItemIconGenerator(folder -> "vaadin:folder");
        // The path of a child item; a root item needs none
        tree.setItemTooltipGenerator(folder -> data.getParent(folder) == null ? null : path(data, folder));
        tree.setAriaLabel("Folders");
        tree.setWidth("20em");

        List<Folder> parents = descendants(data, null).filter(folder -> !data.getChildren(folder).isEmpty()).toList();
        Button expandAll = new Button("Expand all", event -> tree.expand(parents));
        Button collapseAll = new Button("Collapse all", event -> tree.collapse(parents));
        Folder year = new Folder("2026");
        Button select = new Button("Select 2026", event -> tree.asSingleSelect().setValue(year));
        Button show = new Button("Show 2026", event -> tree.scrollToItem(year));
        Button clear = new Button("Clear selection", event -> tree.asSingleSelect().clear());

        return new Section(new H2("Folders, in memory"), expandAll, collapseAll, select, show, clear, tree,
                eventLog(tree, Folder::name));
    }

    private static Section catalogue() {
        Tree<CatalogDataProvider.Entry> tree = new Tree<>();
        tree.setDataProvider(new CatalogDataProvider());
        tree.setItemLabelGenerator(CatalogDataProvider.Entry::name);
        tree.setItemIconGenerator(entry -> entry instanceof CatalogDataProvider.Category ? "vaadin:folder" : "vaadin:file");
        // Categories tell how many products they hold; products have no tooltip
        tree.setItemTooltipGenerator(entry -> entry instanceof CatalogDataProvider.Category category
                ? category.productCount() + " products"
                : null);
        tree.setAriaLabel("Catalogue");
        tree.setWidth("20em");
        tree.setHeight("20em");

        CatalogDataProvider.Product product = new CatalogDataProvider.Product(CatalogDataProvider.CATEGORIES.get(0), 599);
        Button show = new Button("Show and select " + product.name(), event -> {
            tree.scrollToItem(product);
            tree.asSingleSelect().setValue(product);
        });

        return new Section(new H2("Catalogue, from a backend"), show, tree,
                eventLog(tree, CatalogDataProvider.Entry::name));
    }

    /** A status line telling the latest items expanded, collapsed or selected, and by whom. */
    private static <T> Paragraph eventLog(Tree<T> tree, Function<T, String> name) {
        Paragraph log = new Paragraph();
        log.getElement().setAttribute("role", "status");
        tree.addExpandListener(event -> log.setText("Expanded " + names(event.getItems(), name) + by(event)));
        tree.addCollapseListener(event -> log.setText("Collapsed " + names(event.getItems(), name) + by(event)));
        tree.asSingleSelect().addValueChangeListener(event -> log.setText(
                (event.getValue() == null ? "Cleared the selection" : "Selected " + name.apply(event.getValue()))
                        + by(event)));
        return log;
    }

    private static String by(ComponentEvent<?> event) {
        return event.isFromClient() ? " by the user" : " by the server";
    }

    private static <T> String names(List<T> items, Function<T, String> name) {
        return items.stream().map(name).collect(Collectors.joining(", "));
    }

    /** The names of a folder and its ancestors, the root item first, such as "Documents / Letters". */
    private static String path(TreeData<Folder> data, Folder folder) {
        Folder parent = data.getParent(folder);
        return parent == null ? folder.name() : path(data, parent) + " / " + folder.name();
    }

    private static Stream<Folder> descendants(TreeData<Folder> data, Folder parent) {
        return data.getChildren(parent).stream().flatMap(child -> Stream.concat(Stream.of(child), descendants(data, child)));
    }

    static TreeData<Folder> folderData() {
        Folder documents = new Folder("Documents");
        Folder music = new Folder("Music");
        Folder pictures = new Folder("Pictures");
        Folder letters = new Folder("Letters");
        TreeData<Folder> data = new TreeData<>();
        data.addRootItems(documents, music, pictures);
        data.addItems(documents, new Folder("Invoices"), letters);
        data.addItems(letters, new Folder("2025"), new Folder("2026"));
        data.addItems(music, new Folder("Jazz"), new Folder("Classical"));
        data.addItems(pictures, new Folder("Holidays"));
        return data;
    }
}
