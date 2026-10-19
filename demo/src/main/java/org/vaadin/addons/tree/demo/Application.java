package org.vaadin.addons.tree.demo;

import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Inline;
import com.vaadin.flow.server.AppShellSettings;
import com.vaadin.flow.server.PWA;

/** The entry point of the Spring Boot application. */
@SpringBootApplication
@PWA(name = "Tree for Vaadin Demo", shortName = "Tree")
public class Application implements AppShellConfigurator {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    /** Loads the theme the visitor chose last, which {@link ThemeSwitcher} can change. */
    @Override
    public void configurePage(AppShellSettings settings) {
        DemoTheme theme = DemoTheme.of(settings.getRequest());
        settings.addLink(theme.stylesheet(), Map.of("rel", "stylesheet", "id", DemoTheme.LINK_ID));
        if (!theme.htmlTheme().isEmpty()) {
            settings.addInlineWithContents(
                    "document.documentElement.setAttribute('theme', '" + theme.htmlTheme() + "');",
                    Inline.Wrapping.JAVASCRIPT);
        }
    }
}
