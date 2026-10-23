package com.company.dms.security.role;

import com.company.dms.model.access.AccessDraft;
import com.company.dms.model.access.AccessEntry;
import com.company.dms.model.access.AccessEntryDraft;
import com.company.dms.model.access.AccessSet;
import com.company.dms.model.document.Document;
import com.company.dms.model.document.DocumentRevision;
import com.company.dms.model.folder.Folder;
import com.company.dms.model.user.User;
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
public interface EmployeeRole extends UiMinimalRole {

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
     * the status graph and the archive lock.
     */
    @EntityPolicy(entityClass = Document.class, actions = {EntityPolicyAction.READ,
            EntityPolicyAction.CREATE, EntityPolicyAction.UPDATE, EntityPolicyAction.DELETE})
    @EntityAttributePolicy(entityClass = Document.class,
            attributes = {"name", "folder", "status", "documentDate", "currentRevision"},
            action = EntityAttributePolicyAction.MODIFY)
    @EntityAttributePolicy(entityClass = Document.class, attributes = "*",
            action = EntityAttributePolicyAction.VIEW)
    void document();

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
    @ViewPolicy(viewIds = {"RepositoryView", "Document.detail", "Folder.detail", "Folder.lookup"})
    @MenuPolicy(menuIds = "RepositoryView")
    void repository();
}
