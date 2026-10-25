package org.vaadin.addons.tree.demo;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Main;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Section;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.binder.Binder;
import com.vaadin.flow.data.provider.hierarchy.HierarchicalQuery;
import com.vaadin.flow.data.provider.hierarchy.TreeData;
import com.vaadin.flow.data.provider.hierarchy.TreeDataProvider;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.function.SerializablePredicate;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import org.vaadin.addons.tree.DropLocation;
import org.vaadin.addons.tree.Tree;
import org.vaadin.addons.tree.TreeContextMenu;
import org.vaadin.addons.tree.TreeI18n;

/**
 * A tree of folders kept in memory, a large product catalogue fetched from a
 * backend, projects that change and fail to load, and a tree bound to a form.
 */
@Route("")
@PageTitle("Tree for Vaadin")
public class TreeView extends Main {

    /** A folder of the demo's file system. */
    public record Folder(String name) {
    }

    private static final int REPORTS = 1;
    private static final int ARCHIVE = 2;
    /** The ids of the reports added, after the ones {@link #fillProjects} gives. */
    private static final AtomicInteger NEXT_ID = new AtomicInteger(100);

    /** A project, which the user can rename: the same project, by its id, whatever its name. */
    public static class Project {

        private final int id;
        private String name;

        public Project(int id, String name) {
            this.id = id;
            this.name = name;
        }

        public int getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Project project && project.id == id;
        }

