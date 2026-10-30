package com.company.docai.app.service.document;

import com.company.docai.model.document.DocumentRevision;

/**
 * The content of a new document version: an uploaded file or a copy of an earlier revision.
 */
public sealed interface NewVersion {

    /**
     * A new version from an uploaded file.
     */
    record FromFile(UploadedFile file) implements NewVersion {
    }

    /**
     * A copy of an earlier revision of the same document, which becomes the current one.
     */
    record FromRevision(DocumentRevision revision) implements NewVersion {
    }
}
