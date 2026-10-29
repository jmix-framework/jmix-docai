package com.company.dms.ai.classification;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * What the model proposes for a document: one type of the catalog, the values it found in the text and the tags
 * that tell what the document is about.
 *
 * @param type         the name of a document type, or {@link #UNKNOWN} when no type fits
 * @param fields       the values of the fields of the type, as the model wrote them
 * @param documentDate the date the document was issued or signed, as {@code yyyy-MM-dd}
 * @param tags         the names of tags of the vocabulary, and at most one new name when none of them fits
 */
public record ClassificationProposal(String type,
                                     List<Field> fields,
                                     // the schema OpenAI holds the model to must require every property
                                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
                                     @Nullable String documentDate,
                                     List<String> tags) {

    /**
     * The type the model gives when no type of the catalog fits.
     */
    public static final String UNKNOWN = "UNKNOWN";

    // the model may leave the lists out
    public ClassificationProposal {
        fields = fields == null ? List.of() : fields;
        tags = tags == null ? List.of() : tags;
    }

    /**
     * The value of one field of the type.
     *
     * @param code  the code of the field
     * @param value the value as the model wrote it
     */
    public record Field(String code, String value) {
    }
}
