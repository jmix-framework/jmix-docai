package com.company.docai.search;

import com.company.docai.model.document.Document;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
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

    private static final String MATCH_START = "<b>";
    private static final String MATCH_END = "</b>";

    /**
     * Splits a marked text into its parts in their order: the matched words, which stand between {@code <b>} and
     * {@code </b>}, and the plain text around them. A mark left open stays plain text, and no part is empty. The text
     * comes from the users, so the parts are shown as text, never as HTML.
     */
    public static List<Part> partsOf(String marked) {
        List<Part> parts = new ArrayList<>();
        int position = 0;

        while (position < marked.length()) {
            int start = marked.indexOf(MATCH_START, position);
            int end = start < 0 ? -1 : marked.indexOf(MATCH_END, start);

            if (end < 0) {
                addPart(parts, marked.substring(position), false);
                break;
            }

            addPart(parts, marked.substring(position, start), false);
            addPart(parts, marked.substring(start + MATCH_START.length(), end), true);
            position = end + MATCH_END.length();
        }

        return parts;
    }

    private static void addPart(List<Part> parts, String text, boolean matched) {
        if (!text.isEmpty()) {
            parts.add(new Part(text, matched));
        }
    }

    /**
     * A part of a marked text.
     *
     * @param text    the text, to be shown as text
     * @param matched whether the search matched it
     */
    public record Part(String text, boolean matched) {
    }
}
