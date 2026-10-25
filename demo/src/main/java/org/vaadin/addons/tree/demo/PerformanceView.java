package org.vaadin.addons.tree.demo;

import java.util.function.Supplier;

import tools.jackson.databind.JsonNode;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.Main;
import com.vaadin.flow.component.html.OrderedList;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Section;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import org.vaadin.addons.tree.Tree;

/**
 * A tree of any size from a {@link GeneratedDataProvider}, and actions that
 * time how long the tree takes to fetch and show the items: from the click in
 * the browser until the tree is no longer busy and has rendered, with the
 * time the server spent and how many items the page holds then.
 */
@Route("performance")
@PageTitle("Tree for Vaadin: performance")
public class PerformanceView extends Main {

    /** Expanding everything fetches every item on the server: more would take the demo down. */
    static final long MAX_ITEMS_TO_EXPAND = 200_000;

    /**
     * Notes in the browser when an action button is clicked, before the request
     * leaves, so that the time measured includes the round trip.
     */
    private static final String RECORD_CLICKS = """
            if (!window.__tfvPerfListening) {
              window.__tfvPerfListening = true;
              document.addEventListener('click', (event) => {
                if (event.composedPath().some((target) => target.hasAttribute?.('data-perf-action'))) {
                  window.__tfvPerfStart = performance.now();
                }
              }, true);
            }
            """;

    /**
     * Waits until the tree has fetched what the action asked for and rendered
     * it: no busy level for two frames in a row. Gives the time since the click
     * and the number of items in the page.
     */
    private static final String WAIT_UNTIL_READY = """
            const tree = $0;
            const start = window.__tfvPerfStart ?? performance.now();
            const busy = () => tree.hasAttribute('aria-busy') || tree.shadowRoot.querySelector('[aria-busy="true"]');
            return new Promise((resolve) => {
              let idleFrames = 0;
              const tick = () => {
                idleFrames = busy() ? 0 : idleFrames + 1;
                if (idleFrames >= 2 || performance.now() - start > 120000) {
                  resolve({
                    ms: Math.round(performance.now() - start),
                    items: tree.shadowRoot.querySelectorAll('[role="treeitem"]').length,
                  });
                } else {
                  requestAnimationFrame(tick);
                }
              };
              requestAnimationFrame(tick);
            });
            """;

    private final IntegerField rootCount = sizeField("Root items", 100);
    private final IntegerField childCount = sizeField("Child items per item", 10);
    private final IntegerField levels = sizeField("Levels", 3);
    private final IntegerField pageSize = sizeField("Page size", 50);
    private final Paragraph size = new Paragraph();
    private final Checkbox checkboxes = new Checkbox("Checkboxes");
    /** Each icon is a vaadin-icon of its own, which costs: see the difference. */
    private final Checkbox icons = new Checkbox("Icons", true);
    private final OrderedList results = new OrderedList();
    private final Div treeHolder = new Div();
    private Tree<GeneratedDataProvider.Item> tree;
    private GeneratedDataProvider provider;

    public PerformanceView() {
        getStyle().set("display", "block").set("padding", "0 1rem 1rem").set("max-width", "60rem");
        levels.setMax(10);
        pageSize.setMax(500);
        // The tree always has the shape the fields give
        for (IntegerField field : new IntegerField[] { rootCount, childCount, levels, pageSize }) {
            field.addValueChangeListener(event -> buildTree());
        }

        Button build = action("Rebuild the tree", "Rebuilt the tree", () -> {
            buildTree();
            return 0L;
        });
        Button expandAll = action("Expand all", "Expanded all", () -> {
            if (provider.size() > MAX_ITEMS_TO_EXPAND) {
                return -1L;
            }
            return timed(() -> tree.expandRecursively(provider.rootItems().toList(), Integer.MAX_VALUE));
        });
        Button collapseAll = action("Collapse all", "Collapsed all",
                () -> timed(() -> tree.collapseRecursively(provider.rootItems().toList(), Integer.MAX_VALUE)));
        Button scrollToLast = action("Scroll to the last item", "Scrolled to the last item",
                () -> timed(() -> tree.scrollToItem(provider.lastItem())));
        Button checkAll = action("Check all", "Checked all", () -> {
            if (tree.getSelectionMode() != Tree.SelectionMode.CHECKBOX) {
                return -2L;
            }
            return timed(() -> tree.asMultiSelect().select(provider.rootItems().toList()));
        });
        checkboxes.addValueChangeListener(event -> buildTree());
        icons.addValueChangeListener(event -> buildTree());

        // A live region announcing each result; on a div, as a list cannot have the role
        Div log = new Div(results);
        log.getElement().setAttribute("role", "log");
        log.getElement().setAttribute("aria-label", "Results, the latest first");
        Div shape = new Div(rootCount, childCount, levels, pageSize);
        shape.getStyle().set("display", "flex").set("flex-wrap", "wrap").set("gap", "1em");

        add(new H1("Tree for Vaadin: performance"), new RouterLink("Back to the demo", TreeView.class),
                new Paragraph("A tree of generated items, fetched from a backend a page at a time. Each action is"
                        + " timed in the browser, from the click until the tree has fetched and rendered what it"
                        + " needs, with the time the server spent and the items in the page then."),
                new Section(new H2("Tree"), shape, size, options(), build, treeHolder),
                new Section(new H2("Actions"), expandAll, collapseAll, scrollToLast, checkAll),
                new Section(new H2("Results"), log));
        buildTree();
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        attachEvent.getUI().getPage().executeJs(RECORD_CLICKS);
    }

