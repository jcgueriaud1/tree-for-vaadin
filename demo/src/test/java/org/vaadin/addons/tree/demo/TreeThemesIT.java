package org.vaadin.addons.tree.demo;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;

/**
 * The tree in each theme of the demo: its selected item, focus ring and selection
 * path must stand out enough (WCAG 1.4.3 and 1.4.11). Screenshots of each theme
 * go to target/screenshots.
 */
class TreeThemesIT {

    private static final String URL = System.getProperty("demo.url", "http://localhost:8080/");

    /**
     * Measures an item as the eye sees it: each color is resolved to sRGB through a
     * canvas and laid over what is behind it, then compared as WCAG does. Gives the
     * contrast of the text, of the item's fill with what is behind the item, of the
     * focus ring and of the selection path marker with the item.
     */
    private static final String CONTRASTS = """
            item => {
              const canvas = document.createElement('canvas');
              canvas.width = canvas.height = 1;
              const context = canvas.getContext('2d', { willReadFrequently: true });
              const rgba = (color) => {
                context.clearRect(0, 0, 1, 1);
                context.fillStyle = color;
                context.fillRect(0, 0, 1, 1);
                const [r, g, b, a] = context.getImageData(0, 0, 1, 1).data;
                return [r, g, b, a / 255];
              };
              const over = (top, bottom) => {
                const alpha = top[3] + bottom[3] * (1 - top[3]);
                return [0, 1, 2].map((i) => (top[i] * top[3] + bottom[i] * bottom[3] * (1 - top[3])) / alpha)
                    .concat(alpha);
              };
              const parent = (element) => element.parentElement
                  ?? (element.getRootNode() instanceof ShadowRoot ? element.getRootNode().host : null);
              const backdrop = (element) => {
                const layers = [];
                for (let e = element; e; e = parent(e)) {
                  layers.push(rgba(getComputedStyle(e).backgroundColor));
                }
                return layers.reduceRight((color, layer) => over(layer, color), [255, 255, 255, 1]);
              };
              const luminance = (color) => {
                const linear = (v) => (v /= 255) <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
                return 0.2126 * linear(color[0]) + 0.7152 * linear(color[1]) + 0.0722 * linear(color[2]);
              };
              const contrast = (a, b) => {
                const [light, dark] = [luminance(a), luminance(b)].sort((x, y) => y - x);
                return (light + 0.05) / (dark + 0.05);
              };
              const style = getComputedStyle(item);
              const fill = backdrop(item);
              const marker = item.querySelector('[part="selection-path-marker"]');
              return {
                text: contrast(over(rgba(style.color), fill), fill),
                fill: contrast(fill, backdrop(parent(item))),
                ring: style.outlineStyle === 'none' ? 0 : contrast(over(rgba(style.outlineColor), fill), fill),
                marker: marker ? contrast(over(rgba(getComputedStyle(marker).backgroundColor), fill), fill) : 0,
              };
            }
            """;

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
        page = browser.newPage(new Browser.NewPageOptions().setViewportSize(900, 800));
    }

    @AfterEach
    void closePage() {
        page.close();
    }

    /** The names of the demo's themes (DemoTheme, which this test cannot load from the packaged demo). */
    @ParameterizedTest
    @ValueSource(strings = { "AURA", "LUMO_LIGHT", "LUMO_DARK" })
    void theSelectedItemTheFocusRingAndTheSelectionPathStandOut(String theme) {
        page.navigate(URL + "?theme=" + theme);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Expand all")).click();
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Select 2026")).click();
        Locator selected = item("2026");
        assertThat(selected).hasAttribute("aria-selected", "true");

        // Focus the item above the selected one with the keyboard, away from the mouse
        selected.click();
        page.keyboard().press("ArrowUp");
        page.mouse().move(0, 0);
        Locator focused = item("2025");
        assertThat(focused).hasAttribute("focus-ring", "");
        Map<String, Double> focusedItem = contrasts(focused);
        atLeast(4.5, focusedItem, "text", theme + ": text of an item");
        atLeast(3, focusedItem, "ring", theme + ": focus ring of an item");

        atLeast(3, contrasts(item("Letters")), "marker", theme + ": selection path marker");

        page.keyboard().press("ArrowDown");
        assertThat(selected).hasAttribute("focus-ring", "");
        Map<String, Double> selectedItem = contrasts(selected);
        atLeast(4.5, selectedItem, "text", theme + ": text of the selected item");
        atLeast(3, selectedItem, "fill", theme + ": fill of the selected item");
        atLeast(3, selectedItem, "ring", theme + ": focus ring of the selected item");

        page.locator("section").first().screenshot(new Locator.ScreenshotOptions()
                .setPath(Path.of("target", "screenshots", "tree-" + theme.toLowerCase() + ".png")));
    }

    @Test
    void theThemeSwitcherChangesTheThemeWithoutReloading() {
        page.navigate(URL + "?theme=AURA");
        page.evaluate("window.notReloaded = true");

        page.locator("vaadin-select").click();
        page.getByRole(AriaRole.OPTION, new Page.GetByRoleOptions().setName("Lumo dark")).click();

        assertThat(page.locator("html")).hasAttribute("theme", "dark");
        assertThat(page.locator("link#demo-theme")).hasAttribute("href",
                Pattern.compile("lumo"));
        assertTrue((Boolean) page.evaluate("window.notReloaded === true"), "the page was not reloaded");
    }

    private Locator item(String label) {
        return page.getByRole(AriaRole.TREE, new Page.GetByRoleOptions().setName("Folders"))
                .getByRole(AriaRole.TREEITEM, new Locator.GetByRoleOptions().setName(label).setExact(true));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Double> contrasts(Locator item) {
        Map<String, Number> measured = (Map<String, Number>) item.evaluate(CONTRASTS);
        return Map.of("text", measured.get("text").doubleValue(), "fill", measured.get("fill").doubleValue(),
                "ring", measured.get("ring").doubleValue(), "marker", measured.get("marker").doubleValue());
    }

    private static void atLeast(double minimum, Map<String, Double> contrasts, String key, String what) {
        double ratio = contrasts.get(key);
        assertTrue(ratio >= minimum, what + " has a contrast of " + String.format("%.2f", ratio) + ":1, below "
                + minimum + ":1");
    }
}
