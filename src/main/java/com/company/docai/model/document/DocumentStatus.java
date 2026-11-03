package com.company.docai.model.document;

import io.jmix.aitools.ExcludeFromAi;
import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A status of documents from the dictionary that the administrator keeps: a name, a color and a place in the lists,
 * and the rules of the documents in it. A new document gets the one initial status; a document moves to the statuses
 * its status lists, or to any status when its status allows that; a locked status keeps the name, the date, the type
 * with its fields and the version of its documents. A name is unique whatever its case. A status is deleted for good,
 * and only while no document has it.
 */
@JmixEntity
@Entity
@Table(name = "DOCUMENT_STATUS", uniqueConstraints = {
        @UniqueConstraint(name = "UK_DOCUMENT_STATUS_NAME_KEY", columnNames = "NAME_KEY")
})
public class DocumentStatus {

    /**
     * The longest name of a status.
     */
    public static final int MAX_NAME_LENGTH = 50;

    /**
     * The id of the default initial status Draft, as the changelog creates it. Only the demo sets and the tests refer
     * to the default statuses: the rules read the flags.
     */
    public static final UUID DRAFT_ID = UUID.fromString("0f6b1b3e-5c1a-4b9e-8a52-6a1f3d2c7e01");

    /**
     * The id of the default status Active, as the changelog creates it.
     */
    public static final UUID ACTIVE_ID = UUID.fromString("0f6b1b3e-5c1a-4b9e-8a52-6a1f3d2c7e02");

    /**
     * The id of the default locked status Archived, as the changelog creates it.
     */
    public static final UUID ARCHIVED_ID = UUID.fromString("0f6b1b3e-5c1a-4b9e-8a52-6a1f3d2c7e03");

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

    @NotNull
    @Pattern(regexp = "#[0-9a-fA-F]{6}")
    @Column(name = "COLOR", nullable = false, length = 7)
    private String color;

    @NotNull
    @Column(name = "POSITION_", nullable = false)
    private Integer position;

    @NotNull
    @Column(name = "INITIAL_", nullable = false)
    private Boolean initial = false;

    @NotNull
    @Column(name = "LOCKED", nullable = false)
    private Boolean locked = false;

    @NotNull
    @Column(name = "ANY_TARGET", nullable = false)
    private Boolean anyTarget = true;

    @OrderBy("position")
    @JoinTable(name = "DOCUMENT_STATUS_TARGET",
            joinColumns = @JoinColumn(name = "STATUS_ID", referencedColumnName = "ID"),
            inverseJoinColumns = @JoinColumn(name = "TARGET_ID", referencedColumnName = "ID"),
            indexes = @Index(name = "IDX_DOCUMENT_STATUS_TARGET_TARGET", columnList = "TARGET_ID"))
    @ManyToMany
    private List<DocumentStatus> targets;

    @PrePersist
    @PreUpdate
    protected void updateNameKey() {
        nameKey = name.toLowerCase(Locale.ROOT);
    }

    /**
     * Tells whether a document in this status may move to the target: to any other status when this one allows it,
     * otherwise to one this status lists.
     */
    public boolean canMoveTo(DocumentStatus target) {
        return !equals(target)
                && (Boolean.TRUE.equals(anyTarget) || targets != null && targets.contains(target));
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

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public Integer getPosition() {
        return position;
    }

    public void setPosition(Integer position) {
        this.position = position;
    }

    public Boolean getInitial() {
        return initial;
    }

    public void setInitial(Boolean initial) {
        this.initial = initial;
    }

    public Boolean getLocked() {
        return locked;
    }

    public void setLocked(Boolean locked) {
        this.locked = locked;
    }

    public Boolean getAnyTarget() {
        return anyTarget;
    }

    public void setAnyTarget(Boolean anyTarget) {
        this.anyTarget = anyTarget;
    }

    public List<DocumentStatus> getTargets() {
        return targets;
    }

    public void setTargets(List<DocumentStatus> targets) {
        this.targets = targets;
    }
}
