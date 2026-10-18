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
        Locator tree = page.getByRole(AriaRole.TREE, new Page.GetByRoleOptions().setName("Folders"));
        Locator items = tree.getByRole(AriaRole.TREEITEM);

        assertThat(items).hasText(new String[] { "Documents", "Music", "Pictures" });
        assertEquals(List.of("1", "1", "1"),
                items.all().stream().map(item -> item.getAttribute("aria-level")).toList());
    }

    @Test
    void onlyTheRootItemsAreShown() {
        Locator items = page.getByRole(AriaRole.TREEITEM);

        assertThat(items).hasCount(3);
        assertThat(page.getByText("Letters")).hasCount(0);
    }

    @Test
    void thePageHasNoAccessibilityViolations() {
        assertThat(page.getByRole(AriaRole.TREEITEM).first()).isVisible();

        // Vaadin's connection indicator sits outside any landmark; it is not ours to fix
        AxeResults results = new AxeBuilder(page).exclude(List.of("vaadin-connection-indicator")).analyze();

        assertEquals(List.of(), results.getViolations().stream()
                .map(violation -> violation.getId() + ": " + violation.getHelp() + " "
                        + violation.getNodes().stream().map(node -> node.getTarget().toString()).toList())
                .toList());
    }
}
