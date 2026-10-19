package org.vaadin.addons.tree.demo;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.server.VaadinRequest;

/**
 * Switches the demo between the {@link DemoTheme}s without reloading the page.
 * The new stylesheet replaces the old one once it has loaded, so the page never
 * shows unstyled, and the choice goes in a cookie for the next visit.
 */
public class ThemeSwitcher extends Select<DemoTheme> {

    private static final String APPLY_THEME = """
            const [href, htmlTheme, name, linkId, cookie] = [$0, $1, $2, $3, $4];
            const old = document.getElementById(linkId);
            const link = document.createElement('link');
            link.rel = 'stylesheet';
            link.href = href;
            const apply = () => {
              if (htmlTheme) {
                document.documentElement.setAttribute('theme', htmlTheme);
              } else {
                document.documentElement.removeAttribute('theme');
              }
              old?.remove();
              link.id = linkId;
            };
            link.onload = apply;
            link.onerror = apply;
            if (old) {
              old.after(link);
            } else {
              document.head.append(link);
            }
            document.cookie = cookie + '=' + name + '; path=/; max-age=31536000; samesite=lax';
            """;

    public ThemeSwitcher() {
        setItems(DemoTheme.values());
        setItemLabelGenerator(DemoTheme::label);
        setLabel("Theme");
        setValue(DemoTheme.of(VaadinRequest.getCurrent()));
        addValueChangeListener(event -> {
            if (event.isFromClient() && event.getValue() != null) {
                apply(event.getValue());
            }
        });
    }

    private static void apply(DemoTheme theme) {
        UI.getCurrent().getPage().executeJs(APPLY_THEME, theme.stylesheet(), theme.htmlTheme(), theme.name(),
                DemoTheme.LINK_ID, DemoTheme.COOKIE);
    }
}
