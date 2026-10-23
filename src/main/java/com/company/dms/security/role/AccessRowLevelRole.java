package com.company.dms.security.role;

import com.company.dms.app.service.access.AccessChecker;
import com.company.dms.model.access.AccessEntry;
import com.company.dms.model.access.AccessLevel;
import com.company.dms.model.access.AccessSet;
import com.company.dms.model.document.Document;
import com.company.dms.model.document.DocumentRevision;
import com.company.dms.model.folder.Folder;
import com.company.dms.security.AccessRoleAssignmentProvider;
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
     * their resource roles. The alias must differ from {@code e}, the default alias of Jmix queries: a subquery
     * alias {@code e} hides the outer one, and the policy lets every row through.
     */
    String MY_ENTRY = "(ae.username = :current_user_username or ae.roleCode in :current_role_codes)";

    /**
     * Shows the folders where the user has an entry.
     */
    @JpqlRowLevelPolicy(entityClass = Folder.class, where = "exists (select ae from AccessEntry ae"
            + " where ae.accessSet = {E}.accessSet and " + MY_ENTRY + ")")
    void folder();

    /**
     * Shows the documents where the user has an entry.
     */
    @JpqlRowLevelPolicy(entityClass = Document.class, where = "exists (select ae from AccessEntry ae"
            + " where ae.accessSet = {E}.accessSet and " + MY_ENTRY + ")")
    void document();

    /**
     * Shows the versions of the documents the user sees.
     */
    @JpqlRowLevelPolicy(entityClass = DocumentRevision.class, where = "exists (select ae from AccessEntry ae"
            + " where ae.accessSet = {E}.document.accessSet and " + MY_ENTRY + ")")
    void documentRevision();

    /**
     * Shows the access sets the user manages.
     */
    @JpqlRowLevelPolicy(entityClass = AccessSet.class, where = "exists (select ae from AccessEntry ae"
            + " where ae.accessSet = {E}"
            + " and ae.accessLevel >= @enum(com.company.dms.model.access.AccessLevel.MANAGE) and " + MY_ENTRY + ")")
    void accessSet();

    /**
     * Shows the entries of the access sets the user manages.
     */
    @JpqlRowLevelPolicy(entityClass = AccessEntry.class, where = "exists (select ae from AccessEntry ae"
            + " where ae.accessSet = {E}.accessSet"
            + " and ae.accessLevel >= @enum(com.company.dms.model.access.AccessLevel.MANAGE) and " + MY_ENTRY + ")")
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
}
