package com.company.dms.model.access;

import com.company.dms.model.document.Document;
import com.company.dms.model.folder.Folder;
import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.metamodel.annotation.Composition;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.util.List;
import java.util.UUID;

/**
 * The entries that give access to folders and documents. A folder or a document refers to the set in effect:
 * an inheriting one to the set of its parent, one with its own access to its own set. Exactly one owner is set,
 * the folder or the document the set was created for, and an item owns one set at most; the root owns
 * the first set. A set is deleted physically,
 * and the database deletes its entries with it.
 */
@JmixEntity
@Entity
@Table(name = "ACCESS_SET", uniqueConstraints = {
        @UniqueConstraint(name = "UK_ACCESS_SET_OWNER_FOLDER", columnNames = "OWNER_FOLDER_ID"),
        @UniqueConstraint(name = "UK_ACCESS_SET_OWNER_DOCUMENT", columnNames = "OWNER_DOCUMENT_ID")
})
public class AccessSet {

    /**
     * The set of the root folder, created by the Liquibase changelog.
     */
    public static final UUID ROOT_ID = UUID.fromString("2bf6f55e-0ad3-4afc-94bc-1a0915e3c932");

    @JmixGeneratedValue
    @Column(name = "ID", nullable = false)
    @Id
    private UUID id;

    @Version
    @Column(name = "VERSION", nullable = false)
    private Integer version;

    @JoinColumn(name = "OWNER_FOLDER_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private Folder ownerFolder;

    @JoinColumn(name = "OWNER_DOCUMENT_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private Document ownerDocument;

    @Composition
    @OneToMany(mappedBy = "accessSet")
    private List<AccessEntry> entries;

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

    public Folder getOwnerFolder() {
        return ownerFolder;
    }

    public void setOwnerFolder(Folder ownerFolder) {
        this.ownerFolder = ownerFolder;
    }

    public Document getOwnerDocument() {
        return ownerDocument;
    }

    public void setOwnerDocument(Document ownerDocument) {
        this.ownerDocument = ownerDocument;
    }

    public List<AccessEntry> getEntries() {
        return entries;
    }

    public void setEntries(List<AccessEntry> entries) {
        this.entries = entries;
    }
}