        @Override
        public int hashCode() {
            return Integer.hashCode(id);
        }
    }

    /** A shortcut to a folder, which a tree bound with a {@link Binder} edits. */
    public static class Shortcut {

        private Folder folder;

        public Folder getFolder() {
            return folder;
        }

        public void setFolder(Folder folder) {
            this.folder = folder;
        }
    }

    public TreeView() {
        // Room on the sides on a phone, and lines short enough to read on a wide screen
        getStyle().set("display", "block").set("padding", "0 1rem 1rem").set("max-width", "60rem");
        add(new H1("Tree for Vaadin"), new ThemeSwitcher(), folders(), catalogue(), products(), projects(), shortcut());
    }

    /** A tree as wide as the demo's trees, narrower on a narrow screen. */
    private static <T> Tree<T> newTree() {
        Tree<T> tree = new Tree<>();
        tree.setWidth("20em");
        tree.setMaxWidth("100%");
        return tree;
    }

    private static Section folders() {
        TreeData<Folder> data = folderData();
        TreeDataProvider<Folder> provider = new TreeDataProvider<>(data);
        Tree<Folder> tree = newTree();
        tree.setAriaLabel("Folders");
        tree.setDataProvider(provider);
        tree.setItemLabelGenerator(Folder::name);
        tree.setItemIconGenerator(folder -> "vaadin:folder");
        // The path of a child item; a root item needs none
        tree.setItemTooltipGenerator(folder -> data.getParent(folder) == null ? null : path(data, folder));

        // Right-click, long press, Shift+F10 or the Menu key: items for the folder the menu opens on
        TreeContextMenu<Folder> menu = tree.addContextMenu();
        menu.setDynamicContentHandler(folder -> {
            if (folder == null) {
                return false;
            }
            menu.removeAll();
            menu.addItem("Select", event -> menu.getTargetItem().ifPresent(tree.asSingleSelect()::setValue));
            if (!data.getChildren(folder).isEmpty()) {
                boolean expanded = tree.isExpanded(folder);
                menu.addItem(expanded ? "Collapse" : "Expand",
                        event -> menu.getTargetItem().ifPresent(target -> {
                            if (expanded) {
                                tree.collapse(target);
                            } else {
                                tree.expand(target);
                            }
                        }));
            }
            return true;
        });

        List<Folder> parents = descendants(data, null).filter(folder -> !data.getChildren(folder).isEmpty()).toList();
        Button expandAll = new Button("Expand all", event -> tree.expand(parents));
        Button collapseAll = new Button("Collapse all", event -> tree.collapse(parents));
        Folder year = new Folder("2026");
        Button select = new Button("Select 2026", event -> tree.asSingleSelect().setValue(year));
        Button show = new Button("Show 2026", event -> tree.scrollToItem(year));
        Button clear = new Button("Clear selection", event -> tree.asSingleSelect().clear());
        Paragraph log = eventLog(tree, Folder::name);

        // Typing filters the folders, keeping the ancestors of those that match, expanded to show them
        TextField filter = new TextField("Filter folders");
        filter.setClearButtonVisible(true);
        filter.setValueChangeMode(ValueChangeMode.LAZY);
        tree.setI18n(new TreeI18n().setEmpty("No folder matches"));
        filter.addValueChangeListener(event -> {
            String text = event.getValue().strip().toLowerCase(Locale.ROOT);
            if (text.isEmpty()) {
                provider.clearFilters();
                log.setText("Showing all folders");
                return;
            }
            SerializablePredicate<Folder> matches = folder -> folder.name().toLowerCase(Locale.ROOT).contains(text);
            provider.setFilter(matches);
            tree.expandRecursively(data.getRootItems(), Integer.MAX_VALUE);
            long count = descendants(data, null).filter(matches).count();
            log.setText(count == 1 ? "1 folder matches" : count + " folders match");
        });

        return new Section(new H2("Folders, in memory"), filter, expandAll, collapseAll, select, show, clear, tree,
                log);
    }

    private static Section catalogue() {
        Tree<CatalogDataProvider.Entry> tree = newTree();
        tree.setAriaLabel("Catalogue");
        tree.setDataProvider(new CatalogDataProvider());
        tree.setItemLabelGenerator(CatalogDataProvider.Entry::name);
        tree.setItemIconGenerator(entry -> entry instanceof CatalogDataProvider.Category ? "vaadin:folder" : "vaadin:file");
        // Categories tell how many products they hold; products out of stock tell why they are disabled
        tree.setItemTooltipGenerator(entry -> switch (entry) {
            case CatalogDataProvider.Category category -> category.productCount() + " products";
            case CatalogDataProvider.Product product -> product.inStock() ? null : "Out of stock";
        });
        // Products out of stock cannot be selected
        tree.setItemEnabledProvider(entry -> !(entry instanceof CatalogDataProvider.Product product)
                || product.inStock());
        tree.setHeight("20em");

        Paragraph log = eventLog(tree, CatalogDataProvider.Entry::name);

        // A search the backend answers: the tree fetches the pages down to the product, then shows and selects it
        TextField search = new TextField("Find a product");
        search.setPlaceholder("Books item 600");
        Button find = new Button("Find", event -> CatalogDataProvider.find(search.getValue()).ifPresentOrElse(
                product -> {
                    tree.scrollToItem(product);
                    tree.asSingleSelect().setValue(product);
                },
                () -> log.setText("No product named " + search.getValue().strip())));
        search.addKeyDownListener(Key.ENTER, event -> find.click());

        return new Section(new H2("Catalogue, from a backend"), search, find, tree, log);
    }

    /**
     * The catalogue in checkbox selection mode: checking a category checks its
     * products without fetching them, and the value is the top-most checked
     * items.
     */
    private static Section products() {
        Tree<CatalogDataProvider.Entry> tree = newTree();
        tree.setAriaLabel("Products");
        tree.setSelectionMode(Tree.SelectionMode.CHECKBOX);
        tree.setDataProvider(new CatalogDataProvider());
        tree.setItemLabelGenerator(CatalogDataProvider.Entry::name);
        tree.setItemIconGenerator(entry -> entry instanceof CatalogDataProvider.Category ? "vaadin:folder" : "vaadin:file");
        tree.setHeight("20em");

        Paragraph value = new Paragraph("Checked: nothing");
        value.getElement().setAttribute("role", "status");
        tree.asMultiSelect().addValueChangeListener(event -> value.setText(checkedText(event.getValue())));
        Button clear = new Button("Uncheck all", event -> tree.asMultiSelect().clear());

        return new Section(new H2("Checkbox selection, from a backend"), clear, tree, value);
    }

    /** The checked items, the first few by name, as "Checked: 3 items: Books, Games item 1, Music". */
    private static String checkedText(Set<CatalogDataProvider.Entry> checked) {
        if (checked.isEmpty()) {
            return "Checked: nothing";
        }
        String names = checked.stream().limit(3).map(CatalogDataProvider.Entry::name)
                .collect(Collectors.joining(", "));
        return checked.size() == 1 ? "Checked: " + names
                : "Checked: " + checked.size() + " items: " + names + (checked.size() > 3 ? ", …" : "");
    }

    /**
     * Projects whose data the buttons change, refreshing one item or all of
     * them. The child items of the archive fail to load.
     */
    private static Section projects() {
        TreeData<Project> data = new TreeData<>();
        fillProjects(data);
        Project archive = new Project(ARCHIVE, "Archive");
        TreeDataProvider<Project> provider = new TreeDataProvider<>(data) {
            @Override
            public Stream<Project> fetchChildren(HierarchicalQuery<Project, SerializablePredicate<Project>> query) {
                if (archive.equals(query.getParent())) {
                    throw new IllegalStateException("The archive is offline");
                }
                return super.fetchChildren(query);
            }
        };
        Tree<Project> tree = newTree();
        tree.setAriaLabel("Projects");
        tree.setDataProvider(provider);
        tree.setItemLabelGenerator(Project::getName);
        tree.setItemIconGenerator(project -> "vaadin:folder");
        Paragraph log = eventLog(tree, Project::getName);

        // F2, double-click or the context menu renames a project; a blank name is rejected by leaving it as it is
        tree.setItemEditableProvider(project -> true);
        tree.addEditListener(event -> {
            Project project = event.getItem();
            String name = event.getLabel().strip();
            if (name.isEmpty()) {
                log.setText("Kept " + project.getName() + ": a name cannot be blank");
                return;
            }
            log.setText("Renamed " + project.getName() + " to " + name + " by the user");
            project.setName(name);
            provider.refreshItem(project);
        });
        // Dragging a project, or Ctrl+X then Ctrl+V or Ctrl+Shift+V, moves it: the app moves it in the data
        tree.setItemDraggableProvider(project -> true);
        tree.addDropListener(event -> {
            move(data, event.getDraggedItem(), event.getTargetItem(), event.getLocation());
            provider.refreshAll();
            String where = event.getLocation().name().toLowerCase(Locale.ROOT);
            log.setText("Moved " + event.getDraggedItem().getName() + " " + where + " "
                    + event.getTargetItem().getName() + " by the user");
        });

        // The context menu renames and moves projects: the way on a touch screen, without F2, Ctrl+X nor dragging
        TreeContextMenu<Project> menu = tree.addContextMenu();
        menu.setDynamicContentHandler(project -> {
            if (project == null) {
                return false;
            }
            menu.removeAll();
            menu.addItem("Rename", event -> tree.editItem(project));
            menu.addItem("Cut", event -> tree.cutItem(project));
            tree.getCutItem().filter(cut -> !isSelfOrAncestor(data, cut, project)).ifPresent(cut -> {
                menu.addItem("Paste into", event -> tree.pasteInto(project));
                menu.addItem("Paste before", event -> tree.pasteBefore(project));
                menu.addItem("Paste after", event -> tree.pasteAfter(project));
            });
            return true;
        });

        Button add = new Button("Add a report", event -> {
            // The reports in the data, with the name the user may have given them
            data.getRootItems().stream().filter(project -> project.getId() == REPORTS).findFirst().ifPresent(reports -> {
                int number = data.getChildren(reports).size() + 1;
                data.addItem(reports, new Project(NEXT_ID.getAndIncrement(), "Report " + number));
                provider.refreshItem(reports, true);
            });
        });
        Button removeAll = new Button("Remove all projects", event -> {
            data.clear();
            provider.refreshAll();
        });
        Button restore = new Button("Restore projects", event -> {
            data.clear();
            fillProjects(data);
            provider.refreshAll();
        });

        // The texts the tree shows by itself, and the ones for assistive technology
        Checkbox french = new Checkbox("French texts", event -> tree.setI18n(event.getValue()
                ? new TreeI18n().setLoading("Chargement…").setEmpty("Aucun élément")
                        .setLoadError("Impossible de charger les éléments")
                        .setSelectionPath("Contient l’élément sélectionné")
                        .setEditor("Nom")
                : new TreeI18n()));

        return new Section(new H2("Projects, changing and failing"), add, removeAll, restore, french, tree, log);
    }

    /**
     * The tree as a field of a form: its single selection bound to a bean with
     * a {@link Binder}, which can be made read-only, and the tree disabled.
     */
    private static Section shortcut() {
        // A visible label, which names the tree
        Paragraph label = new Paragraph("Shortcut folder");
        label.setId("shortcut-folder-label");
        Tree<Folder> tree = newTree();
        tree.setAriaLabelledBy(label.getId().orElseThrow());
        tree.setItems(folderData());
        tree.setItemLabelGenerator(Folder::name);
        tree.setItemIconGenerator(folder -> "vaadin:folder");

        Shortcut shortcut = new Shortcut();
        Binder<Shortcut> binder = new Binder<>(Shortcut.class);
        binder.forField(tree.asSingleSelect()).bind(Shortcut::getFolder, Shortcut::setFolder);
        binder.setBean(shortcut);

        Paragraph status = new Paragraph();
        status.getElement().setAttribute("role", "status");
        binder.addValueChangeListener(event -> status.setText("The shortcut opens "
                + (shortcut.getFolder() == null ? "no folder" : shortcut.getFolder().name())));
        Checkbox readOnly = new Checkbox("Read-only", event -> binder.setReadOnly(event.getValue()));
        Checkbox disabled = new Checkbox("Disabled", event -> tree.setEnabled(!event.getValue()));

        Div options = new Div(readOnly, disabled);
        options.getStyle().set("display", "flex").set("flex-wrap", "wrap").set("gap", "1em");

        return new Section(new H2("A form field, read-only or disabled"), options, label, tree, status);
    }

    /** Whether a project is the other one or one of its ancestors: the other cannot be moved into it. */
    private static boolean isSelfOrAncestor(TreeData<Project> data, Project project, Project other) {
        for (Project current = other; current != null; current = data.getParent(current)) {
            if (current.equals(project)) {
                return true;
            }
        }
        return false;
    }

    /** Moves a project before, after or into another: into puts it last among the other's child items. */
    private static void move(TreeData<Project> data, Project moved, Project target, DropLocation location) {
        Project parent = location == DropLocation.INTO ? target : data.getParent(target);
        data.setParent(moved, parent);
        List<Project> siblings = (parent == null ? data.getRootItems() : data.getChildren(parent)).stream()
                .filter(sibling -> !sibling.equals(moved)).toList();
        switch (location) {
            case BEFORE -> {
                int index = siblings.indexOf(target);
                // After the previous sibling, or first
                data.moveAfterSibling(moved, index == 0 ? null : siblings.get(index - 1));
            }
            case AFTER -> data.moveAfterSibling(moved, target);
            case INTO -> data.moveAfterSibling(moved, siblings.isEmpty() ? null : siblings.getLast());
        }
    }

    private static void fillProjects(TreeData<Project> data) {
        Project reports = new Project(REPORTS, "Reports");
        Project archive = new Project(ARCHIVE, "Archive");
        data.addRootItems(reports, archive, new Project(3, "Drafts"));
        data.addItems(reports, new Project(4, "Report 1"));
        data.addItems(archive, new Project(5, "2019"));
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
