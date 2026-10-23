package com.company.dms.view.folder;

import com.company.dms.model.folder.Folder;
import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.html.NativeLabel;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.treegrid.HierarchyColumnComponentRenderer;
import com.vaadin.flow.data.renderer.Renderer;
import io.jmix.core.MetadataTools;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.component.grid.TreeDataGrid;
import io.jmix.flowui.icon.Icons;
import io.jmix.flowui.kit.icon.JmixFontIcon;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.function.ToLongFunction;

/**
 * Renderers of folders in grids. In the name column of a folder tree the icon and the name are a label
 * inside a span that selects the folder: the tree toggle expands a folder on any click inside it except
 * a click on a label, so a click on the name selects, and only the chevron expands or collapses.
 */
@Component
public class FolderRenderers {

    private final UiComponents uiComponents;
    private final MetadataTools metadataTools;
    private final Icons icons;

    public FolderRenderers(UiComponents uiComponents, MetadataTools metadataTools, Icons icons) {
        this.uiComponents = uiComponents;
        this.metadataTools = metadataTools;
        this.icons = icons;
    }

    /**
     * The renderer of the name column for the tree it is set on.
     */
    public Renderer<Folder> treeNameRenderer(TreeDataGrid<Folder> tree) {
        return new HierarchyColumnComponentRenderer<>(folder -> name(tree, folder, null), tree);
    }

    /**
     * The renderer of the name column that also shows the number of documents after each name.
     */
    public Renderer<Folder> treeNameRenderer(TreeDataGrid<Folder> tree, ToLongFunction<Folder> documentCount) {
        return new HierarchyColumnComponentRenderer<>(
                folder -> name(tree, folder, documentCount.applyAsLong(folder)), tree);
    }

    private Span name(TreeDataGrid<Folder> tree, Folder folder, @Nullable Long documentCount) {
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

        Span name = uiComponents.create(Span.class);
        name.add(label);
        name.addClickListener(click -> tree.select(folder));

        return name;
    }
}
