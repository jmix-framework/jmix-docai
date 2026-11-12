package com.company.docai.app.service.tag;

import com.company.docai.app.service.OperationRejectedException;
import com.company.docai.model.document.Document;
import com.company.docai.model.tag.Tag;
import io.jmix.core.DataManager;
import io.jmix.core.EntityStates;
import io.jmix.core.Messages;
import io.jmix.core.QueryUtils;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.data.PersistenceHints;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Finds the tags of the shared vocabulary by name, whatever the case and the spaces around it, and makes new ones.
 * A new tag is not saved here: it joins the vocabulary when the document that carries it is saved. A tag is deleted
 * here, and only while no document carries it.
 */
@Service
public class TagService {

    private final DataManager dataManager;
    // whether a document carries a tag is a question of integrity: the documents of the others count too
    private final UnconstrainedDataManager unconstrainedDataManager;
    private final Messages messages;
    private final EntityStates entityStates;

    public TagService(DataManager dataManager, UnconstrainedDataManager unconstrainedDataManager, Messages messages,
                      EntityStates entityStates) {
        this.dataManager = dataManager;
        this.unconstrainedDataManager = unconstrainedDataManager;
        this.messages = messages;
        this.entityStates = entityStates;
    }

    /**
     * Returns the tag with the name, whatever its case and the spaces around it.
     */
    public Optional<Tag> find(String name) {
        return dataManager.load(Tag.class)
                .query("select t from Tag t where t.nameKey = :key")
                .parameter("key", name.strip().toLowerCase(Locale.ROOT))
                .optional();
    }

    /**
     * Returns the tag of the vocabulary with the name, whatever its case, or a new unsaved tag with the name
     * without the spaces around it.
     *
     * @throws OperationRejectedException if the name is empty, longer than {@link Tag#MAX_NAME_LENGTH} or holds
     *                                    a line break or another control character
     */
    public Tag findOrCreate(String name) {
        String stripped = checkName(name);

        return find(stripped)
                .orElseGet(() -> {
                    Tag tag = dataManager.create(Tag.class);
                    tag.setName(stripped);
                    return tag;
                });
    }

    /**
     * Returns the name without the spaces around it.
     *
     * @throws OperationRejectedException if the name is empty, longer than {@link Tag#MAX_NAME_LENGTH} or holds
     *                                    a line break or another control character
     */
    public String checkName(@Nullable String name) {
        String stripped = name == null ? "" : name.strip();

        if (stripped.isEmpty()) {
            throw new OperationRejectedException(messages.getMessage(getClass(), "tagNameEmpty"));
        }

        if (stripped.chars().anyMatch(Character::isISOControl)) {
            throw new OperationRejectedException(messages.getMessage(getClass(), "tagNameControl"));
        }

        if (stripped.length() > Tag.MAX_NAME_LENGTH) {
            throw new OperationRejectedException(messages.formatMessage(getClass(), "tagNameTooLong",
                    stripped.substring(0, Tag.MAX_NAME_LENGTH), Tag.MAX_NAME_LENGTH));
        }

        return stripped;
    }

    /**
     * Puts the tags of the vocabulary in place of the tags of the document: a new tag whose name the vocabulary got
     * meanwhile becomes that tag, a tag deleted meanwhile drops out, and a name twice stays once, whatever its case.
     * Returns the new tags, which are saved with the document. A document loaded without its tags keeps them.
     */
    public List<Tag> keepVocabulary(Document document) {
        if (!entityStates.isLoaded(document, "tags") || document.getTags() == null) {
            return List.of();
        }

        List<UUID> storedIds = document.getTags()
                .stream()
                .filter(tag -> !entityStates.isNew(tag))
                .map(Tag::getId)
                .toList();

        Map<UUID, Tag> stored = storedIds.isEmpty()
                ? Map.of()
                : dataManager.load(Tag.class)
                        .query("select t from Tag t where t.id in :ids")
                        .parameter("ids", storedIds)
                        .list()
                        .stream()
                        .collect(Collectors.toMap(Tag::getId, Function.identity()));

        Map<String, Tag> tags = new LinkedHashMap<>();

        for (Tag tag : document.getTags()) {
            Tag kept = entityStates.isNew(tag)
                    ? find(tag.getName())
                            .orElse(tag)
                    : stored.get(tag.getId());

            if (kept != null) {
                tags.putIfAbsent(kept.getName().toLowerCase(Locale.ROOT),
                        kept);
            }
        }

        document.setTags(new ArrayList<>(tags.values()));

        return tags.values()
                .stream()
                .filter(entityStates::isNew)
                .toList();
    }

    /**
     * Deletes the tag for good unless a document carries it, a document in the trash included: such a document can
     * come back with its tags. The foreign key of the link table stops the other ways to delete a tag in use.
     *
     * @throws OperationRejectedException if a document carries the tag
     */
    public void delete(Tag tag) {
        long carriers = unconstrainedDataManager.loadValue(
                        "select count(d) from Document d join d.tags t where t.id = :tag", Long.class)
                .parameter("tag", tag.getId())
                .hint(PersistenceHints.SOFT_DELETION, false)
                .one();

        // the documents in the trash are out of sight, so the refusal says where they are
        if (carriers > 0) {
            throw new OperationRejectedException(messages.formatMessage(getClass(),
                    countLiveCarriers(tag) > 0 ? "tagInUse" : "tagInTrash", tag.getName()));
        }

        dataManager.remove(tag);
    }

    /**
     * Tells whether a document in the trash that the user may see carries the tag.
     */
    public boolean isInTrash(Tag tag) {
        return dataManager.loadValue("select count(d) from Document d join d.tags t"
                        + " where t.id = :tag and d.deletedDate is not null", Long.class)
                .parameter("tag", tag.getId())
                .hint(PersistenceHints.SOFT_DELETION, false)
                .one() > 0;
    }

    /**
     * Returns the names of all the tags in alphabetical order.
     */
    public List<String> loadNames() {
        return dataManager.loadValues("select t.name from Tag t order by t.nameKey")
                .properties("name")
                .list()
                .stream()
                .map(value -> value.<String>getValue("name"))
                .toList();
    }

    /**
     * Returns at most {@code max} tags whose name contains the text, whatever its case and the spaces around it, in
     * alphabetical order, each with the number of the live documents with it that the user may see. A tag without such
     * documents is left out, so the number never tells of the documents of the others; a percent sign or an
     * underscore in the text is matched as it is.
     */
    public List<TagCount> findByName(String text, int max) {
        String part = text.strip().toLowerCase(Locale.ROOT);

        return dataManager.loadValues("select t.id, t.name, count(d) from Document d join d.tags t"
                        + " where t.nameKey like :part escape '\\'"
                        + " group by t.id, t.name, t.nameKey order by t.nameKey")
                .properties("id", "name", "documents")
                .parameter("part", "%" + QueryUtils.escapeForLike(part) + "%")
                .maxResults(max)
                .list()
                .stream()
                .map(value -> new TagCount(value.getValue("name"), value.<Long>getValue("documents")))
                .toList();
    }

    private long countLiveCarriers(Tag tag) {
        return unconstrainedDataManager.loadValue(
                        "select count(d) from Document d join d.tags t where t.id = :tag", Long.class)
                .parameter("tag", tag.getId())
                .one();
    }
}
