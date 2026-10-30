package com.company.docai.model.favorite;

import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import io.jmix.aitools.ExcludeFromAi;
import io.jmix.core.entity.annotation.JmixId;
import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import io.jmix.core.metamodel.annotation.JmixProperty;

import java.util.UUID;

/**
 * A row of the favorites block: a marked folder or document that the person may read and that is not in the trash.
 * Exactly one of {@code folder} and {@code document} is set, and the row has the id of its target.
 */
@ExcludeFromAi
@JmixEntity(annotatedPropertiesOnly = true)
public class FavoriteItem {

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
     * The folder the target lies in: the folder of a document, the parent of a folder; empty for a folder under
     * the root.
     */
    @JmixProperty
    private String location;

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
}
