package com.company.docai.model.document;

import com.company.docai.model.access.AccessSet;
import com.company.docai.model.folder.Folder;
import com.company.docai.model.tag.Tag;
import io.jmix.aitools.ExcludeFromAi;
import io.jmix.core.DeletePolicy;
import io.jmix.core.annotation.DeletedBy;
import io.jmix.core.annotation.DeletedDate;
import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.entity.annotation.OnDelete;
import io.jmix.core.entity.annotation.OnDeleteInverse;
import io.jmix.core.metamodel.annotation.Composition;
import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import io.jmix.dynattr.model.Categorized;
import io.jmix.dynattr.model.Category;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
import org.jspecify.annotations.Nullable;
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
 * in use. Names are unique among the live documents of one folder and are case-sensitive. Its type
 * is a category of the dynamic attributes add-on, and the fields of the type are the dynamic attributes
 * of the category, properties named {@code +code}.
 */
@JmixEntity
@Entity
@Table(name = "DOCUMENT", indexes = {
        @Index(name = "IDX_DOCUMENT_FOLDER", columnList = "FOLDER_ID"),
        @Index(name = "IDX_DOCUMENT_CURRENT_REVISION", columnList = "CURRENT_REVISION_ID"),
        @Index(name = "IDX_DOCUMENT_ACCESS_SET", columnList = "ACCESS_SET_ID"),
        @Index(name = "IDX_DOCUMENT_CATEGORY", columnList = "CATEGORY_ID")
}, uniqueConstraints = {
        @UniqueConstraint(name = "UK_DOCUMENT_NAME_KEY", columnNames = {"FOLDER_ID", "NAME_KEY"})
})
public class Document implements Categorized {

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
    @ExcludeFromAi
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

    /**
     * The type of the document. A type that documents are marked with cannot be deleted.
     */
    // IntelliJ counts only the project's classes as entities, not the add-on's Category
    @SuppressWarnings("JpaAttributeTypeInspection")
    @OnDeleteInverse(DeletePolicy.DENY)
    @JoinColumn(name = "CATEGORY_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private Category category;

    /**
     * The tags of the document, by name. The document owns the association, and a tag in use cannot be deleted:
     * the tag service refuses it, and the foreign key of the link table stops the other ways.
     */
    @OrderBy("nameKey")
    @JoinTable(name = "DOCUMENT_TAG_LINK",
            joinColumns = @JoinColumn(name = "DOCUMENT_ID", referencedColumnName = "ID"),
            inverseJoinColumns = @JoinColumn(name = "TAG_ID", referencedColumnName = "ID"),
            indexes = @Index(name = "IDX_DOCUMENT_TAG_LINK_TAG", columnList = "TAG_ID"))
    @ManyToMany
    private List<Tag> tags;

    /**
     * Who changed the document itself last: its file, name, date, type, fields or status. The audit stamps below
     * change with every save of the row, an access change and a move included.
     */
    @Column(name = "CHANGED_BY")
    private String changedBy;

    /**
     * When the document itself was changed last, see {@link #changedBy}.
     */
    @Column(name = "CHANGED_DATE")
    private OffsetDateTime changedDate;

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

    // Categorized is @NullMarked, yet a document may have no type, and the add-on itself checks for none
    @SuppressWarnings("NullableProblems")
    @Override
    public @Nullable Category getCategory() {
        return category;
    }

    @Override
    public void setCategory(@Nullable Category category) {
        this.category = category;
    }

    public List<Tag> getTags() {
        return tags;
    }

    public void setTags(List<Tag> tags) {
        this.tags = tags;
    }

    public String getChangedBy() {
        return changedBy;
    }

    public void setChangedBy(String changedBy) {
        this.changedBy = changedBy;
    }

    public OffsetDateTime getChangedDate() {
        return changedDate;
    }

    public void setChangedDate(OffsetDateTime changedDate) {
        this.changedDate = changedDate;
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
