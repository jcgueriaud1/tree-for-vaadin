package org.vaadin.addons.tree.demo;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.deque.html.axecore.playwright.AxeBuilder;
import com.deque.html.axecore.results.AxeResults;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;

/** The demo in a real browser, as assistive technology sees it. Started by the build on demo.url. */
class TreeViewIT {

    private static final String URL = System.getProperty("demo.url", "http://localhost:8080/");

    private static Playwright playwright;
    private static Browser browser;
    private Page page;

    @BeforeAll
    static void launchBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
    }

    @AfterAll
    static void closeBrowser() {
        playwright.close();
    }

    @BeforeEach
    void openDemo() {
        page = browser.newPage();
        page.navigate(URL);
    }

    @AfterEach
    void closePage() {
        page.close();
    }

    @Test
    void theRootItemsAreTreeItemsOfALabelledTree() {
        Locator items = folders().getByRole(AriaRole.TREEITEM);

        assertThat(items).hasText(new String[] { "Documents", "Music", "Pictures" });
        assertEquals(List.of("1", "1", "1"),
                items.all().stream().map(item -> item.getAttribute("aria-level")).toList());
    }

    @Test
    void onlyTheRootItemsAreShown() {
        Locator items = folders().getByRole(AriaRole.TREEITEM);

        assertThat(items).hasCount(3);
        assertThat(page.getByText("Letters")).hasCount(0);
    }

    @Test
    void clickingTheToggleOfAnItemShowsItsChildItemsOneLevelDownAndClickingItAgainHidesThem() {
        Locator documents = item(folders(), "Documents");
        assertThat(documents).hasAttribute("aria-expanded", "false");

        documents.locator("[part=toggle]").click();

        assertThat(folders().getByRole(AriaRole.TREEITEM))
                .hasText(new String[] { "Documents", "Invoices", "Letters", "Music", "Pictures" });
        assertThat(documents).hasAttribute("aria-expanded", "true");
        Locator letters = item(folders(), "Letters");
        assertThat(letters).hasAttribute("aria-level", "2");
        assertThat(letters).hasAttribute("aria-posinset", "2");
        assertThat(letters).hasAttribute("aria-setsize", "2");
        assertThat(page.getByRole(AriaRole.STATUS).first()).hasText("Expanded Documents by the user");

        documents.locator("[part=toggle]").click();

        assertThat(folders().getByRole(AriaRole.TREEITEM)).hasText(new String[] { "Documents", "Music", "Pictures" });
        assertThat(page.getByRole(AriaRole.STATUS).first()).hasText("Collapsed Documents by the user");
    }

    @Test
    void theServerCanExpandItemsTheBrowserHasNotFetchedYet() {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Expand all")).click();

        assertThat(folders().getByRole(AriaRole.TREEITEM)).hasText(new String[] { "Documents", "Invoices",
                "Letters", "2025", "2026", "Music", "Jazz", "Classical", "Pictures", "Holidays" });
        assertThat(item(folders(), "2026")).hasAttribute("aria-level", "3");
        assertThat(page.getByRole(AriaRole.STATUS).first()).hasText(
                "Expanded Documents, Letters, Music, Pictures by the server");

        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Collapse all")).click();

        assertThat(folders().getByRole(AriaRole.TREEITEM)).hasText(new String[] { "Documents", "Music", "Pictures" });
    }

    @Test
    void aLargeLevelIsFetchedPageByPageAsTheUserScrolls() {
        Locator catalogue = page.getByRole(AriaRole.TREE, new Page.GetByRoleOptions().setName("Catalogue"));
        Locator books = item(catalogue, "Books");
        assertThat(catalogue.getByRole(AriaRole.TREEITEM)).hasCount(4);

        books.locator("[part=toggle]").click();

        assertThat(catalogue.getByRole(AriaRole.TREEITEM)).hasCount(4 + 50);
        Locator first = item(catalogue, "Books item 1");
        assertThat(first).hasAttribute("aria-posinset", "1");
        assertThat(first).hasAttribute("aria-setsize", "1000");

        catalogue.evaluate("tree => tree.scrollTop = tree.scrollHeight");

        assertThat(catalogue.getByRole(AriaRole.TREEITEM)).hasCount(4 + 100);
        assertThat(item(catalogue, "Books item 100")).hasAttribute("aria-posinset", "100");
        assertThat(item(catalogue, "Books item 100")).hasAttribute("aria-setsize", "1000");
    }

    @Test
    void clickingAnItemSelectsItAndMovesFocusThere() {
        Locator items = folders().getByRole(AriaRole.TREEITEM);
        assertThat(items).hasCount(3);
        assertThat(folders()).not().hasAttribute("aria-multiselectable", "true");
        assertEquals(List.of("false", "false", "false"),
                items.all().stream().map(item -> item.getAttribute("aria-selected")).toList());

        item(folders(), "Music").click();

        assertThat(item(folders(), "Music")).hasAttribute("aria-selected", "true");
        assertThat(item(folders(), "Music")).isFocused();
        assertThat(item(folders(), "Documents")).hasAttribute("aria-selected", "false");
        assertThat(status()).hasText("Selected Music by the user");

        item(folders(), "Documents").click();

        assertThat(item(folders(), "Documents")).hasAttribute("aria-selected", "true");
        assertThat(item(folders(), "Music")).hasAttribute("aria-selected", "false");
        assertThat(item(folders(), "Documents")).hasAttribute("aria-expanded", "false");
        assertThat(status()).hasText("Selected Documents by the user");
    }

    @Test
    void clickingTheSelectedItemAgainKeepsItSelected() {
        item(folders(), "Music").click();
        item(folders(), "Documents").locator("[part=toggle]").click();
        assertThat(status()).hasText("Expanded Documents by the user");

        item(folders(), "Music").click();

        assertThat(item(folders(), "Music")).hasAttribute("aria-selected", "true");
        assertThat(status()).hasText("Expanded Documents by the user");
    }

    @Test
    void theSelectionPathMarksTheAncestorsOfTheSelectedItemExpandedOrCollapsed() {
        item(folders(), "Documents").locator("[part=toggle]").click();
        item(folders(), "Letters").locator("[part=toggle]").click();

        item(folders(), "2026").click();

        assertThat(item(folders(), "2026")).hasAttribute("aria-selected", "true");
        assertOnSelectionPath("Documents");
        assertOnSelectionPath("Letters");
        assertThat(item(folders(), "2026").locator("[part=selection-path-marker]")).hasCount(0);
        assertThat(item(folders(), "Music").locator("[part=selection-path-marker]")).hasCount(0);

        item(folders(), "Documents").locator("[part=toggle]").click();

        assertThat(folders().getByRole(AriaRole.TREEITEM)).hasText(new String[] { "Documents", "Music", "Pictures" });
        assertOnSelectionPath("Documents");
        assertThat(status()).hasText("Collapsed Documents by the user");

        item(folders(), "Documents").locator("[part=toggle]").click();

        assertThat(item(folders(), "2026")).hasAttribute("aria-selected", "true");
    }

    @Test
    void theServerSelectsAnItemWithoutExpandingItsAncestorsAndClearsTheSelection() {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Select 2026")).click();

        assertThat(status()).hasText("Selected 2026 by the server");
        assertThat(folders().getByRole(AriaRole.TREEITEM)).hasText(new String[] { "Documents", "Music", "Pictures" });
        assertOnSelectionPath("Documents");

        item(folders(), "Documents").locator("[part=toggle]").click();
        assertOnSelectionPath("Letters");
        item(folders(), "Letters").locator("[part=toggle]").click();
        assertThat(item(folders(), "2026")).hasAttribute("aria-selected", "true");

        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Clear selection")).click();

        assertThat(status()).hasText("Cleared the selection by the server");
        assertThat(folders().locator("[aria-selected=true]")).hasCount(0);
        assertThat(folders().locator("[selection-path]")).hasCount(0);
    }

    @Test
    void scrollingToAnItemExpandsItsAncestorsWithoutSelectingIt() {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Show 2026")).click();

        assertThat(item(folders(), "2026")).isInViewport();
        assertThat(item(folders(), "Letters")).hasAttribute("aria-expanded", "true");
        assertThat(status()).hasText("Expanded Documents, Letters by the server");
        assertThat(folders().locator("[aria-selected=true]")).hasCount(0);
    }

    @Test
    void scrollingToAnItemWhoseAncestorsWereNeverFetchedFetchesThePagesOnTheWay() {
        Locator catalogue = page.getByRole(AriaRole.TREE, new Page.GetByRoleOptions().setName("Catalogue"));

        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Show and select Books item 600")).click();

        Locator product = item(catalogue, "Books item 600");
        assertThat(product).isInViewport();
        assertThat(product).hasAttribute("aria-selected", "true");
        assertThat(product).hasAttribute("aria-posinset", "600");
        assertThat(item(catalogue, "Books")).hasAttribute("aria-expanded", "true");
        assertThat(item(catalogue, "Books")).hasAttribute("selection-path", "");
        assertThat(page.getByRole(AriaRole.STATUS).nth(1)).hasText("Selected Books item 600 by the server");
    }

    @Test
    void thePageHasNoAccessibilityViolations() {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Expand all")).click();
        assertThat(item(folders(), "Holidays")).isVisible();
        item(folders(), "2026").click();
        assertOnSelectionPath("Letters");

        // Vaadin's connection indicator sits outside any landmark; it is not ours to fix
        AxeResults results = new AxeBuilder(page).exclude(List.of("vaadin-connection-indicator")).analyze();

        assertEquals(List.of(), results.getViolations().stream()
                .map(violation -> violation.getId() + ": " + violation.getHelp() + " "
                        + violation.getNodes().stream().map(node -> node.getTarget().toString()).toList())
                .toList());
    }

    private void assertOnSelectionPath(String label) {
        assertThat(item(folders(), label)).hasAttribute("selection-path", "");
        assertThat(item(folders(), label).locator("[part=selection-path-marker]")).isVisible();
        assertThat(item(folders(), label)).hasAttribute("aria-selected", "false");
    }

    private Locator status() {
        return page.getByRole(AriaRole.STATUS).first();
    }

    private Locator folders() {
        return page.getByRole(AriaRole.TREE, new Page.GetByRoleOptions().setName("Folders"));
    }

    private static Locator item(Locator tree, String label) {
        return tree.getByRole(AriaRole.TREEITEM, new Locator.GetByRoleOptions().setName(label).setExact(true));
    }
}
