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
 * The name filter of the folder trees and of the search palette; a tree keeps the folders above a match.
 */
@Component
public class FolderFilter {

    private final MetadataTools metadataTools;

    public FolderFilter(MetadataTools metadataTools) {
        this.metadataTools = metadataTools;
    }

    /**
     * Shows in the tree the folders whose shown name contains the text, with their parents, all open.
     *
     * @return whether the text filters the folders
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
     * Returns the folders whose shown name contains the text, ignoring case, with their parents among the given ones.
     */
    public List<Folder> filterByName(Collection<Folder> folders, @Nullable String text) {
        if (text == null || text.isBlank()) {
            return List.copyOf(folders);
        }

        String part = text.strip().toLowerCase(Locale.ROOT);
        FolderIndex index = FolderIndex.of(folders);

        Set<Folder> shown = new HashSet<>();

        for (Folder folder : folders) {
            if (matches(folder, part)) {
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

    /**
     * Returns at most {@code max} folders whose shown name contains the text, ignoring case, without their parents.
     */
    public List<Folder> findByName(Collection<Folder> folders, @Nullable String text, int max) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        String part = text.strip().toLowerCase(Locale.ROOT);

        return folders.stream()
                .filter(folder -> matches(folder, part))
                .limit(max)
                .toList();
    }

    // the tree and the search palette compare the same way: the shown name, whatever its case
    private boolean matches(Folder folder, String part) {
        return metadataTools.getInstanceName(folder).toLowerCase(Locale.ROOT).contains(part);
    }
}
