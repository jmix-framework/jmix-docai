package com.company.docai.view.folder;

import com.company.docai.model.folder.Folder;
import com.vaadin.flow.component.HasComponents;
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

import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

/**
 * Renderers of folders in grids, the path of a folder in one line, and the key that marks an item with its own access.
 * In a folder tree the name is a label, since the tree toggle expands a folder on any click inside it except a click
 * on a label: a click on the name selects, and only the chevron expands.
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
     * The renderer of the name column with the number of documents and the key of an own access the user manages.
     */
    public Renderer<Folder> treeNameRenderer(TreeDataGrid<Folder> tree, Predicate<Folder> greyed,
                                             Predicate<Folder> ownAccess, ToLongFunction<Folder> documentCount) {
        return new HierarchyColumnComponentRenderer<>(folder -> name(tree, folder, greyed.test(folder),
                ownAccess.test(folder), documentCount.applyAsLong(folder)), tree);
    }

    /**
     * Returns the path in one line: the names of the folders from the top down, separated by slashes.
     */
    public String pathText(List<Folder> path) {
        return path.stream()
                .map(metadataTools::getInstanceName)
                .collect(Collectors.joining(" / "));
    }

    /**
     * Adds the key of an own access to the label of the item's name, which carries the hint as its content takes no
     * pointer events; a hint the label has already comes first.
     */
    public void markOwnAccess(HasComponents label) {
        String ownAccess = messages.getMessage(getClass(), "ownAccess.title");
        Span key = uiComponents.create(Span.class);

        key.addClassName("own-access");
        key.getElement().setAttribute("role", "img");
        key.getElement().setAttribute("aria-label", ownAccess);
        key.add(icons.get(JmixFontIcon.KEY));

        label.add(key);

        String hint = label.getElement().getAttribute("title");
        label.getElement().setAttribute("title", hint == null ? ownAccess : hint + "\n" + ownAccess);
    }

    private Span name(TreeDataGrid<Folder> tree, Folder folder, boolean greyed, boolean ownAccess,
                      @Nullable Long documentCount) {
        String folderName = metadataTools.getInstanceName(folder);
        Span text = uiComponents.create(Span.class);
        text.addClassName("name-text");
        text.setText(folderName);

        NativeLabel label = uiComponents.create(NativeLabel.class);
        label.addClassName("name-with-icon");
        label.add(icons.get(JmixFontIcon.FOLDER), text);
        // a long name ends with an ellipsis before its number, so the hint names the folder in full
        label.getElement().setAttribute("title", folderName);

        if (documentCount != null) {
            Span count = uiComponents.create(Span.class);
            count.addClassName("folder-document-count");
            count.setText(" (" + documentCount + ")");
            label.add(count);
        }

        if (greyed) {
            label.addClassName("folder-greyed");
        }

        if (ownAccess) {
            markOwnAccess(label);
        }

        Span name = uiComponents.create(Span.class);
        name.addClassName("folder-tree-name");
        name.add(label);
        name.addClickListener(click -> tree.select(folder));

        return name;
    }
}
