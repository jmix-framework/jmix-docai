package com.company.dms.model.folder;

import com.company.dms.model.access.AccessSet;
import io.jmix.core.DeletePolicy;
import io.jmix.core.Messages;
import io.jmix.core.annotation.DeletedBy;
import io.jmix.core.annotation.DeletedDate;
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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A folder of the repository tree. One root folder, created by the changelog, holds the whole tree;
 * names are unique among the live folders of one parent and are case-sensitive.
 */
@JmixEntity
@Entity
@Table(name = "FOLDER", indexes = {
        @Index(name = "IDX_FOLDER_PARENT", columnList = "PARENT_ID"),
        @Index(name = "IDX_FOLDER_ACCESS_SET", columnList = "ACCESS_SET_ID")
}, uniqueConstraints = {
        @UniqueConstraint(name = "UK_FOLDER_NAME_KEY", columnNames = {"PARENT_ID", "NAME_KEY"})
})
public class Folder {

    /**
     * The root folder, created by the Liquibase changelog.
     */
    public static final UUID ROOT_ID = UUID.fromString("1a6e3c0e-5b0f-4c55-9a3e-6f1d2c7b8e01");

    @JmixGeneratedValue
    @Column(name = "ID", nullable = false)
    @Id
    private UUID id;

    @Version
    @Column(name = "VERSION", nullable = false)
    private Integer version;

    @NotBlank
    @Column(name = "NAME", nullable = false)
    private String name;

    @OnDeleteInverse(DeletePolicy.CASCADE)
    @JoinColumn(name = "PARENT_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private Folder parent;

    /**
     * The name of a live folder and null for a deleted one, so that the unique constraint
     * ignores deleted folders on any database.
     */
    @Column(name = "NAME_KEY")
    private String nameKey;

    /**
     * The access set in effect: the set of the parent for an inheriting folder, its own set otherwise.
     */
    @JoinColumn(name = "ACCESS_SET_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private AccessSet accessSet;

    @CreatedBy
    @Column(name = "CREATED_BY")
    private String createdBy;

    @CreatedDate
    @Column(name = "CREATED_DATE")
    private OffsetDateTime createdDate;

    @LastModifiedBy
    @Column(name = "LAST_MODIFIED_BY")
    private String lastModifiedBy;

    @LastModifiedDate
    @Column(name = "LAST_MODIFIED_DATE")
    private OffsetDateTime lastModifiedDate;

    @DeletedBy
    @Column(name = "DELETED_BY")
    private String deletedBy;

    @DeletedDate
    @Column(name = "DELETED_DATE")
    private OffsetDateTime deletedDate;

    @PrePersist
    @PreUpdate
    protected void updateNameKey() {
        nameKey = deletedDate == null ? name : null;
    }

    /**
     * Tells whether this is the root folder, which the changelog creates.
     */
    public boolean isRoot() {
        return ROOT_ID.equals(id);
    }

    /**
     * The name to show: the root shows its name in the user's language, other folders their own name.
     */
    @InstanceName
    @DependsOnProperties("name")
    public String getDisplayName(Messages messages) {
        return isRoot() ? messages.getMessage(Folder.class, "Folder.root") : name;
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Folder getParent() {
        return parent;
    }

    public void setParent(Folder parent) {
        this.parent = parent;
    }

    public String getNameKey() {
        return nameKey;
    }

    public void setNameKey(String nameKey) {
        this.nameKey = nameKey;
    }

    public AccessSet getAccessSet() {
        return accessSet;
    }

    public void setAccessSet(AccessSet accessSet) {
        this.accessSet = accessSet;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public OffsetDateTime getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(OffsetDateTime createdDate) {
        this.createdDate = createdDate;
    }

    public String getLastModifiedBy() {
        return lastModifiedBy;
    }

    public void setLastModifiedBy(String lastModifiedBy) {
        this.lastModifiedBy = lastModifiedBy;
    }

    public OffsetDateTime getLastModifiedDate() {
        return lastModifiedDate;
    }

    public void setLastModifiedDate(OffsetDateTime lastModifiedDate) {
        this.lastModifiedDate = lastModifiedDate;
    }

    public String getDeletedBy() {
        return deletedBy;
    }

    public void setDeletedBy(String deletedBy) {
        this.deletedBy = deletedBy;
    }

    public OffsetDateTime getDeletedDate() {
        return deletedDate;
    }

    public void setDeletedDate(OffsetDateTime deletedDate) {
        this.deletedDate = deletedDate;
    }
}
