package com.company.docai.model.access;

import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.entity.annotation.JmixId;
import io.jmix.core.metamodel.annotation.Composition;
import io.jmix.core.metamodel.annotation.JmixEntity;
import io.jmix.core.metamodel.annotation.JmixProperty;

import java.util.List;
import java.util.UUID;

/**
 * The access of a folder or a document as the access dialog edits it; exactly one of {@code folder} and
 * {@code document} is set. While the item inherits, {@code entries} holds a copy of the inherited entries
 * to start from when the inheritance is broken.
 */
@JmixEntity(annotatedPropertiesOnly = true)
public class AccessDraft {

    @JmixId
    @JmixGeneratedValue
    @JmixProperty(mandatory = true)
    private UUID id;

    @JmixProperty
    private Folder folder;

    @JmixProperty
    private Document document;

    @JmixProperty
    private Boolean inherit;

    /**
     * The name of the folder or document that owns the set of the parent; null at the root, which cannot inherit.
     */
    @JmixProperty
    private String inheritedFrom;

    /**
     * The set in effect and its entries when the dialog opened: the service rejects the save if they changed.
     */
    @JmixProperty
    private String fingerprint;

    @Composition
    @JmixProperty
    private List<AccessEntryDraft> entries;

    @Composition
    @JmixProperty
    private List<AccessEntryDraft> inheritedEntries;

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

    public Boolean getInherit() {
        return inherit;
    }

    public void setInherit(Boolean inherit) {
        this.inherit = inherit;
    }

    public String getInheritedFrom() {
        return inheritedFrom;
    }

    public void setInheritedFrom(String inheritedFrom) {
        this.inheritedFrom = inheritedFrom;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public List<AccessEntryDraft> getEntries() {
        return entries;
    }

    public void setEntries(List<AccessEntryDraft> entries) {
        this.entries = entries;
    }

    public List<AccessEntryDraft> getInheritedEntries() {
        return inheritedEntries;
    }

    public void setInheritedEntries(List<AccessEntryDraft> inheritedEntries) {
        this.inheritedEntries = inheritedEntries;
    }
}
