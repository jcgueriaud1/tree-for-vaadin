package org.vaadin.addons.tree.demo;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;

import jakarta.servlet.http.Cookie;

import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.theme.aura.Aura;
import com.vaadin.flow.theme.lumo.Lumo;

/**
 * The themes the demo can show, to see the tree in each. A theme is one
 * stylesheet, plus the {@code theme} attribute of the {@code <html>} element for
 * Lumo's dark variant. The choice is kept in a cookie so that the app shell
 * loads the same theme on the next visit; a {@code ?theme=} parameter, such as
 * {@code ?theme=LUMO_DARK}, overrides it.
 */
public enum DemoTheme {
    AURA("Aura", Aura.STYLESHEET, ""),
    LUMO_LIGHT("Lumo light", Lumo.STYLESHEET, ""),
    LUMO_DARK("Lumo dark", Lumo.STYLESHEET, Lumo.DARK);

    public static final DemoTheme DEFAULT = AURA;

    /** The id of the {@code <link>} element that loads the theme's stylesheet. */
    public static final String LINK_ID = "demo-theme";

    static final String COOKIE = "tree-demo-theme";
    static final String PARAMETER = "theme";

    private final String label;
    private final String stylesheet;
    private final String htmlTheme;

    DemoTheme(String label, String stylesheet, String htmlTheme) {
        this.label = label;
        this.stylesheet = stylesheet;
        this.htmlTheme = htmlTheme;
    }

    public String label() {
        return label;
    }

    public String stylesheet() {
        return stylesheet;
    }

    /** The value of the {@code theme} attribute of {@code <html>}; empty for none. */
    public String htmlTheme() {
        return htmlTheme;
    }

    /** The theme the request asks for, in its parameter or else its cookie, or the default one. */
    public static DemoTheme of(VaadinRequest request) {
        if (request == null) {
            return DEFAULT;
        }
        Stream<String> fromCookie = Optional.ofNullable(request.getCookies()).stream()
                .flatMap(Arrays::stream)
                .filter(cookie -> COOKIE.equals(cookie.getName()))
                .map(Cookie::getValue);
        return Stream.concat(Stream.ofNullable(request.getParameter(PARAMETER)), fromCookie)
                .map(DemoTheme::named)
                .flatMap(Optional::stream)
                .findFirst()
                .orElse(DEFAULT);
    }

    private static Optional<DemoTheme> named(String name) {
        return Arrays.stream(values()).filter(theme -> theme.name().equals(name)).findFirst();
    }
}
