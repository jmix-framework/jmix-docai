package com.company.dms.app.service.folder;

/**
 * What deleting a folder takes with it: its live subfolders at any depth and the live
 * documents in the folder and in those subfolders.
 */
public record FolderContents(long subfolders, long documents) {
}
