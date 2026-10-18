package org.vaadin.addons.tree.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.server.PWA;
import com.vaadin.flow.theme.aura.Aura;

/** The entry point of the Spring Boot application. */
@SpringBootApplication
@PWA(name = "Tree for Vaadin Demo", shortName = "Tree")
@StyleSheet(Aura.STYLESHEET)
public class Application implements AppShellConfigurator {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
