package com.company.docai.app.service.dashboard;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * How many documents the user may open have each type and each status. A type or a status without documents has no
 * row.
 *
 * @param types    the types, the most documents first, and the documents without a type last
 * @param statuses the statuses in the order of their dictionary
 */
public record Summary(List<Row> types, List<Row> statuses) {

    /**
     * A type or a status with the number of its documents.
     *
     * @param name      the name of the type in the language of the user or the name of the status; null for the
     *                  documents without a type
     * @param color     the color of the status, {@code #rrggbb}; null for a type
     * @param documents the number of the documents
     */
    public record Row(@Nullable String name, @Nullable String color, long documents) {
    }
}
