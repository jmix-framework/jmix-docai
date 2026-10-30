package com.company.docai.security.role;

import com.company.docai.model.access.AccessDraft;
import com.company.docai.model.access.AccessEntry;
import com.company.docai.model.access.AccessEntryDraft;
import com.company.docai.model.access.AccessSet;
import com.company.docai.model.discussion.DiscussedDocument;
import com.company.docai.model.document.Document;
import com.company.docai.model.document.DocumentComment;
import com.company.docai.model.document.DocumentRevision;
import com.company.docai.model.favorite.Favorite;
import com.company.docai.model.favorite.FavoriteItem;
import com.company.docai.model.folder.Folder;
import com.company.docai.model.tag.Tag;
import com.company.docai.model.trash.TrashItem;
import com.company.docai.model.user.User;
import io.jmix.aitoolsflowui.security.role.AiToolsChatUserRole;
import io.jmix.dynattr.model.Category;
import io.jmix.dynattr.model.CategoryAttribute;
import io.jmix.security.model.EntityAttributePolicyAction;
import io.jmix.security.model.EntityPolicyAction;
import io.jmix.security.model.SecurityScope;
import io.jmix.security.role.annotation.EntityAttributePolicy;
import io.jmix.security.role.annotation.EntityPolicy;
import io.jmix.security.role.annotation.ResourceRole;
import io.jmix.securityflowui.role.annotation.MenuPolicy;
import io.jmix.securityflowui.role.annotation.ViewPolicy;

/**
 * The employee persona: works with folders and documents and views their own profile.
 * Extends {@link UiMinimalRole}, so one assignment is enough to sign in.
 */
@ResourceRole(name = "msg://EmployeeRole.name", code = EmployeeRole.CODE,
        description = "msg://EmployeeRole.description", scope = SecurityScope.UI)
public interface EmployeeRole extends UiMinimalRole, AiToolsChatUserRole {

    String CODE = "employee";

    /**
     * Reads users, but sees only their names, email and time zone.
     */
    @EntityPolicy(entityClass = User.class, actions = EntityPolicyAction.READ)
    @EntityAttributePolicy(entityClass = User.class,
            attributes = {"username", "firstName", "lastName", "email", "timeZoneId"},
            action = EntityAttributePolicyAction.VIEW)
    void user();

    /**
     * Opens the own profile from the user menu.
     */
    @ViewPolicy(viewIds = "ProfileReadView")
    void profile();

    /**
     * Works with the whole folder tree; the folder service and listener keep the root intact.
     */
    @EntityPolicy(entityClass = Folder.class, actions = {EntityPolicyAction.READ,
            EntityPolicyAction.CREATE, EntityPolicyAction.UPDATE, EntityPolicyAction.DELETE})
    @EntityAttributePolicy(entityClass = Folder.class, attributes = {"name", "parent"},
            action = EntityAttributePolicyAction.MODIFY)
    @EntityAttributePolicy(entityClass = Folder.class, attributes = "*",
            action = EntityAttributePolicyAction.VIEW)
    void folder();

    /**
     * Works with the documents of every folder; the document service and listener keep
     * the status graph and the archive lock. Every attribute is open for change: the fields of
     * a document type are attributes named {@code +code} that the administrator adds later, so a role
     * cannot list them. Attribute policies guard only the views; what is written is decided by
     * the document service, edit access is checked there and by {@link AccessRowLevelRole}, and
     * the views of an employee have no editable fields for the access set or the change stamps.
     */
    @EntityPolicy(entityClass = Document.class, actions = {EntityPolicyAction.READ,
            EntityPolicyAction.CREATE, EntityPolicyAction.UPDATE, EntityPolicyAction.DELETE})
    @EntityAttributePolicy(entityClass = Document.class, attributes = "*",
            action = EntityAttributePolicyAction.MODIFY)
    void document();

    /**
     * Reads the tags and adds new ones from the document card, where the document service saves them with
     * the document. Only the administrator renames and deletes tags.
     */
    @EntityPolicy(entityClass = Tag.class, actions = {EntityPolicyAction.READ, EntityPolicyAction.CREATE})
    @EntityAttributePolicy(entityClass = Tag.class, attributes = "*", action = EntityAttributePolicyAction.MODIFY)
    void tag();

    /**
     * Reads the document types and their fields. Only the administrator changes them.
     */
    @EntityPolicy(entityClass = Category.class, actions = EntityPolicyAction.READ)
    @EntityAttributePolicy(entityClass = Category.class, attributes = "*", action = EntityAttributePolicyAction.VIEW)
    @EntityPolicy(entityClass = CategoryAttribute.class, actions = EntityPolicyAction.READ)
    @EntityAttributePolicy(entityClass = CategoryAttribute.class, attributes = "*",
            action = EntityAttributePolicyAction.VIEW)
    void documentTypes();

