package com.company.dms.ai.classification;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * What a proposal changed in a document.
 *
 * @param typeName the name of the type the document got, or null when the proposal named no type of the catalog
 *                 and the document stayed as it was
 * @param filled   the names of the fields that got values, the date of the document included
 * @param skipped  the fields whose values did not fit them, each with the value the model wrote
 */
public record ClassificationResult(@Nullable String typeName, List<String> filled, List<String> skipped) {
}
