package com.company.docai.ai.classification;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * What a proposal changed in a document.
 *
 * @param typeName the name of the type the document got, or null when the proposal named no type of the catalog
 *                 and the type, the fields and the date stayed as they were
 * @param filled   the names of the fields that got values, the date of the document included
 * @param skipped  the fields whose values did not fit them, each with the value the model wrote
 * @param tags     the tags the document got, in the order of the proposal
 */
public record ClassificationResult(@Nullable String typeName, List<String> filled, List<String> skipped,
                                   List<AddedTag> tags) {

    /**
     * A tag the document got.
     *
     * @param name   the name of the tag
     * @param newTag whether the tag is new: it joins the vocabulary when the document is saved
     */
    public record AddedTag(String name, boolean newTag) {
    }
}
