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
    void thePageHasNoAccessibilityViolations() {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Expand all")).click();
        assertThat(item(folders(), "Holidays")).isVisible();

        // Vaadin's connection indicator sits outside any landmark; it is not ours to fix
        AxeResults results = new AxeBuilder(page).exclude(List.of("vaadin-connection-indicator")).analyze();

        assertEquals(List.of(), results.getViolations().stream()
                .map(violation -> violation.getId() + ": " + violation.getHelp() + " "
                        + violation.getNodes().stream().map(node -> node.getTarget().toString()).toList())
                .toList());
    }

    private Locator folders() {
        return page.getByRole(AriaRole.TREE, new Page.GetByRoleOptions().setName("Folders"));
    }

    private static Locator item(Locator tree, String label) {
        return tree.getByRole(AriaRole.TREEITEM, new Locator.GetByRoleOptions().setName(label).setExact(true));
    }
}
