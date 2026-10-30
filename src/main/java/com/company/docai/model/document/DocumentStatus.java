package com.company.docai.model.document;

import io.jmix.core.metamodel.datatype.EnumClass;
import org.jspecify.annotations.Nullable;

/**
 * The status of a document. A new document is a draft; an active document can go to the archive
 * and back, and an archived one keeps its name, date and versions.
 */
public enum DocumentStatus implements EnumClass<String> {

    DRAFT("DRAFT"),
    ACTIVE("ACTIVE"),
    ARCHIVED("ARCHIVED");

    private final String id;

    DocumentStatus(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    /**
     * Tells whether a document in this status may move to the target one.
     */
    public boolean canTransitionTo(DocumentStatus target) {
        return switch (this) {
            case DRAFT -> target == ACTIVE;
            case ACTIVE -> target == ARCHIVED;
            case ARCHIVED -> target == ACTIVE;
        };
    }

    @Nullable
    public static DocumentStatus fromId(String id) {
        for (DocumentStatus status : DocumentStatus.values()) {
            if (status.getId().equals(id)) {
                return status;
            }
        }

        return null;
    }
}
