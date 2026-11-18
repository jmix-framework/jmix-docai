package com.company.docai.security.role;

import com.company.docai.model.access.AccessDraft;
import com.company.docai.model.access.AccessEntry;
import com.company.docai.model.access.AccessEntryDraft;
import com.company.docai.model.access.AccessSet;
import com.company.docai.model.discussion.DiscussedDocument;
import com.company.docai.model.document.Document;
import com.company.docai.model.document.DocumentComment;
import com.company.docai.model.document.DocumentRevision;
import com.company.docai.model.document.DocumentStatus;
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
 * The employee persona: folders, documents and own profile; extends {@link UiMinimalRole}, so it suffices to sign in.
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
     * Works with the documents of every folder; every attribute is open for change, since the fields of a document type
     * are attributes named {@code +code} the administrator adds later. Attribute policies guard only the views: the
     * document service decides what is written, edit access is checked there and by {@link AccessRowLevelRole}, and an
     * employee's views edit neither the access set nor the change stamps.
     */
    @EntityPolicy(entityClass = Document.class, actions = {EntityPolicyAction.READ,
            EntityPolicyAction.CREATE, EntityPolicyAction.UPDATE, EntityPolicyAction.DELETE})
    @EntityAttributePolicy(entityClass = Document.class, attributes = "*",
            action = EntityAttributePolicyAction.MODIFY)
    void document();

    /**
     * Reads the tags and adds new ones from the document card; only the administrator renames and deletes tags.
     */
    @EntityPolicy(entityClass = Tag.class, actions = {EntityPolicyAction.READ, EntityPolicyAction.CREATE})
    @EntityAttributePolicy(entityClass = Tag.class, attributes = "*", action = EntityAttributePolicyAction.MODIFY)
    void tag();

    /**
     * Reads the document statuses with their rules. Only the administrator keeps the dictionary.
     */
    @EntityPolicy(entityClass = DocumentStatus.class, actions = EntityPolicyAction.READ)
    @EntityAttributePolicy(entityClass = DocumentStatus.class, attributes = "*",
            action = EntityAttributePolicyAction.VIEW)
    void documentStatus();

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
     * Writes comments on visible documents; {@link AccessRowLevelRole} lets only the author change or delete a comment.
     */
    @EntityPolicy(entityClass = DocumentComment.class, actions = {EntityPolicyAction.READ,
            EntityPolicyAction.CREATE, EntityPolicyAction.UPDATE, EntityPolicyAction.DELETE})
    @EntityAttributePolicy(entityClass = DocumentComment.class, attributes = "*",
            action = EntityAttributePolicyAction.MODIFY)
    void documentComment();

    /**
     * Reads access sets and their entries; only the access service writes them.
     */
    @EntityPolicy(entityClass = AccessSet.class, actions = EntityPolicyAction.READ)
    @EntityAttributePolicy(entityClass = AccessSet.class, attributes = "*", action = EntityAttributePolicyAction.VIEW)
    @EntityPolicy(entityClass = AccessEntry.class, actions = EntityPolicyAction.READ)
    @EntityAttributePolicy(entityClass = AccessEntry.class, attributes = "*", action = EntityAttributePolicyAction.VIEW)
    void access();

    /**
     * Edits the access dialog, whose drafts are never stored; without these policies it opens empty and read-only.
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
     * Opens the views of the document repository. The folders of the main menu lead to it, so it has no menu item.
     */
    @ViewPolicy(viewIds = {"RepositoryView", "Document.detail", "DocumentRevision.read", "Folder.detail",
            "Folder.lookup", "DocumentUploadView"})
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
     * Searches documents from the field in the header, from the search palette and on the search page.
     */
    @ViewPolicy(viewIds = {"DocumentSearchView", "SearchPaletteView"})
    void search();

    /**
     * Opens the AI assistant from its own item of the main menu; the add-on's item stays hidden in its group.
     */
    @MenuPolicy(menuIds = "assistantChat")
    void assistantChat();

    /**
     * Keeps the selections of documents of the own conversations with the assistant.
     */
    @EntityPolicy(entityClass = DiscussedDocument.class, actions = EntityPolicyAction.ALL)
    @EntityAttributePolicy(entityClass = DiscussedDocument.class, attributes = "*",
            action = EntityAttributePolicyAction.MODIFY)
    void discussion();

    /**
     * Marks folders and documents as own favorites and removes the marks.
     */
    @EntityPolicy(entityClass = Favorite.class, actions = {EntityPolicyAction.READ, EntityPolicyAction.CREATE,
            EntityPolicyAction.DELETE})
    @EntityAttributePolicy(entityClass = Favorite.class, attributes = "*", action = EntityAttributePolicyAction.MODIFY)
    @EntityPolicy(entityClass = FavoriteItem.class, actions = EntityPolicyAction.READ)
    @EntityAttributePolicy(entityClass = FavoriteItem.class, attributes = "*",
            action = EntityAttributePolicyAction.VIEW)
    void favorite();
}
