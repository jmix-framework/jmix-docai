package com.company.docai.model.trash;

import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import io.jmix.core.entity.annotation.JmixId;
import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import io.jmix.core.metamodel.annotation.JmixProperty;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A folder or a document in the trash, as the trash shows it: in its list or in the contents of a deleted folder.
 * Exactly one of {@code folder} and {@code document} is set, and the item has its id.
 */
@JmixEntity(annotatedPropertiesOnly = true)
public class TrashItem {

    @JmixId
    @JmixProperty(mandatory = true)
    private UUID id;

    @JmixProperty
    private Folder folder;

    @JmixProperty
    private Document document;

    @InstanceName
    @JmixProperty
    private String name;

    @JmixProperty
    private String location;

    @JmixProperty
    private String deletedBy;

    @JmixProperty
    private OffsetDateTime deletedDate;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Folder getFolder() {
        return folder;
    }

    public void setFolder(Folder folder) {
        this.folder = folder;
    }

    public Document getDocument() {
        return document;
    }

    public void setDocument(Document document) {
        this.document = document;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
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
