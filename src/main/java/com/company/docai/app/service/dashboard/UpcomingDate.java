package com.company.docai.app.service.dashboard;

import com.company.docai.model.document.Document;

import java.time.LocalDate;

/**
 * A date of a field of the type of a document, today or later.
 *
 * @param document the document
 * @param folder   the name of the folder of the document
 * @param field    the name of the field in the language of the user
 * @param date     the date the field holds
 * @param soon     whether the date comes within a week
 */
public record UpcomingDate(Document document, String folder, String field, LocalDate date, boolean soon) {
}
