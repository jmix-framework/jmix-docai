package com.company.dms.search;

import com.company.dms.model.document.Document;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * A found document.
 *
 * @param document   the document with its name, status, type and folder
 * @param folderPath the names of the folders from the root down to the folder of the document, as the folder tree
 *                   shows them
 * @param field      the caption of the field the snippet comes from
 * @param snippet    the matched text with the matched words in {@code <b>} tags; it is text of the users,
 *                   to be shown as text
 */
public record DocumentSearchHit(Document document,
                                List<String> folderPath,
                                @Nullable String field,
                                @Nullable String snippet) {
}
