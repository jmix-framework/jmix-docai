package com.company.docai.view.document;

import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Set;

/**
 * How the preview shows a file of the given content type. Only the listed types are shown:
 * SVG and HTML can carry scripts, so they are never rendered.
 */
public enum PreviewType {

    IMAGE,
    PDF,
    TEXT,
    NONE;

    private static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/gif", "image/webp");
    private static final Set<String> TEXT_TYPES = Set.of("application/json", "application/xml");

    /**
     * The kind for the content type; parameters such as {@code charset} are ignored. A missing or unlisted
     * type gets {@link #NONE}.
     */
    public static PreviewType of(@Nullable String contentType) {
        if (contentType == null) {
            return NONE;
        }

        String type = contentType.split(";", 2)[0]
                .strip()
                .toLowerCase(Locale.ROOT);

        if (IMAGE_TYPES.contains(type)) {
            return IMAGE;
        }

        if (type.equals("application/pdf")) {
            return PDF;
        }

        if (type.startsWith("text/") || TEXT_TYPES.contains(type)) {
            return TEXT;
        }

        return NONE;
    }
}
