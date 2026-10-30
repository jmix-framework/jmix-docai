package com.company.docai.security;

import com.company.docai.security.role.AccessRowLevelRole;
import com.company.docai.security.role.FullAccessRole;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.security.role.assignment.RoleAssignment;
import io.jmix.security.role.assignment.RoleAssignmentProvider;
import io.jmix.security.role.assignment.RoleAssignmentRoleType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Assigns {@link AccessRowLevelRole} to every user without {@link FullAccessRole}, so that nobody works without
 * the access entries applied. Who has full access is told by the other role assignment providers, such as the
 * database one.
 */
@Component
public class AccessRoleAssignmentProvider implements RoleAssignmentProvider {

    private final ObjectProvider<RoleAssignmentProvider> assignmentProviders;
    private final UnconstrainedDataManager dataManager;

    public AccessRoleAssignmentProvider(ObjectProvider<RoleAssignmentProvider> assignmentProviders,
                                        UnconstrainedDataManager dataManager) {
        this.assignmentProviders = assignmentProviders;
        this.dataManager = dataManager;
    }

    @Override
    public Collection<RoleAssignment> getAllAssignments() {
        Set<String> withFullAccess = otherProviders()
                .flatMap(provider -> provider.getAllAssignments().stream())
                .filter(AccessRoleAssignmentProvider::isFullAccess)
                .map(RoleAssignment::getUsername)
                .collect(Collectors.toSet());

        return dataManager.loadValue("select u.username from User u", String.class)
                .list().stream()
                .filter(username -> !withFullAccess.contains(username))
                .map(AccessRoleAssignmentProvider::accessAssignment)
                .toList();
    }

    @Override
    public Collection<RoleAssignment> getAssignmentsByUsername(String username) {
        boolean fullAccess = otherProviders()
                .flatMap(provider -> provider.getAssignmentsByUsername(username).stream())
                .anyMatch(AccessRoleAssignmentProvider::isFullAccess);

        return fullAccess ? List.of() : List.of(accessAssignment(username));
    }

    private Stream<RoleAssignmentProvider> otherProviders() {
        return assignmentProviders.stream()
                .filter(provider -> provider != this);
    }

    private static boolean isFullAccess(RoleAssignment assignment) {
        return RoleAssignmentRoleType.RESOURCE.equals(assignment.getRoleType())
                && FullAccessRole.CODE.equals(assignment.getRoleCode());
    }

    private static RoleAssignment accessAssignment(String username) {
        return new RoleAssignment(username, AccessRowLevelRole.CODE, RoleAssignmentRoleType.ROW_LEVEL);
    }
}
