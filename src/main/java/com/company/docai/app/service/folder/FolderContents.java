package com.company.docai.app.service.folder;

/**
 * What deleting a folder takes with it: its live subfolders at any depth and the live documents in all of them.
 */
public record FolderContents(long subfolders, long documents) {
}
