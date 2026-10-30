package com.company.docai.model.document;

import io.jmix.aitools.ExcludeFromAi;
import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A comment a person left on a document: plain text that everyone who sees the document reads. It is saved at once,
 * apart from the card, and is no part of the document: the document has no list of its comments, and a change of a
 * comment changes nothing in the document. Only its author changes it. A deleted comment is gone for good; the trash
 * keeps the comments of a deleted document hidden with it.
 */
@ExcludeFromAi
@JmixEntity
@Entity
@Table(name = "DOCUMENT_COMMENT", indexes = {
        @Index(name = "IDX_DOCUMENT_COMMENT_DOCUMENT", columnList = "DOCUMENT_ID")
})
public class DocumentComment {

    /**
     * How many characters a comment holds.
     */
    public static final int MAX_TEXT_LENGTH = 2000;

    @JmixGeneratedValue
    @Column(name = "ID", nullable = false)
    @Id
    private UUID id;

    @Version
    @Column(name = "VERSION", nullable = false)
    private Integer version;

    @JoinColumn(name = "DOCUMENT_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Document document;

    @NotBlank
    @Size(max = MAX_TEXT_LENGTH)
    @Column(name = "TEXT_", nullable = false, length = MAX_TEXT_LENGTH)
    private String text;

    /**
     * When the author changed the text last, or null for a comment never changed.
     */
    @Column(name = "EDITED_DATE")
    private OffsetDateTime editedDate;

    @CreatedBy
    @Column(name = "CREATED_BY")
    private String createdBy;

    @CreatedDate
    @Column(name = "CREATED_DATE")
    private OffsetDateTime createdDate;

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

    public Document getDocument() {
        return document;
    }

    public void setDocument(Document document) {
        this.document = document;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public OffsetDateTime getEditedDate() {
        return editedDate;
    }

    public void setEditedDate(OffsetDateTime editedDate) {
        this.editedDate = editedDate;
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
}