    /**
     * Reads and adds document versions. A saved version never changes, and it goes away only
     * with its document: the cascade does not check the DELETE permission.
     */
    @EntityPolicy(entityClass = DocumentRevision.class, actions = {EntityPolicyAction.READ,
            EntityPolicyAction.CREATE})
    @EntityAttributePolicy(entityClass = DocumentRevision.class, attributes = "*",
            action = EntityAttributePolicyAction.MODIFY)
    void documentRevision();

    /**
     * Writes comments on the documents the user sees. {@link AccessRowLevelRole} leaves the comments of those
     * documents and lets only the author change or delete a comment; the comment service writes them.
     */
    @EntityPolicy(entityClass = DocumentComment.class, actions = {EntityPolicyAction.READ,
            EntityPolicyAction.CREATE, EntityPolicyAction.UPDATE, EntityPolicyAction.DELETE})
    @EntityAttributePolicy(entityClass = DocumentComment.class, attributes = "*",
            action = EntityAttributePolicyAction.MODIFY)
    void documentComment();

    /**
     * Reads access sets and their entries; {@link AccessRowLevelRole} leaves the sets the user manages.
     * Only the access service writes them.
     */
    @EntityPolicy(entityClass = AccessSet.class, actions = EntityPolicyAction.READ)
    @EntityAttributePolicy(entityClass = AccessSet.class, attributes = "*", action = EntityAttributePolicyAction.VIEW)
    @EntityPolicy(entityClass = AccessEntry.class, actions = EntityPolicyAction.READ)
    @EntityAttributePolicy(entityClass = AccessEntry.class, attributes = "*", action = EntityAttributePolicyAction.VIEW)
    void access();

    /**
     * Edits the access dialog, whose drafts are never stored: the access service checks the right to manage
     * the item and writes the access itself. Without these policies the dialog opens empty and read-only.
     */
    @EntityPolicy(entityClass = AccessDraft.class, actions = EntityPolicyAction.ALL)
    @EntityAttributePolicy(entityClass = AccessDraft.class, attributes = "*",
            action = EntityAttributePolicyAction.MODIFY)
    @EntityPolicy(entityClass = AccessEntryDraft.class, actions = EntityPolicyAction.ALL)
    @EntityAttributePolicy(entityClass = AccessEntryDraft.class, attributes = "*",
            action = EntityAttributePolicyAction.MODIFY)
    @ViewPolicy(viewIds = "AccessDraft.detail")
    void accessDialog();

    /**
     * Opens the views of the document repository.
     */
    @ViewPolicy(viewIds = {"RepositoryView", "Document.detail", "DocumentRevision.read", "Folder.detail",
            "Folder.lookup"})
    @MenuPolicy(menuIds = "RepositoryView")
    void repository();

    /**
     * Opens the trash. The trash service decides which items the user sees and may restore.
     */
    @EntityPolicy(entityClass = TrashItem.class, actions = EntityPolicyAction.READ)
    @EntityAttributePolicy(entityClass = TrashItem.class, attributes = "*", action = EntityAttributePolicyAction.VIEW)
    @ViewPolicy(viewIds = "TrashView")
    @MenuPolicy(menuIds = "TrashView")
    void trash();

    /**
     * Searches documents from the field in the header.
     */
    @ViewPolicy(viewIds = "DocumentSearchView")
    void search();

    /**
     * Opens the AI assistant from the main menu. The chat views and the own conversations come with the role
     * of the add-on; the item of the add-on stays hidden in its own group, so this one needs a policy of its own.
     */
    @MenuPolicy(menuIds = "assistantChat")
    void assistantChat();

    /**
     * Keeps the selections of documents of the own conversations with the assistant: {@link AccessRowLevelRole}
     * leaves the rows of the own conversations, and the discussion service writes only those.
     */
    @EntityPolicy(entityClass = DiscussedDocument.class, actions = EntityPolicyAction.ALL)
    @EntityAttributePolicy(entityClass = DiscussedDocument.class, attributes = "*",
            action = EntityAttributePolicyAction.MODIFY)
    void discussion();

    /**
     * Marks folders and documents as own favorites and removes the marks: {@link AccessRowLevelRole} leaves the own
     * marks, and the favorite service reads the targets with the rights of the user. The favorites block shows its
     * rows as {@link FavoriteItem}.
     */
    @EntityPolicy(entityClass = Favorite.class, actions = {EntityPolicyAction.READ, EntityPolicyAction.CREATE,
            EntityPolicyAction.DELETE})
    @EntityAttributePolicy(entityClass = Favorite.class, attributes = "*", action = EntityAttributePolicyAction.MODIFY)
    @EntityPolicy(entityClass = FavoriteItem.class, actions = EntityPolicyAction.READ)
    @EntityAttributePolicy(entityClass = FavoriteItem.class, attributes = "*",
            action = EntityAttributePolicyAction.VIEW)
    void favorite();
}
