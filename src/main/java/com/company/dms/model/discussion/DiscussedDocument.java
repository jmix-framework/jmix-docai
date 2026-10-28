package com.company.dms.model.discussion;

import com.company.dms.model.document.Document;
import io.jmix.aitools.ExcludeFromAi;
import io.jmix.aitoolsflowuidata.entity.AiConversationEntity;
import io.jmix.core.DeletePolicy;
import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.entity.annotation.OnDeleteInverse;
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
 * A document of the selection that a conversation of the assistant is about. A document is in the selection of a
 * conversation once, and the selection keeps the order in which the person added its documents.
 */
@ExcludeFromAi
@JmixEntity
@Entity
@Table(name = "DISCUSSED_DOCUMENT", indexes = {
        @Index(name = "IDX_DISCUSSED_DOCUMENT_DOCUMENT", columnList = "DOCUMENT_ID")
}, uniqueConstraints = {
        @UniqueConstraint(name = "UK_DISCUSSED_DOCUMENT", columnNames = {"CONVERSATION_ID", "DOCUMENT_ID"})
})
public class DiscussedDocument {

    @JmixGeneratedValue
    @Column(name = "ID", nullable = false)
    @Id
    private UUID id;

    @Version
    @Column(name = "VERSION", nullable = false)
    private Integer version;

    // IntelliJ counts only the project's classes as entities, not the add-on's conversation
    @SuppressWarnings("JpaAttributeTypeInspection")
    @OnDeleteInverse(DeletePolicy.CASCADE)
    @JoinColumn(name = "CONVERSATION_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private AiConversationEntity conversation;

    @OnDeleteInverse(DeletePolicy.CASCADE)
    @JoinColumn(name = "DOCUMENT_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Document document;

    @Column(name = "POSITION_", nullable = false)
    private Integer position;

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

    public AiConversationEntity getConversation() {
        return conversation;
    }

    public void setConversation(AiConversationEntity conversation) {
        this.conversation = conversation;
    }

    public Document getDocument() {
        return document;
    }

    public void setDocument(Document document) {
        this.document = document;
    }

    public Integer getPosition() {
        return position;
    }

    public void setPosition(Integer position) {
        this.position = position;
    }
}
