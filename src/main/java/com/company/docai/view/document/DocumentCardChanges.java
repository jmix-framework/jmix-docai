package com.company.docai.view.document;

import com.company.docai.app.service.document.NewVersion;
import com.company.docai.app.service.document.UploadedFile;
import com.company.docai.model.document.DocumentRevision;
import com.company.docai.model.document.DocumentStatus;
import io.jmix.flowui.upload.TemporaryStorage;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * The changes of the document card that apply only on OK: a new version, from an uploaded
 * file or from an earlier revision, and a status transition. Owns the temporary file.
 */
public class DocumentCardChanges {

    private final TemporaryStorage temporaryStorage;

    @Nullable
    private UUID temporaryFileId;

    @Nullable
    private NewVersion newVersion;

    @Nullable
    private DocumentStatus targetStatus;

    public DocumentCardChanges(TemporaryStorage temporaryStorage) {
        this.temporaryStorage = temporaryStorage;
    }

    /**
     * Stages an uploaded file as the new version instead of the version staged before.
     */
    public void uploadVersion(UUID temporaryFileId, UploadedFile file) {
        deleteTemporaryFile();
        this.temporaryFileId = temporaryFileId;
        newVersion = new NewVersion.FromFile(file);
    }

    /**
     * Stages a copy of an earlier revision as the new version instead of the version staged before.
     */
    public void restoreVersion(DocumentRevision revision) {
        deleteTemporaryFile();
        newVersion = new NewVersion.FromRevision(revision);
    }

    /**
     * Stages a status transition, or cancels it when the status is null.
     */
    public void changeStatus(@Nullable DocumentStatus targetStatus) {
        this.targetStatus = targetStatus;
    }

    @Nullable
    public NewVersion getNewVersion() {
        return newVersion;
    }

    /**
     * The staged file when the new version is an upload, otherwise null.
     */
    @Nullable
    public UploadedFile getUploadedFile() {
        return newVersion instanceof NewVersion.FromFile(UploadedFile file) ? file : null;
    }

    @Nullable
    public DocumentStatus getTargetStatus() {
        return targetStatus;
    }

    /**
     * Drops the staged changes and deletes the temporary file.
     */
    public void clear() {
        deleteTemporaryFile();
        newVersion = null;
        targetStatus = null;
    }

    private void deleteTemporaryFile() {
        if (temporaryFileId != null) {
            temporaryStorage.deleteFile(temporaryFileId);
            temporaryFileId = null;
        }
    }
}
