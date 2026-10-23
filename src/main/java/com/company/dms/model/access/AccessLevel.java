package com.company.dms.model.access;

import io.jmix.core.metamodel.datatype.EnumClass;
import org.jspecify.annotations.Nullable;

/**
 * What an access entry allows. The levels add up: {@link #MANAGE} includes {@link #EDIT}, and {@link #EDIT}
 * includes {@link #VIEW}. The ids grow with the level, so "at least this level" is a comparison in JPQL.
 */
public enum AccessLevel implements EnumClass<Integer> {

    VIEW(10),
    EDIT(20),
    MANAGE(30);

    private final Integer id;

    AccessLevel(Integer id) {
        this.id = id;
    }

    @Override
    public Integer getId() {
        return id;
    }

    /**
     * Tells whether this level allows what the given one allows.
     */
    public boolean includes(AccessLevel level) {
        return id >= level.id;
    }

    @Nullable
    public static AccessLevel fromId(Integer id) {
        for (AccessLevel level : AccessLevel.values()) {
            if (level.getId().equals(id)) {
                return level;
            }
        }

        return null;
    }
}
