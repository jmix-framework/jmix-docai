package com.company.docai.ai.classification;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * What the model proposes for a document.
 *
 * @param type         the name of a document type, or {@link #UNKNOWN} when no type fits
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

    public ClassificationProposal {
        fields = fields == null ? List.of() : fields;
        tags = tags == null ? List.of() : tags;
    }

    /**
     * The value of one field of the type.
     */
    public record Field(String code, String value) {
    }
}
