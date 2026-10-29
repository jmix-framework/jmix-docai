package com.company.dms.model.trash;

import com.company.dms.model.document.Document;
import com.company.dms.model.folder.Folder;
import io.jmix.core.entity.annotation.JmixId;
import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import io.jmix.core.metamodel.annotation.JmixProperty;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A folder or a document in the trash, as the trash shows it: in its list, an item deleted on its own; in the
 * contents of a deleted folder, an item deleted together with the folder. Exactly one of {@code folder} and
 * {@code document} is set, and the item has its id.
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

    /**
     * The folders the item lay in: from the root in the list of the trash, from the deleted folder in its contents.
     */
    @JmixProperty
    private String location;

    /**
     * The name of the user who deleted the item, as the application shows the user.
     */
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
