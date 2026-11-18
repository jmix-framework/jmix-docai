package com.company.docai.app.service.dashboard;

import com.company.docai.model.document.Document;
import com.company.docai.model.user.User;
import org.jspecify.annotations.Nullable;

import java.time.OffsetDateTime;

/**
 * What another person did with a document the user may open.
 *
 * @param number the number of the version; 0 for a comment
 * @param author the person; null for a user deleted since, who keeps the username
 */
public record Activity(Kind kind, int number, Document document, String username, @Nullable User author,
                       OffsetDateTime time) {

    /**
     * What a person did with a document.
     */
    public enum Kind {

        /**
         * Uploaded the document: its first version.
         */
        UPLOAD,

        /**
         * Uploaded a new version of the document; a restored version is a new one too.
         */
        VERSION,

        /**
         * Commented on the document.
         */
        COMMENT
    }
}
