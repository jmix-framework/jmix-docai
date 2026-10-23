package com.company.dms.app.service.access;

import com.company.dms.model.access.AccessLevel;
import com.company.dms.model.access.AccessSet;
import com.company.dms.model.document.Document;
import com.company.dms.model.folder.Folder;
import com.company.dms.security.CurrentRoleCodesQueryParamValueProvider;
import com.company.dms.security.role.AccessRowLevelRole;
import com.company.dms.security.role.FullAccessRole;
import io.jmix.core.EntityStates;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.data.PersistenceHints;
import io.jmix.security.role.RoleGrantedAuthorityUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/**
 * The Java form of the access rule that {@link AccessRowLevelRole} applies to loads: the level of the current user
 * in an access set is the highest level among the entries for their username and for their resource roles.
 * A user with {@link FullAccessRole} is recognized by that role and has {@link AccessLevel#MANAGE} everywhere.
 */
@Component
public class AccessChecker {

    // the query parameters come from the same providers as in the JPQL policies of AccessRowLevelRole
    private static final String SET_LEVEL = "select max(ae.accessLevel) from AccessEntry ae"
            + " where ae.accessSet.id = :accessSet and " + AccessRowLevelRole.MY_ENTRY;

    private static final String FOLDER_LEVEL = "select max(ae.accessLevel) from AccessEntry ae, Folder f"
            + " where f.id = :item and ae.accessSet = f.accessSet and " + AccessRowLevelRole.MY_ENTRY;

    private static final String DOCUMENT_LEVEL = "select max(ae.accessLevel) from AccessEntry ae, Document d"
            + " where d.id = :item and ae.accessSet = d.accessSet and " + AccessRowLevelRole.MY_ENTRY;

    private final UnconstrainedDataManager dataManager;
    private final CurrentAuthentication currentAuthentication;
    private final RoleGrantedAuthorityUtils roleGrantedAuthorityUtils;
    private final EntityStates entityStates;
    private final CurrentRoleCodesQueryParamValueProvider roleCodes;

    public AccessChecker(UnconstrainedDataManager dataManager, CurrentAuthentication currentAuthentication,
                         RoleGrantedAuthorityUtils roleGrantedAuthorityUtils, EntityStates entityStates,
                         CurrentRoleCodesQueryParamValueProvider roleCodes) {
        this.dataManager = dataManager;
        this.currentAuthentication = currentAuthentication;
        this.roleGrantedAuthorityUtils = roleGrantedAuthorityUtils;
        this.entityStates = entityStates;
        this.roleCodes = roleCodes;
    }

    /**
     * Tells whether the current user has {@link FullAccessRole}, which no access entry restricts.
     */
    public boolean hasFullAccess() {
        String fullAccess = roleGrantedAuthorityUtils.createResourceRoleGrantedAuthority(FullAccessRole.CODE)
                .getAuthority();

        return currentAuthentication.getAuthentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority -> Objects.equals(authority, fullAccess));
    }

    /**
     * Tells whether an entry for the username or the role code is for the current user.
     */
    public boolean isMine(@Nullable String username, @Nullable String roleCode) {
        return username != null
                ? username.equals(currentAuthentication.getUser().getUsername())
                : roleCode != null && roleCodes.currentRoleCodes().contains(roleCode);
    }

    /**
     * Returns the level of the current user in the access set, or null if no entry gives them access.
     */
    @Nullable
    public AccessLevel getLevel(AccessSet accessSet) {
        if (hasFullAccess()) {
            return AccessLevel.MANAGE;
        }

        return AccessLevel.fromId(dataManager.loadValue(SET_LEVEL, Integer.class)
                .parameter("accessSet", accessSet.getId())
                .one());
    }

    /**
     * Tells whether the current user may change the folder: rename, move or delete it with {@link AccessLevel#EDIT}
     * on its stored access set, or create a new folder with {@link AccessLevel#EDIT} on the set of its parent.
     */
    public boolean canEdit(Folder folder) {
        if (hasFullAccess()) {
            return true;
        }

        if (entityStates.isNew(folder)) {
            return folder.getParent() != null && hasEdit(FOLDER_LEVEL, folder.getParent().getId());
        }

        return hasEdit(FOLDER_LEVEL, folder.getId());
    }

    /**
     * Tells whether the current user may change the document or add a version to it: {@link AccessLevel#EDIT}
     * on its stored access set, or on the set of its folder for a new document.
     */
    public boolean canEdit(Document document) {
        if (hasFullAccess()) {
            return true;
        }

        if (entityStates.isNew(document)) {
            return document.getFolder() != null && hasEdit(FOLDER_LEVEL, document.getFolder().getId());
        }

        return hasEdit(DOCUMENT_LEVEL, document.getId());
    }

    /**
     * Checks the set the item has in the database, so that the answer depends neither on the fetch plan of
     * the instance nor on changes not saved yet. A deleted item keeps its set, so deleted rows are read too.
     */
    private boolean hasEdit(String levelQuery, UUID itemId) {
        AccessLevel level = AccessLevel.fromId(dataManager.loadValue(levelQuery, Integer.class)
                .parameter("item", itemId)
                .hint(PersistenceHints.SOFT_DELETION, false)
                .one());

        return level != null && level.includes(AccessLevel.EDIT);
    }
}
