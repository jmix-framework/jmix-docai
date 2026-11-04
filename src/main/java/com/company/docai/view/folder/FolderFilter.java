package com.company.docai.view.folder;

import com.company.docai.app.service.folder.FolderIndex;
import com.company.docai.model.folder.Folder;
import io.jmix.core.MetadataTools;
import io.jmix.flowui.component.grid.TreeDataGrid;
import io.jmix.flowui.model.CollectionContainer;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The name filter of the folder trees, in the main menu and in the folder lookup: a tree shows the folders whose name
 * contains the text of its filter field, together with the folders above them.
 */
@Component
public class FolderFilter {

    private final MetadataTools metadataTools;

    public FolderFilter(MetadataTools metadataTools) {
        this.metadataTools = metadataTools;
    }

    /**
     * Shows in the tree the folders whose shown name contains the text with their parents, all of them open; an
     * empty text shows every folder. Returns whether the text filters the folders.
     */
    public boolean filterTree(TreeDataGrid<Folder> tree, CollectionContainer<Folder> container,
                              Collection<Folder> folders, @Nullable String text) {
        container.setItems(filterByName(folders, text));
        boolean filtered = text != null && !text.isBlank();

        if (filtered) {
            tree.expand(container.getItems());
        }

        return filtered;
    }

    /**
     * Returns the folders whose shown name contains the text, ignoring case, together with their parents among
     * the given folders, in the given order. An empty text keeps every folder.
     */
    public List<Folder> filterByName(Collection<Folder> folders, @Nullable String text) {
        if (text == null || text.isBlank()) {
            return List.copyOf(folders);
        }

        String part = text.strip().toLowerCase(Locale.ROOT);
        FolderIndex index = FolderIndex.of(folders);

        Set<Folder> shown = new HashSet<>();

        for (Folder folder : folders) {
            if (metadataTools.getInstanceName(folder).toLowerCase(Locale.ROOT)
                    .contains(part)) {
                Folder current = folder;

                while (current != null && shown.add(current)) {
                    current = index.parentOf(current);
                }
            }
        }

        return folders.stream()
                .filter(shown::contains)
                .toList();
    }
}
