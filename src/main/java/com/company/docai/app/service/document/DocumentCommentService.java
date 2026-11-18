package com.company.docai.app.service.document;

import com.company.docai.app.service.OperationRejectedException;
import com.company.docai.app.service.access.AccessChecker;
import com.company.docai.app.service.user.UserService;
import com.company.docai.model.document.Document;
import com.company.docai.model.document.DocumentComment;
import com.company.docai.model.user.User;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.Messages;
import io.jmix.core.MetadataTools;
import io.jmix.core.TimeSource;
import io.jmix.core.security.CurrentAuthentication;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;

/**
 * Writes the comments people leave on documents.
 */
@Service
public class DocumentCommentService {

    private final DataManager dataManager;
    private final AccessChecker accessChecker;
    private final UserService userService;
    private final CurrentAuthentication currentAuthentication;
    private final TimeSource timeSource;
    private final MetadataTools metadataTools;
    private final Messages messages;

    public DocumentCommentService(DataManager dataManager, AccessChecker accessChecker, UserService userService,
                                  CurrentAuthentication currentAuthentication, TimeSource timeSource,
                                  MetadataTools metadataTools, Messages messages) {
        this.dataManager = dataManager;
        this.accessChecker = accessChecker;
        this.userService = userService;
        this.currentAuthentication = currentAuthentication;
        this.timeSource = timeSource;
        this.metadataTools = metadataTools;
        this.messages = messages;
    }

    /**
     * Leaves a comment on the document as the current user.
     *
     * @throws OperationRejectedException if the text is empty or too long, or the document is deleted or hidden from
     *                                    the user
     */
    public DocumentComment post(Document document, @Nullable String text) {
        String content = checkText(text);
        // read again, so that a document deleted or hidden since the card opened is refused
        Document live = dataManager.load(Document.class)
                .query("select d from Document d where d.id = :id")
                .parameter("id", document.getId())
                .fetchPlan(FetchPlan.INSTANCE_NAME)
                .optional()
                .orElseThrow(() -> reject("documentUnavailable", metadataTools.getInstanceName(document)));

        DocumentComment comment = dataManager.create(DocumentComment.class);
        comment.setDocument(live);
        comment.setText(content);

        return dataManager.save(comment);
    }

    /**
     * Changes the text of the own comment and marks the comment as changed.
     *
     * @throws OperationRejectedException if the text is empty or too long, the comment is deleted or hidden from
     *                                    the user, or another person wrote it
     */
    public DocumentComment edit(DocumentComment comment, @Nullable String text) {
        String content = checkText(text);
        DocumentComment stored = load(comment);

        if (!isMine(stored)) {
            throw reject("commentEditNotYours");
        }

        if (content.equals(stored.getText())) {
            return stored;
        }

        stored.setText(content);
        stored.setEditedDate(timeSource.now().toOffsetDateTime());

        return dataManager.save(stored);
    }

    /**
     * Deletes the own comment, or any comment for the administrator, for good.
     *
     * @throws OperationRejectedException if the comment is deleted or hidden from the user, or another person wrote
     *                                    it and the user is not the administrator
     */
    public void delete(DocumentComment comment) {
        DocumentComment stored = load(comment);

        if (!canDelete(stored)) {
            throw reject("commentDeleteNotYours");
        }

        dataManager.remove(stored);
    }

    /**
     * Tells whether the current user wrote the comment and so may change it.
     */
    public boolean isMine(DocumentComment comment) {
        return currentAuthentication.getUser().getUsername().equals(comment.getCreatedBy());
    }

    /**
     * Tells whether the current user may delete the comment: the own one, or any for the administrator.
     */
    public boolean canDelete(DocumentComment comment) {
        return isMine(comment) || accessChecker.hasFullAccess();
    }

    /**
     * Returns the users who wrote the comments, by username; a user deleted since is left out.
     */
    public Map<String, User> authorsOf(Collection<DocumentComment> comments) {
        return userService.usersOf(comments.stream()
                .map(DocumentComment::getCreatedBy)
                .toList());
    }

    private DocumentComment load(DocumentComment comment) {
        return dataManager.load(DocumentComment.class)
                .query("select c from DocumentComment c where c.id = :id and c.document.deletedDate is null")
                .parameter("id", comment.getId())
                .optional()
                .orElseThrow(() -> reject("commentUnavailable"));
    }

    private String checkText(@Nullable String text) {
        String content = text == null ? "" : text.strip();

        if (content.isEmpty()) {
            throw reject("commentEmpty");
        }

        if (content.length() > DocumentComment.MAX_TEXT_LENGTH) {
            throw reject("commentTooLong", DocumentComment.MAX_TEXT_LENGTH);
        }

        return content;
    }

    private OperationRejectedException reject(String key, Object... args) {
        return new OperationRejectedException(messages.formatMessage(getClass(), key, args));
    }
}
