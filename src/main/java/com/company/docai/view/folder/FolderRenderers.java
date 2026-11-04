package com.company.docai.view.folder;

import com.company.docai.model.folder.Folder;
import com.vaadin.flow.component.HasComponents;
import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.html.NativeLabel;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.treegrid.HierarchyColumnComponentRenderer;
import com.vaadin.flow.data.renderer.Renderer;
import io.jmix.core.Messages;
import io.jmix.core.MetadataTools;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.component.grid.TreeDataGrid;
import io.jmix.flowui.icon.Icons;
import io.jmix.flowui.kit.icon.JmixFontIcon;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * Renderers of folders in grids, and the key that marks a folder or a document with its own access.
 * In the name column of a folder tree the icon and the name are a label inside a span that selects
 * the folder: the tree toggle expands a folder on any click inside it except a click on a label, so
 * a click on the name selects, and only the chevron expands or collapses. A greyed folder is only
 * the way to folders and documents the user has access to.
 */
@Component
public class FolderRenderers {

    private final UiComponents uiComponents;
    private final MetadataTools metadataTools;
    private final Icons icons;
    private final Messages messages;

    public FolderRenderers(UiComponents uiComponents, MetadataTools metadataTools, Icons icons, Messages messages) {
        this.uiComponents = uiComponents;
        this.metadataTools = metadataTools;
        this.icons = icons;
        this.messages = messages;
    }

    /**
     * The renderer of the name column for the tree it is set on.
     */
    public Renderer<Folder> treeNameRenderer(TreeDataGrid<Folder> tree, Predicate<Folder> greyed) {
        return new HierarchyColumnComponentRenderer<>(
                folder -> name(tree, folder, greyed.test(folder), false, null), tree);
    }

    /**
     * The renderer of the name column that also shows the number of documents after each name and a key
     * at the folders with their own access that the user manages.
     */
    public Renderer<Folder> treeNameRenderer(TreeDataGrid<Folder> tree, Predicate<Folder> greyed,
                                             Predicate<Folder> ownAccess, ToLongFunction<Folder> documentCount) {
        return new HierarchyColumnComponentRenderer<>(folder -> name(tree, folder, greyed.test(folder),
                ownAccess.test(folder), documentCount.applyAsLong(folder)), tree);
    }

    /**
     * Adds the key that marks an item with its own access to the label of the item's name. What is inside
     * the label takes no pointer events, so the label carries the hint.
     */
    public void markOwnAccess(HasComponents label) {
        String ownAccess = messages.getMessage(getClass(), "ownAccess.title");
        Span key = uiComponents.create(Span.class);

        key.addClassName("own-access");
        key.getElement().setAttribute("role", "img");
        key.getElement().setAttribute("aria-label", ownAccess);
        key.add(icons.get(JmixFontIcon.KEY));

        label.add(key);
        label.getElement().setAttribute("title", ownAccess);
    }

    private Span name(TreeDataGrid<Folder> tree, Folder folder, boolean greyed, boolean ownAccess,
                      @Nullable Long documentCount) {
        Span text = uiComponents.create(Span.class);
        text.add(new Text(metadataTools.getInstanceName(folder)));

        if (documentCount != null) {
            Span count = uiComponents.create(Span.class);
            count.addClassName("folder-document-count");
            count.setText(" (" + documentCount + ")");
            text.add(count);
        }

        NativeLabel label = uiComponents.create(NativeLabel.class);
        label.addClassName("name-with-icon");
        label.add(icons.get(JmixFontIcon.FOLDER), text);

        if (greyed) {
            label.addClassName("folder-greyed");
        }

        if (ownAccess) {
            markOwnAccess(label);
        }

        Span name = uiComponents.create(Span.class);
        name.add(label);
        name.setTitle(metadataTools.getInstanceName(folder));
        name.addClickListener(click -> tree.select(folder));

        return name;
    }
}
