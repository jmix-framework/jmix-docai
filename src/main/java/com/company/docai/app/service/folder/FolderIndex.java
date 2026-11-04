package com.company.docai.app.service.folder;

import com.company.docai.model.folder.Folder;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Folders by id with the ways up the tree among them: the parent of a folder, its path, its depth, and whether it lies
 * below another folder. A folder is looked up by its id, so a copy of it gets the same answers; a folder whose parent
 * is not among them stands at the top, as the root does.
 */
public final class FolderIndex {

    private final Map<UUID, Folder> folders;

    private FolderIndex(Map<UUID, Folder> folders) {
        this.folders = folders;
    }

    /**
     * Indexes the folders, keeping their order. Of two folders with the same id, the first one counts.
     */
    public static FolderIndex of(Collection<Folder> folders) {
        return new FolderIndex(folders.stream()
                .collect(Collectors.toMap(Folder::getId,
                        Function.identity(),
                        (first, second) -> first,
                        LinkedHashMap::new)));
    }

    /**
     * The folders in their order.
     */
    public Collection<Folder> folders() {
        return Collections.unmodifiableCollection(folders.values());
    }

    /**
     * Returns the folder with the id, or null if it is not among the folders.
     */
    public Folder get(UUID id) {
        return folders.get(id);
    }

    /**
     * Tells whether the folder with the id is among the folders.
     */
    public boolean contains(UUID id) {
        return folders.containsKey(id);
    }

    /**
     * Returns the parent of the folder, or null for a folder at the top or not among the folders.
     */
    @Nullable
    public Folder parentOf(Folder folder) {
        Folder indexed = folders.get(folder.getId());

        return indexed == null || indexed.getParent() == null ? null : folders.get(indexed.getParent().getId());
    }

    /**
     * Returns the folders from the top down to the folder, both included; empty for a folder not among the folders.
     */
    public List<Folder> pathOf(Folder folder) {
        LinkedList<Folder> path = new LinkedList<>();

        for (Folder current = folders.get(folder.getId()); current != null; current = parentOf(current)) {
            path.addFirst(current);
        }

        return path;
    }

    /**
     * Returns the number of folders above the folder: 0 at the top.
     */
    public int depthOf(Folder folder) {
        int depth = 0;

        for (Folder current = parentOf(folder); current != null; current = parentOf(current)) {
            depth++;
        }

        return depth;
    }

    /**
     * Tells whether the folder lies below the other one, at any depth; no folder lies below itself.
     */
    public boolean isBelow(Folder folder, Folder top) {
        for (Folder current = parentOf(folder); current != null; current = parentOf(current)) {
            if (current.equals(top)) {
                return true;
            }
        }

        return false;
    }
}
