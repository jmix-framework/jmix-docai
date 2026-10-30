package com.company.docai.model.tag;

import io.jmix.aitools.ExcludeFromAi;
import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;

import java.util.Locale;
import java.util.UUID;

/**
 * A tag of the shared vocabulary that marks documents. A name is unique whatever its case: the database holds it
 * through the key of the name, and the tag listener refuses a namesake with a message. A tag knows nothing of its
 * documents: the document owns the association, so saving a tag never changes a document. A tag is deleted for good,
 * and only while no document carries it.
 */
@JmixEntity
@Entity
@Table(name = "TAG", uniqueConstraints = {
        @UniqueConstraint(name = "UK_TAG_NAME_KEY", columnNames = "NAME_KEY")
})
public class Tag {

    /**
     * The longest name of a tag.
     */
    public static final int MAX_NAME_LENGTH = 50;

    @JmixGeneratedValue
    @Column(name = "ID", nullable = false)
    @Id
    private UUID id;

    @Version
    @Column(name = "VERSION", nullable = false)
    private Integer version;

    @InstanceName
    @NotBlank
    @Column(name = "NAME", nullable = false, length = MAX_NAME_LENGTH)
    private String name;

    /**
     * The name in lower case, made by the application, so that the unique constraint holds whatever the case on any
     * database and also against two saves at once.
     */
    @ExcludeFromAi
    @Column(name = "NAME_KEY", nullable = false, length = MAX_NAME_LENGTH)
    private String nameKey;

    @PrePersist
    @PreUpdate
    protected void updateNameKey() {
        nameKey = name.toLowerCase(Locale.ROOT);
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
}
