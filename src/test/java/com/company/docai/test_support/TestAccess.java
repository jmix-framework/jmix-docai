package com.company.docai.test_support;

import com.company.docai.model.access.AccessEntry;
import com.company.docai.model.access.AccessLevel;
import com.company.docai.model.access.AccessSet;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.Id;
import io.jmix.core.SaveContext;
import io.jmix.data.PersistenceHints;
import io.jmix.security.role.assignment.RoleAssignmentRoleType;
import io.jmix.securitydata.entity.ResourceRoleEntity;
import io.jmix.securitydata.entity.RoleAssignmentEntity;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Sets up access the way the access service and the administrator will: gives a folder or a document an access
 * set of its own and gives a user a runtime role that stands for a department. Run it as the administrator.
 */
public final class TestAccess {

    private TestAccess() {
    }

    /**
     * Gives the folder an access set of its own with the grants. Call it before the folder gets content:
     * the content keeps the set it has.
     */
    public static Folder giveOwnAccess(DataManager dataManager, Folder folder, Grant... grants) {
        Folder stored = dataManager.load(Id.of(folder)).one();
        AccessSet accessSet = dataManager.create(AccessSet.class);
        accessSet.setOwnerFolder(stored);
        stored.setAccessSet(accessSet);

        return dataManager.save(withEntries(dataManager, accessSet, grants).saving(stored)).get(stored);
    }

    /**
     * Gives the document an access set of its own with the grants.
     */
    public static Document giveOwnAccess(DataManager dataManager, Document document, Grant... grants) {
        Document stored = dataManager.load(Id.of(document)).one();
        AccessSet accessSet = dataManager.create(AccessSet.class);
        accessSet.setOwnerDocument(stored);
        stored.setAccessSet(accessSet);

        return dataManager.save(withEntries(dataManager, accessSet, grants).saving(stored)).get(stored);
    }

    /**
     * Returns the access set in effect for the folder, also when the folder is deleted.
     */
    public static AccessSet setOf(DataManager dataManager, Folder folder) {
        return dataManager.load(Id.of(folder))
                .fetchPlan(fetchPlan -> fetchPlan.add("accessSet", FetchPlan.INSTANCE_NAME))
                .hint(PersistenceHints.SOFT_DELETION, false)
                .one()
                .getAccessSet();
    }

    /**
     * Returns the access set in effect for the document, also when the document is deleted.
     */
    public static AccessSet setOf(DataManager dataManager, Document document) {
        return dataManager.load(Id.of(document))
                .fetchPlan(fetchPlan -> fetchPlan.add("accessSet", FetchPlan.INSTANCE_NAME))
                .hint(PersistenceHints.SOFT_DELETION, false)
                .one()
                .getAccessSet();
    }

    private static SaveContext withEntries(DataManager dataManager, AccessSet accessSet, Grant... grants) {
        SaveContext saveContext = new SaveContext().saving(accessSet);
        for (Grant grant : grants) {
            AccessEntry entry = dataManager.create(AccessEntry.class);
            entry.setAccessSet(accessSet);
            entry.setUsername(grant.username());
            entry.setRoleCode(grant.roleCode());
            entry.setAccessLevel(grant.level());
            saveContext.saving(entry);
        }

        return saveContext;
    }

    /**
     * An access entry to create, for a user or for a role.
     */
    public record Grant(@Nullable String username, @Nullable String roleCode, AccessLevel level) {

        public static Grant user(String username, AccessLevel level) {
            return new Grant(username, null, level);
        }

        public static Grant role(String roleCode, AccessLevel level) {
            return new Grant(null, roleCode, level);
        }
    }

    /**
     * A runtime resource role without policies that stands for a department, as an administrator creates it
     * in the role views, assigned to one user. The user gets it at their next authentication.
     */
    public record DepartmentRole(String code, ResourceRoleEntity role, RoleAssignmentEntity assignment) {

        /**
         * Creates the role with a unique code and assigns it to the user.
         */
        public static DepartmentRole assign(DataManager dataManager, String username) {
            ResourceRoleEntity role = dataManager.create(ResourceRoleEntity.class);
            role.setCode("department-" + UUID.randomUUID());
            role.setName("Department");

            RoleAssignmentEntity assignment = dataManager.create(RoleAssignmentEntity.class);
            assignment.setUsername(username);
            assignment.setRoleCode(role.getCode());
            assignment.setRoleType(RoleAssignmentRoleType.RESOURCE);

            dataManager.save(role, assignment);
            return new DepartmentRole(role.getCode(), role, assignment);
        }

        /**
         * Deletes the assignment and the role for good: both are soft-deleted by default.
         */
        public void remove(DataManager dataManager) {
            dataManager.save(new SaveContext()
                    .removing(dataManager.load(Id.of(assignment)).one(), dataManager.load(Id.of(role)).one())
                    .setHint(PersistenceHints.SOFT_DELETION, false));
        }
    }
}
