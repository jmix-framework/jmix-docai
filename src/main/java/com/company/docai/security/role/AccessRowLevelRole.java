package com.company.docai.security.role;

import com.company.docai.app.service.access.AccessChecker;
import com.company.docai.model.access.AccessEntry;
import com.company.docai.model.access.AccessLevel;
import com.company.docai.model.access.AccessSet;
import com.company.docai.model.discussion.DiscussedDocument;
import com.company.docai.model.document.Document;
import com.company.docai.model.document.DocumentComment;
import com.company.docai.model.document.DocumentRevision;
import com.company.docai.model.favorite.Favorite;
import com.company.docai.model.folder.Folder;
import com.company.docai.security.AccessRoleAssignmentProvider;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.core.usersubstitution.CurrentUserSubstitution;
import io.jmix.security.model.RowLevelBiPredicate;
import io.jmix.security.model.RowLevelPolicyAction;
import io.jmix.security.role.annotation.JpqlRowLevelPolicy;
import io.jmix.security.role.annotation.PredicateRowLevelPolicy;
import io.jmix.security.role.annotation.RowLevelRole;
import org.springframework.context.ApplicationContext;

/**
 * Applies the access entries to everything loaded and saved through {@code DataManager}: a user sees a folder,
 * a document and its versions with an entry of any level in their access set, and changes them with
 * {@link AccessLevel#EDIT}. {@link AccessRoleAssignmentProvider} assigns the role to everyone without
 * {@link FullAccessRole}. Saves are checked by {@link AccessChecker}, the Java form of the same rule. Folder
 * references of loaded documents stay: there is no read predicate to erase them.
 */
@RowLevelRole(name = "msg://AccessRowLevelRole.name", code = AccessRowLevelRole.CODE,
        description = "msg://AccessRowLevelRole.description")
public interface AccessRowLevelRole {

    String CODE = "access-rl";

    /**
     * The condition that the access entry {@code ae} is for the current user: for their username or for one of
     * their resource roles. The policies look for the access set of a row among the sets of such entries, so the
     * row stays outside the subquery: Jmix puts the alias of a query in place of {@code {E}} as text, and inside
     * the subquery an alias {@code ae}, which the AI may choose, would mean the entry and let every row through.
     */
    String MY_ENTRY = "(ae.username = :current_user_username or ae.roleCode in :current_role_codes)";

    /**
     * Shows the folders where the user has an entry.
     */
    @JpqlRowLevelPolicy(entityClass = Folder.class, where = "{E}.accessSet in (select ae.accessSet"
            + " from AccessEntry ae where " + MY_ENTRY + ")")
    void folder();

    /**
     * Shows the documents where the user has an entry.
     */
    @JpqlRowLevelPolicy(entityClass = Document.class, where = "{E}.accessSet in (select ae.accessSet"
            + " from AccessEntry ae where " + MY_ENTRY + ")")
    void document();

    /**
     * Shows the versions of the documents the user sees.
     */
    @JpqlRowLevelPolicy(entityClass = DocumentRevision.class, where = "{E}.document.accessSet in"
            + " (select ae.accessSet from AccessEntry ae where " + MY_ENTRY + ")")
    void documentRevision();

    /**
     * Shows the access sets the user manages.
     */
    @JpqlRowLevelPolicy(entityClass = AccessSet.class, where = "{E}.id in (select ae.accessSet.id"
            + " from AccessEntry ae where ae.accessLevel >= @enum(com.company.docai.model.access.AccessLevel.MANAGE)"
            + " and " + MY_ENTRY + ")")
    void accessSet();

    /**
     * Shows the entries of the access sets the user manages.
     */
    @JpqlRowLevelPolicy(entityClass = AccessEntry.class, where = "{E}.accessSet in (select ae.accessSet"
            + " from AccessEntry ae where ae.accessLevel >= @enum(com.company.docai.model.access.AccessLevel.MANAGE)"
            + " and " + MY_ENTRY + ")")
    void accessEntry();

    /**
     * Creates, changes and deletes folders with {@link AccessLevel#EDIT}; a new folder needs it in its parent.
     */
    @PredicateRowLevelPolicy(entityClass = Folder.class, actions = {RowLevelPolicyAction.CREATE,
            RowLevelPolicyAction.UPDATE, RowLevelPolicyAction.DELETE})
    default RowLevelBiPredicate<Folder, ApplicationContext> folderChange() {
        return (folder, context) -> context.getBean(AccessChecker.class).canEdit(folder);
    }

    /**
     * Creates, changes and deletes documents with {@link AccessLevel#EDIT}; a new document needs it in its folder.
     */
    @PredicateRowLevelPolicy(entityClass = Document.class, actions = {RowLevelPolicyAction.CREATE,
            RowLevelPolicyAction.UPDATE, RowLevelPolicyAction.DELETE})
    default RowLevelBiPredicate<Document, ApplicationContext> documentChange() {
        return (document, context) -> context.getBean(AccessChecker.class).canEdit(document);
    }

    /**
     * Adds versions to the documents the user may change.
     */
    @PredicateRowLevelPolicy(entityClass = DocumentRevision.class, actions = RowLevelPolicyAction.CREATE)
    default RowLevelBiPredicate<DocumentRevision, ApplicationContext> documentRevisionCreation() {
        return (revision, context) -> context.getBean(AccessChecker.class).canEdit(revision.getDocument());
    }

    /**
     * Shows the comments of the documents the user sees.
     */
    @JpqlRowLevelPolicy(entityClass = DocumentComment.class, where = "{E}.document.accessSet in"
            + " (select ae.accessSet from AccessEntry ae where " + MY_ENTRY + ")")
    void documentComment();

    /**
     * Comments on the live documents the user sees: an entry of any level is enough.
     */
    @PredicateRowLevelPolicy(entityClass = DocumentComment.class, actions = RowLevelPolicyAction.CREATE)
    default RowLevelBiPredicate<DocumentComment, ApplicationContext> documentCommentCreation() {
        return (comment, context) -> context.getBean(AccessChecker.class).canRead(comment.getDocument());
    }

    /**
     * Changes and deletes the own comments only.
     */
    @PredicateRowLevelPolicy(entityClass = DocumentComment.class, actions = {RowLevelPolicyAction.UPDATE,
            RowLevelPolicyAction.DELETE})
    default RowLevelBiPredicate<DocumentComment, ApplicationContext> ownDocumentComment() {
        return (comment, context) -> context.getBean(CurrentAuthentication.class).getUser().getUsername()
                .equals(comment.getCreatedBy());
    }

    /**
     * Shows the selections of documents of the own conversations with the assistant.
     */
    @JpqlRowLevelPolicy(entityClass = DiscussedDocument.class,
            where = "{E}.conversation.username = :current_user_username")
    void discussedDocument();

    /**
     * Shows the own favorites: those of the user the person works as.
     */
    @JpqlRowLevelPolicy(entityClass = Favorite.class, where = "{E}.user.username = :current_user_username")
    void favorite();

    /**
     * Adds and removes only the own favorites.
     */
    @PredicateRowLevelPolicy(entityClass = Favorite.class, actions = {RowLevelPolicyAction.CREATE,
            RowLevelPolicyAction.DELETE})
    default RowLevelBiPredicate<Favorite, ApplicationContext> favoriteChange() {
        return (favorite, context) -> favorite.getUser().getUsername()
                .equals(context.getBean(CurrentUserSubstitution.class).getEffectiveUser().getUsername());
    }
}
