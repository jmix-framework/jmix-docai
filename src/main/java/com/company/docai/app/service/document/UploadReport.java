package com.company.docai.app.service.document;

import com.company.docai.model.document.Document;

import java.util.List;

/**
 * What a batch upload did: the documents it created and the files it skipped.
 */
public record UploadReport(List<Document> created, List<SkippedFile> skipped) {

    /**
     * A file the upload skipped, with the reason in the user's language.
     */
    public record SkippedFile(String fileName, String reason) {
    }
}
