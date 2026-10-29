package com.company.dms.app.service.document;

import com.company.dms.app.service.OperationRejectedException;
import com.company.dms.app.service.access.AccessChecker;
import com.company.dms.model.document.Document;
import com.company.dms.model.document.DocumentComment;
import com.company.dms.model.user.User;
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
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Writes the comments people leave on documents. Everyone who sees a document comments on it, the author changes and
 * deletes their own comment, and the administrator deletes any. A comment is saved at once and changes nothing in its
 * document. The access rules of the comments check the same again on every save.
 */
@Service
public class DocumentCommentService {

    private final DataManager dataManager;
    private final AccessChecker accessChecker;
    private final CurrentAuthentication currentAuthentication;
    private final TimeSource timeSource;
    private final MetadataTools metadataTools;
    private final Messages messages;

    public DocumentCommentService(DataManager dataManager, AccessChecker accessChecker,
                                  CurrentAuthentication currentAuthentication, TimeSource timeSource,
                                  MetadataTools metadataTools, Messages messages) {
        this.dataManager = dataManager;
        this.accessChecker = accessChecker;
        this.currentAuthentication = currentAuthentication;
        this.timeSource = timeSource;
        this.metadataTools = metadataTools;
        this.messages = messages;
    }

    /**
     * Leaves a comment on the document as the current user. The text is saved without the spaces at its edges.
     *
     * @throws OperationRejectedException if the text is empty or too long, or the document is deleted or hidden
     *                                    from the user
     */
    public DocumentComment post(Document document, @Nullable String text) {
        String content = checkText(text);
        // read again, so that a document deleted or hidden since the card opened is refused and the comment refers
        // to a live row
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

        // the administrator deletes any comment but changes only their own
        if (!isMine(stored)) {
            throw reject("commentEditNotYours");
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
     * Returns the users who wrote the comments, by username. A user deleted since is left out, and their comments
     * keep the username.
     */
    public Map<String, User> authorsOf(Collection<DocumentComment> comments) {
        Set<String> usernames = comments.stream()
                .map(DocumentComment::getCreatedBy)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (usernames.isEmpty()) {
            return Map.of();
        }

        return dataManager.load(User.class)
                .query("select u from User u where u.username in :usernames")
                .parameter("usernames", usernames)
                .fetchPlan(FetchPlan.INSTANCE_NAME)
                .list()
                .stream()
                .collect(Collectors.toMap(User::getUsername, Function.identity()));
    }

    // a comment deleted since, or one of a document hidden from the user or lying in the trash, is not found
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
