package com.company.dms.model.access;

import io.jmix.aitools.ExcludeFromAi;
import io.jmix.core.DeletePolicy;
import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.entity.annotation.OnDeleteInverse;
import io.jmix.core.metamodel.annotation.DependsOnProperties;
import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.util.UUID;

/**
 * Gives a user or a Jmix resource role a level of access in an access set. Exactly one of {@code username}
 * and {@code roleCode} is set, and a set holds one entry per subject.
 */
@ExcludeFromAi
@JmixEntity
@Entity
@Table(name = "ACCESS_ENTRY", indexes = {
        @Index(name = "IDX_ACCESS_ENTRY_ACCESS_SET", columnList = "ACCESS_SET_ID")
}, uniqueConstraints = {
        @UniqueConstraint(name = "UK_ACCESS_ENTRY_USER", columnNames = {"ACCESS_SET_ID", "USERNAME"}),
        @UniqueConstraint(name = "UK_ACCESS_ENTRY_ROLE", columnNames = {"ACCESS_SET_ID", "ROLE_CODE"})
})
public class AccessEntry {

    @JmixGeneratedValue
    @Column(name = "ID", nullable = false)
    @Id
    private UUID id;

    @Version
    @Column(name = "VERSION", nullable = false)
    private Integer version;

    @OnDeleteInverse(DeletePolicy.CASCADE)
    @JoinColumn(name = "ACCESS_SET_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private AccessSet accessSet;

    @Column(name = "USERNAME")
    private String username;

    @Column(name = "ROLE_CODE")
    private String roleCode;

    @Column(name = "ACCESS_LEVEL", nullable = false)
    private Integer accessLevel;

    /**
     * The user or the role the entry is for: the username or the role code.
     */
    @InstanceName
    @DependsOnProperties({"username", "roleCode"})
    public String getSubject() {
        return username != null ? username : roleCode;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public AccessSet getAccessSet() {
        return accessSet;
    }

    public void setAccessSet(AccessSet accessSet) {
        this.accessSet = accessSet;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getRoleCode() {
        return roleCode;
    }

    public void setRoleCode(String roleCode) {
        this.roleCode = roleCode;
    }

    public AccessLevel getAccessLevel() {
        return accessLevel == null ? null : AccessLevel.fromId(accessLevel);
    }

    public void setAccessLevel(AccessLevel accessLevel) {
        this.accessLevel = accessLevel == null ? null : accessLevel.getId();
    }
}
