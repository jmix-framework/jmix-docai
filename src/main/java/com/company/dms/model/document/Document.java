package com.company.dms.model.document;

import com.company.dms.model.access.AccessSet;
import com.company.dms.model.folder.Folder;
import io.jmix.core.DeletePolicy;
import io.jmix.core.annotation.DeletedBy;
import io.jmix.core.annotation.DeletedDate;
import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.entity.annotation.OnDelete;
import io.jmix.core.entity.annotation.OnDeleteInverse;
import io.jmix.core.metamodel.annotation.Composition;
import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A document in a folder. Its files are revisions, and {@code currentRevision} points to the one
 * in use. Names are unique among the live documents of one folder and are case-sensitive.
 */
@JmixEntity
@Entity
@Table(name = "DOCUMENT", indexes = {
        @Index(name = "IDX_DOCUMENT_FOLDER", columnList = "FOLDER_ID"),
        @Index(name = "IDX_DOCUMENT_CURRENT_REVISION", columnList = "CURRENT_REVISION_ID"),
        @Index(name = "IDX_DOCUMENT_ACCESS_SET", columnList = "ACCESS_SET_ID")
}, uniqueConstraints = {
        @UniqueConstraint(name = "UK_DOCUMENT_NAME_KEY", columnNames = {"FOLDER_ID", "NAME_KEY"})
})
public class Document {

    @JmixGeneratedValue
    @Column(name = "ID", nullable = false)
    @Id
    private UUID id;

    @Version
    @Column(name = "VERSION", nullable = false)
    private Integer version;

    @InstanceName
    @NotBlank
    @Column(name = "NAME", nullable = false)
    private String name;

    /**
     * The name of a live document and null for a deleted one, so that the unique constraint
     * ignores deleted documents on any database.
     */
    @Column(name = "NAME_KEY")
    private String nameKey;

    @OnDeleteInverse(DeletePolicy.CASCADE)
    @JoinColumn(name = "FOLDER_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Folder folder;

    @JoinColumn(name = "CURRENT_REVISION_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private DocumentRevision currentRevision;

    /**
     * The access set in effect: the set of the folder for an inheriting document, its own set otherwise.
     */
    @JoinColumn(name = "ACCESS_SET_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private AccessSet accessSet;

    @Composition
    @OnDelete(DeletePolicy.CASCADE)
    @OrderBy("number DESC")
    @OneToMany(mappedBy = "document")
    private List<DocumentRevision> revisions;

    @Column(name = "STATUS", nullable = false, length = 50)
    private String status = DocumentStatus.DRAFT.getId();

    @Column(name = "DOCUMENT_DATE")
    private LocalDate documentDate;

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

    public String getNameKey() {
        return nameKey;
    }

    public void setNameKey(String nameKey) {
        this.nameKey = nameKey;
    }

    public Folder getFolder() {
        return folder;
    }

    public void setFolder(Folder folder) {
        this.folder = folder;
    }

    public DocumentRevision getCurrentRevision() {
        return currentRevision;
    }

    public void setCurrentRevision(DocumentRevision currentRevision) {
        this.currentRevision = currentRevision;
    }

    public AccessSet getAccessSet() {
        return accessSet;
    }

    public void setAccessSet(AccessSet accessSet) {
        this.accessSet = accessSet;
    }

    public List<DocumentRevision> getRevisions() {
        return revisions;
    }

    public void setRevisions(List<DocumentRevision> revisions) {
        this.revisions = revisions;
    }

    public DocumentStatus getStatus() {
        return status == null ? null : DocumentStatus.fromId(status);
    }

    public void setStatus(DocumentStatus status) {
        this.status = status == null ? null : status.getId();
    }

    public LocalDate getDocumentDate() {
        return documentDate;
    }

    public void setDocumentDate(LocalDate documentDate) {
        this.documentDate = documentDate;
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
