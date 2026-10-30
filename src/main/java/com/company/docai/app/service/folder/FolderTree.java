package com.company.docai.app.service.folder;

import com.company.docai.app.service.access.AccessLevels;
import com.company.docai.model.folder.Folder;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The folder tree as the current user sees it: the folders they have access to, and greyed, the folders on the way
 * to the folders and documents they have access to. The root is always shown.
 *
 * @param folders the shown folders, by name
 * @param greyed  the ids of the shown folders the user has no access to
 * @param levels  the levels of the user, by access set
 */
public record FolderTree(List<Folder> folders, Set<UUID> greyed, AccessLevels levels) {
}
