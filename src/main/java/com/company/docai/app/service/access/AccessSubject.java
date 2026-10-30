package com.company.docai.app.service.access;

import org.jspecify.annotations.Nullable;

/**
 * A user or a resource role that can get access: exactly one of the username and the role code is set.
 *
 * @param username the username of a user
 * @param roleCode the code of a resource role
 * @param name     the name to show: the user's name or the role's name in the user's language
 */
public record AccessSubject(@Nullable String username, @Nullable String roleCode, String name) {

    /**
     * Tells whether the subject is a role.
     */
    public boolean isRole() {
        return roleCode != null;
    }
}
