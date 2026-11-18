package com.company.docai.app.service.tag;

/**
 * A tag with the number of the live documents with it that the user may see.
 */
public record TagCount(String name, long documents) {
}
