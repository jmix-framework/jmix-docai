package com.company.docai.app.service.tag;

/**
 * A tag with the number of the live documents with it that the user may see.
 *
 * @param name      the name of the tag
 * @param documents the number of the documents
 */
public record TagCount(String name, long documents) {
}
