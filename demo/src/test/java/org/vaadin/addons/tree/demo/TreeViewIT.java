package org.vaadin.addons.tree.demo;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
import com.microsoft.playwright.assertions.LocatorAssertions;
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
    void tabbingIntoTheTreeFocusesTheFirstItem() {
        tabIntoFolders();

        assertThat(item(folders(), "Documents")).isFocused();
        assertEquals(List.of("0", "-1", "-1"), folders().getByRole(AriaRole.TREEITEM).all().stream()
                .map(item -> item.getAttribute("tabindex")).toList());
    }

    @Test
    void tabbingIntoTheTreeFocusesTheSelectedItemEvenAfterFocusMovedElsewhereInIt() {
        item(folders(), "Music").click();
        page.keyboard().press("ArrowDown");
        assertThat(item(folders(), "Pictures")).isFocused();

        tabIntoFolders();

        assertThat(item(folders(), "Music")).isFocused();
    }

    @Test
    void downAndUpMoveFocusAmongVisibleItemsWithoutSelecting() {
        tabIntoFolders();

        page.keyboard().press("ArrowDown");
        assertThat(item(folders(), "Music")).isFocused();
        page.keyboard().press("ArrowDown");
        page.keyboard().press("ArrowDown");
        assertThat(item(folders(), "Pictures")).isFocused();
        page.keyboard().press("ArrowUp");
        assertThat(item(folders(), "Music")).isFocused();

        assertThat(folders().locator("[aria-selected=true]")).hasCount(0);
    }

    @Test
    void homeAndEndMoveFocusToTheFirstAndLastVisibleItems() {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Expand all")).click();
        assertThat(item(folders(), "Holidays")).isVisible();
        tabIntoFolders();

        page.keyboard().press("End");
        assertThat(item(folders(), "Holidays")).isFocused();
        page.keyboard().press("Home");
        assertThat(item(folders(), "Documents")).isFocused();
    }

    @Test
    void rightExpandsAnItemThenMovesToItsFirstChildItem() {
        tabIntoFolders();

        page.keyboard().press("ArrowRight");
        assertThat(item(folders(), "Documents")).hasAttribute("aria-expanded", "true");
        assertThat(item(folders(), "Documents")).isFocused();
        assertThat(status()).hasText("Expanded Documents by the user");
        assertThat(item(folders(), "Invoices")).isVisible();

        page.keyboard().press("ArrowRight");
        assertThat(item(folders(), "Invoices")).isFocused();

        page.keyboard().press("ArrowRight");
        assertThat(item(folders(), "Invoices")).isFocused();
    }

    @Test
    void leftMovesToTheParentItemThenCollapsesIt() {
        tabIntoFolders();
        page.keyboard().press("ArrowRight");
        assertThat(item(folders(), "Invoices")).isVisible();
        page.keyboard().press("ArrowRight");
        assertThat(item(folders(), "Invoices")).isFocused();

        page.keyboard().press("ArrowLeft");
        assertThat(item(folders(), "Documents")).isFocused();

        page.keyboard().press("ArrowLeft");
        assertThat(folders().getByRole(AriaRole.TREEITEM)).hasText(new String[] { "Documents", "Music", "Pictures" });
        assertThat(status()).hasText("Collapsed Documents by the user");
    }

    @Test
    void leftAndRightAreSwappedRightToLeft() {
        folders().evaluate("tree => tree.dir = 'rtl'");
        tabIntoFolders();

        page.keyboard().press("ArrowLeft");
        assertThat(item(folders(), "Invoices")).isVisible();
        page.keyboard().press("ArrowLeft");
        assertThat(item(folders(), "Invoices")).isFocused();

        page.keyboard().press("ArrowRight");
        assertThat(item(folders(), "Documents")).isFocused();
        page.keyboard().press("ArrowRight");
        assertThat(item(folders(), "Documents")).hasAttribute("aria-expanded", "false");
    }

    @Test
    void enterAndSpaceSelectTheFocusedItem() {
        tabIntoFolders();
        page.keyboard().press("ArrowDown");

        page.keyboard().press("Enter");
        assertThat(item(folders(), "Music")).hasAttribute("aria-selected", "true");
        assertThat(status()).hasText("Selected Music by the user");

        page.keyboard().press("ArrowDown");
        page.keyboard().press(" ");
        assertThat(item(folders(), "Pictures")).hasAttribute("aria-selected", "true");
        assertThat(item(folders(), "Music")).hasAttribute("aria-selected", "false");
        assertThat(status()).hasText("Selected Pictures by the user");
    }

    @Test
    void asteriskExpandsTheSiblingsOfTheFocusedItem() {
        tabIntoFolders();

        page.keyboard().press("*");

        assertThat(folders().getByRole(AriaRole.TREEITEM)).hasText(new String[] { "Documents", "Invoices", "Letters",
                "Music", "Jazz", "Classical", "Pictures", "Holidays" });
        assertThat(status()).hasText("Expanded Documents, Music, Pictures by the user");
        assertThat(item(folders(), "Documents")).isFocused();
    }

    @Test
    void typingMovesFocusToTheNextVisibleItemStartingWithIt() {
        tabIntoFolders();
        page.keyboard().press("ArrowRight");
        assertThat(item(folders(), "Letters")).isVisible();

        page.keyboard().press("p");
        assertThat(item(folders(), "Pictures")).isFocused();

        // After a pause, a new search
        page.waitForTimeout(600);
        page.keyboard().press("l");
        assertThat(item(folders(), "Letters")).isFocused();

        // Letters is collapsed: its child items are not searched
        page.waitForTimeout(600);
        page.keyboard().press("2");
        assertThat(item(folders(), "Letters")).isFocused();
    }

    @Test
    void typingSeveralCharactersSearchesOnlyTheLoadedItems() {
        Locator catalogue = page.getByRole(AriaRole.TREE, new Page.GetByRoleOptions().setName("Catalogue"));
        item(catalogue, "Books").locator("[part=toggle]").click();
        assertThat(catalogue.getByRole(AriaRole.TREEITEM)).hasCount(4 + 50);

        page.keyboard().type("books item 4");
        assertThat(item(catalogue, "Books item 4")).isFocused();

        // Books item 51 is not loaded yet: focus stays on the last match, Books item 5
        page.waitForTimeout(600);
        page.keyboard().press("Home");
        page.keyboard().type("books item 51");
        assertThat(item(catalogue, "Books item 5")).isFocused();
        assertThat(catalogue.getByRole(AriaRole.TREEITEM)).hasCount(4 + 50);
    }

    @Test
    void theFocusedItemLooksDifferentFromTheSelectedItem() {
        tabIntoFolders();
        page.keyboard().press("Enter");
        page.keyboard().press("ArrowDown");

        Locator selected = item(folders(), "Documents");
        Locator focused = item(folders(), "Music");
        assertThat(focused).isFocused();
        assertEquals("none", style(selected, "outline-style"));
        assertEquals("solid", style(focused, "outline-style"));
        assertEquals("rgba(0, 0, 0, 0)", style(focused, "background-color"));
        assertNotEquals("rgba(0, 0, 0, 0)", style(selected, "background-color"));
    }

    @Test
    void focusMovesToTheParentWhenTheServerCollapsesTheParentOfTheFocusedItem() {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Expand all")).click();
        assertThat(item(folders(), "2026")).isVisible();
        item(folders(), "2026").focus();

        // Clicked without taking focus away from the tree
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Collapse all"))
                .evaluate("button => button.click()");

        assertThat(folders().getByRole(AriaRole.TREEITEM)).hasText(new String[] { "Documents", "Music", "Pictures" });
        assertThat(item(folders(), "Documents")).isFocused();
    }

    @Test
    void itemsShowADecorativeIconBeforeTheirLabel() {
        Locator documents = item(folders(), "Documents");
        Locator icon = documents.locator("vaadin-icon[part=icon]");

        assertThat(icon).hasAttribute("icon", "vaadin:folder");
        assertThat(icon).hasAttribute("aria-hidden", "true");
        assertThat(icon.locator("svg path")).not().hasCount(0);
        assertThat(documents).hasAccessibleName("Documents");

        Locator catalogue = page.getByRole(AriaRole.TREE, new Page.GetByRoleOptions().setName("Catalogue"));
        Locator books = item(catalogue, "Books");
        assertThat(books.locator("[part=icon]")).hasAttribute("icon", "vaadin:folder");
        books.locator("[part=toggle]").click();
        assertThat(item(catalogue, "Books item 1").locator("[part=icon]")).hasAttribute("icon", "vaadin:file");
    }

    @Test
    void hoveringAnItemShowsItsTooltipWhichAlsoDescribesIt() {
        item(folders(), "Documents").locator("[part=toggle]").click();
        Locator letters = item(folders(), "Letters");
        assertThat(letters).hasAccessibleDescription("Documents / Letters");
        assertThat(item(folders(), "Documents")).hasAccessibleDescription("");

        letters.locator("[part=label]").hover();

        assertThat(tooltip()).hasText("Documents / Letters");

        item(folders(), "Music").locator("[part=label]").hover();

        assertThat(tooltip()).isHidden();
    }

    @Test
    void focusingAnItemWithTheKeyboardShowsItsTooltipAndEscapeHidesIt() {
        tabIntoFolders();
        page.keyboard().press("ArrowRight");
        assertThat(item(folders(), "Invoices")).isVisible();
        page.keyboard().press("ArrowRight");

        assertThat(item(folders(), "Invoices")).isFocused();
        assertThat(tooltip()).hasText("Documents / Invoices");

        page.keyboard().press("Escape");

        assertThat(tooltip()).isHidden();
        assertThat(item(folders(), "Invoices")).isFocused();
    }

    @Test
    void anItemWhoseChildItemsFailToLoadCollapsesBackAndItsGroupBrieflyShowsTheError() {
        Locator archive = item(projects(), "Archive");

        archive.locator("[part=toggle]").click();

        assertThat(page.getByRole(AriaRole.ALERT)).hasText("Could not load the items");
        assertThat(archive).hasAttribute("aria-expanded", "false");
        assertThat(projects().locator("[part=error]")).hasText("Could not load the items");
        assertThat(projectsStatus()).hasText("Collapsed Archive by the user");
        assertThat(item(projects(), "2019")).hasCount(0);

        assertThat(projects().locator("[part=error]")).hasCount(0, new LocatorAssertions.HasCountOptions().setTimeout(10_000));
    }

    @Test
    void aTreeWithoutItemsShowsAndIsDescribedByTheEmptyText() {
        assertThat(item(projects(), "Reports")).isVisible();

        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Remove all projects")).click();

        assertThat(projects().getByText("No items")).isVisible();
        assertThat(projects()).hasAccessibleDescription("No items");
        assertThat(projects().getByRole(AriaRole.TREEITEM)).hasCount(0);

        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Restore projects")).click();

        assertThat(projects().getByRole(AriaRole.TREEITEM)).hasText(new String[] { "Reports", "Archive", "Drafts" });
        assertThat(projects()).hasAccessibleDescription("");
    }

    @Test
    void refreshingAnItemShowsItsNewChildItemsAndKeepsItExpanded() {
        item(projects(), "Reports").locator("[part=toggle]").click();
        assertThat(item(projects(), "Report 1")).isVisible();

        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Add a report")).click();

        assertThat(projects().getByRole(AriaRole.TREEITEM))
                .hasText(new String[] { "Reports", "Report 1", "Report 2", "Archive", "Drafts" });
        assertThat(item(projects(), "Reports")).hasAttribute("aria-expanded", "true");
    }

    @Test
    void refreshingAllKeepsTheItemsThatStillExistExpanded() {
        item(projects(), "Reports").locator("[part=toggle]").click();
        assertThat(item(projects(), "Report 1")).isVisible();

        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Restore projects")).click();

        assertThat(projects().getByRole(AriaRole.TREEITEM))
                .hasText(new String[] { "Reports", "Report 1", "Archive", "Drafts" });
    }

    @Test
    void thePageHasNoAccessibilityViolations() {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Expand all")).click();
        assertThat(item(folders(), "Holidays")).isVisible();
        item(folders(), "2026").click();
        assertOnSelectionPath("Letters");
        item(folders(), "Letters").locator("[part=label]").hover();
        assertThat(tooltip()).isVisible();
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Remove all projects")).click();
        assertThat(projects().getByText("No items")).isVisible();

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

    /** Tabs from the button just before the folders tree into it, once its items are there. */
    private void tabIntoFolders() {
        assertThat(item(folders(), "Documents")).isVisible();
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Clear selection")).focus();
        page.keyboard().press("Tab");
    }

    private static String style(Locator item, String property) {
        return (String) item.evaluate("(item, property) => getComputedStyle(item).getPropertyValue(property)", property);
    }

    private Locator status() {
        return page.getByRole(AriaRole.STATUS).first();
    }

    /** The tooltip of the folders, hidden from assistive technology as a tree only holds treeitems. */
    private Locator tooltip() {
        return folders().locator("[role=tooltip]");
    }

    private Locator projects() {
        return page.getByRole(AriaRole.TREE, new Page.GetByRoleOptions().setName("Projects"));
    }

    private Locator projectsStatus() {
        return page.locator("section").filter(new Locator.FilterOptions().setHas(projects())).getByRole(AriaRole.STATUS);
    }

    private Locator folders() {
        return page.getByRole(AriaRole.TREE, new Page.GetByRoleOptions().setName("Folders"));
    }

    private static Locator item(Locator tree, String label) {
        return tree.getByRole(AriaRole.TREEITEM, new Locator.GetByRoleOptions().setName(label).setExact(true));
    }
}