    private Div options() {
        Div options = new Div(checkboxes, icons);
        options.getStyle().set("display", "flex").set("flex-wrap", "wrap").set("gap", "1em");
        return options;
    }

    private static IntegerField sizeField(String label, int value) {
        IntegerField field = new IntegerField(label);
        field.setMin(label.equals("Child items per item") ? 0 : 1);
        field.setMax(1_000_000);
        field.setStepButtonsVisible(true);
        field.setValue(value);
        return field;
    }

    private static int valueOf(IntegerField field) {
        Integer value = field.getValue();
        return value == null ? field.getMin() : Math.max(field.getMin(), Math.min(field.getMax(), value));
    }

    /** Builds a new tree of the shape the fields give, in place of the last one. */
    private void buildTree() {
        provider = new GeneratedDataProvider(valueOf(rootCount), valueOf(childCount), valueOf(levels));
        size.setText(String.format("%,d items in all", provider.size()));
        tree = new Tree<>();
        tree.setAriaLabel("Generated items");
        tree.setWidth("30em");
        tree.setMaxWidth("100%");
        tree.setHeight("25em");
        tree.setPageSize(valueOf(pageSize));
        tree.setItemLabelGenerator(GeneratedDataProvider.Item::name);
        if (icons.getValue()) {
            tree.setItemIconGenerator(item -> provider.hasChildren(item) ? "vaadin:folder" : "vaadin:file");
        }
        if (checkboxes.getValue()) {
            tree.setSelectionMode(Tree.SelectionMode.CHECKBOX);
        }
        tree.setDataProvider(provider);
        treeHolder.removeAll();
        treeHolder.add(tree);
    }

    /** The time a server-side step takes, in milliseconds. */
    private static long timed(Runnable step) {
        long start = System.nanoTime();
        step.run();
        return (System.nanoTime() - start) / 1_000_000;
    }

    /**
     * A button whose click runs a step on the server, then times the browser
     * until the tree is ready, and adds the result. The step gives the time it
     * took on the server, or a negative number when it could not run.
     */
    private Button action(String text, String done, Supplier<Long> step) {
        Button button = new Button(text);
        button.getElement().setAttribute("data-perf-action", "");
        button.addClickListener(event -> {
            long serverMs = step.get();
            if (serverMs == -1) {
                addResult(String.format("Not expanded: %,d items is more than the %,d this demo expands at once",
                        provider.size(), MAX_ITEMS_TO_EXPAND));
                return;
            }
            if (serverMs == -2) {
                addResult("Not checked: turn on checkboxes first");
                return;
            }
            String shape = String.format("%,d items, page size %d", provider.size(), tree.getPageSize());
            tree.getElement().executeJs(WAIT_UNTIL_READY, tree.getElement()).then(JsonNode.class,
                    ready -> addResult(String.format("%s: ready in %,d ms (server %,d ms), %,d items in the page (%s)",
                            done, ready.get("ms").asInt(), serverMs, ready.get("items").asInt(), shape)));
        });
        return button;
    }

    private void addResult(String text) {
        results.addComponentAsFirst(new ListItem(text));
    }
}
