package com.company.docai.view.document;

import com.company.docai.app.service.document.NewVersion;
import com.company.docai.app.service.document.UploadedFile;
import com.company.docai.model.document.DocumentRevision;
import com.company.docai.model.document.DocumentStatus;
import io.jmix.core.Messages;
import io.jmix.flowui.upload.TemporaryStorage;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The changes of the document card that apply only on OK: a new version, from an uploaded
 * file or from an earlier revision, and a status transition. Owns the temporary file. Each card gets its own
 * instance: the bean is a prototype.
 */
@Component
@Scope(BeanDefinition.SCOPE_PROTOTYPE)
public class DocumentCardChanges {

    private final TemporaryStorage temporaryStorage;
    private final Messages messages;

    @Nullable
    private UUID temporaryFileId;

    @Nullable
    private NewVersion newVersion;

    @Nullable
    private DocumentStatus targetStatus;

    public DocumentCardChanges(TemporaryStorage temporaryStorage, Messages messages) {
        this.temporaryStorage = temporaryStorage;
        this.messages = messages;
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
     * Describes the staged changes in the texts of the card: the new or the restored version, then the target
     * status; an empty text when nothing is staged.
     */
    public String describe() {
        List<String> parts = new ArrayList<>();

        switch (newVersion) {
            case NewVersion.FromFile(UploadedFile file) ->
                    parts.add(messages.formatMessage(getClass(), "pendingNewVersion", file.fileName()));
            case NewVersion.FromRevision(DocumentRevision revision) ->
                    parts.add(messages.formatMessage(getClass(), "pendingRestoredVersion", revision.getNumber()));
            case null -> {
                // do nothing
            }
        }

        if (targetStatus != null) {
            parts.add("→ " + targetStatus.getName());
        }

        return String.join(" · ", parts);
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
