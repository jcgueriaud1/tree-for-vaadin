package org.vaadin.addons.tree.demo;

import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Main;
import com.vaadin.flow.data.provider.hierarchy.TreeData;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import org.vaadin.addons.tree.Tree;

/** A tree of folders, kept in memory. */
@Route("")
@PageTitle("Tree for Vaadin")
public class TreeView extends Main {

    /** A folder of the demo's file system. */
    public record Folder(String name) {
    }

    public TreeView() {
        Tree<Folder> tree = new Tree<>();
        tree.setItems(folders());
        tree.setItemLabelGenerator(Folder::name);
        tree.setAriaLabel("Folders");
        tree.setWidth("20em");

        add(new H1("Tree for Vaadin"), tree);
    }

    static TreeData<Folder> folders() {
        Folder documents = new Folder("Documents");
        Folder music = new Folder("Music");
        Folder pictures = new Folder("Pictures");
        TreeData<Folder> data = new TreeData<>();
        data.addRootItems(documents, music, pictures);
        data.addItems(documents, new Folder("Invoices"), new Folder("Letters"));
        data.addItems(music, new Folder("Jazz"), new Folder("Classical"));
        data.addItems(pictures, new Folder("Holidays"));
        return data;
    }
}
