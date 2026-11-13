package com.company.docai.app.service.dashboard;

import com.company.docai.model.document.Document;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The documents without a type: how many the user may open, and the latest of them.
 *
 * @param count  the number of the documents without a type
 * @param latest the documents uploaded last, the latest first
 */
public record UntypedDocuments(long count, List<Row> latest) {

    /**
     * A document without a type.
     *
     * @param document the document
     * @param folder   the name of the folder of the document
     * @param uploaded when the document was uploaded
     * @param fillable whether its card offers Fill with AI while the AI is on: the user may edit the document, its
     *                 status does not lock it and it has a file
     */
    public record Row(Document document, String folder, OffsetDateTime uploaded, boolean fillable) {
    }
}
