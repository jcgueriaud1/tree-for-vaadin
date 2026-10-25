package org.vaadin.addons.tree.demo;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;

/** The performance page: it builds a tree of the size asked, and times what the tree does. */
class PerformanceViewIT {

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
    void openPage() {
        page = browser.newPage();
        page.navigate(URL + "performance");
    }

    @AfterEach
    void closePage() {
        page.close();
    }

    @Test
    void theTreeHasTheShapeTheFieldsGive() {
        assertThat(page.getByText("11,100 items in all")).isVisible();

        page.getByLabel("Root items").fill("3");
        page.getByLabel("Levels").fill("2");
        page.getByLabel("Levels").press("Enter");

        assertThat(page.getByText("33 items in all")).isVisible();
        assertThat(tree().getByRole(AriaRole.TREEITEM)).hasText(new String[] { "Item 1", "Item 2", "Item 3" });
    }

    @Test
    void anActionIsTimedUntilTheTreeIsReady() {
        page.getByLabel("Root items").fill("5");
        page.getByLabel("Root items").press("Enter");
        assertThat(page.getByText("555 items in all")).isVisible();

        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Expand all")).click();

        Locator result = page.getByRole(AriaRole.LOG).getByRole(AriaRole.LISTITEM).first();
        assertThat(result).hasText(Pattern.compile("^Expanded all: ready in [\\d,]+ ms \\(server [\\d,]+ ms\\),"
                + " 555 items in the page \\(555 items, page size 50\\)$"));
        TreeViewIT.assertNoAccessibilityViolations(page);
    }

    @Test
    void expandingAllRefusesTreesTooLargeForTheDemo() {
        page.getByLabel("Root items").fill("1000");
        page.getByLabel("Levels").fill("4");
        page.getByLabel("Levels").press("Enter");
        assertThat(page.getByText("1,111,000 items in all")).isVisible();

        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Expand all")).click();

        assertThat(page.getByRole(AriaRole.LOG).getByRole(AriaRole.LISTITEM).first())
                .hasText("Not expanded: 1,111,000 items is more than the 200,000 this demo expands at once");
    }

    private Locator tree() {
        return page.getByRole(AriaRole.TREE, new Page.GetByRoleOptions().setName("Generated items"));
    }
}
