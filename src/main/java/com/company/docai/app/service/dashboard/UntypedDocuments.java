package com.company.docai.app.service.dashboard;

import com.company.docai.model.document.Document;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The documents without a type: how many the user may open, and the latest of them.
 *
 * @param latest the documents uploaded last, the latest first
 */
public record UntypedDocuments(long count, List<Row> latest) {

    /**
     * A document without a type.
     *
     * @param folder   the name of the folder of the document
     * @param fillable whether its card offers Fill with AI while the AI is on
     */
    public record Row(Document document, String folder, OffsetDateTime uploaded, boolean fillable) {
    }
}
