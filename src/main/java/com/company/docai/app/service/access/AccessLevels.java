package com.company.docai.app.service.access;

import com.company.docai.model.access.AccessLevel;
import com.company.docai.model.access.AccessSet;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.UUID;

/**
 * The levels of the current user in the access sets where they have entries, read in one query so that the tree
 * and the actions do not ask for every item. A user with full access has {@link AccessLevel#MANAGE} in every set.
 *
 * @param bySet the highest level of the user's entries, by access set id
 * @param full  whether the user has full access
 */
public record AccessLevels(Map<UUID, AccessLevel> bySet, boolean full) {

    /**
     * Returns the level of the user in the set, or null if no entry gives them access.
     */
    @Nullable
    public AccessLevel of(AccessSet accessSet) {
        return full ? AccessLevel.MANAGE : bySet.get(accessSet.getId());
    }

    /**
     * Tells whether the level of the user in the set includes the given one.
     */
    public boolean allows(AccessSet accessSet, AccessLevel level) {
        AccessLevel own = of(accessSet);
        return own != null && own.includes(level);
    }}
