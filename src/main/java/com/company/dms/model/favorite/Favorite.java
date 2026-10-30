package com.company.dms.model.favorite;

import com.company.dms.model.document.Document;
import com.company.dms.model.folder.Folder;
import com.company.dms.model.user.User;
import io.jmix.aitools.ExcludeFromAi;
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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.util.UUID;

/**
 * A mark of a person on a folder or a document they come back to often: exactly one of {@code folder} and
 * {@code document} is set. The mark keeps no name of its target: the name comes from the target when the mark is
 * shown, so a renamed target shows its new name and a target the person can no longer read shows nothing. A mark of
 * a target in the trash waits there with it. The marks of a deleted user go with the user.
 */
@ExcludeFromAi
@JmixEntity
@Entity
@Table(name = "FAVORITE", indexes = {
        @Index(name = "IDX_FAVORITE_FOLDER", columnList = "FOLDER_ID"),
        @Index(name = "IDX_FAVORITE_DOCUMENT", columnList = "DOCUMENT_ID")
}, uniqueConstraints = {
        @UniqueConstraint(name = "UK_FAVORITE_FOLDER", columnNames = {"USER_ID", "FOLDER_ID"}),
        @UniqueConstraint(name = "UK_FAVORITE_DOCUMENT", columnNames = {"USER_ID", "DOCUMENT_ID"})
})
public class Favorite {

    @JmixGeneratedValue
    @Column(name = "ID", nullable = false)
    @Id
    private UUID id;

    @Version
    @Column(name = "VERSION", nullable = false)
    private Integer version;

    @OnDeleteInverse(DeletePolicy.CASCADE)
    @JoinColumn(name = "USER_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User user;

    @JoinColumn(name = "FOLDER_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private Folder folder;

    @JoinColumn(name = "DOCUMENT_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private Document document;

    // the favorite service builds a mark with one target; this guards every other way of saving one
    @PrePersist
    @PreUpdate
    protected void checkTarget() {
        if ((folder == null) == (document == null)) {
            throw new IllegalStateException("A favorite marks exactly one folder or document");
        }
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

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
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
}
