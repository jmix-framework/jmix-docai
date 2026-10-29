package com.company.dms.app.service.tag;

import com.company.dms.app.service.OperationRejectedException;
import com.company.dms.model.tag.Tag;
import io.jmix.core.DataManager;
import io.jmix.core.Messages;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.data.PersistenceHints;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

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

    public TagService(DataManager dataManager, UnconstrainedDataManager unconstrainedDataManager, Messages messages) {
        this.dataManager = dataManager;
        this.unconstrainedDataManager = unconstrainedDataManager;
        this.messages = messages;
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
     * @throws OperationRejectedException if the name is empty or longer than {@link Tag#MAX_NAME_LENGTH}
     */
    public Tag findOrCreate(String name) {
        String stripped = checkName(name);

        return find(stripped).orElseGet(() -> {
            Tag tag = dataManager.create(Tag.class);
            tag.setName(stripped);
            return tag;
        });
    }

    /**
     * Returns the name without the spaces around it.
     *
     * @throws OperationRejectedException if the name is empty or longer than {@link Tag#MAX_NAME_LENGTH}
     */
    public String checkName(@Nullable String name) {
        String stripped = name == null ? "" : name.strip();

        if (stripped.isEmpty()) {
            throw new OperationRejectedException(messages.getMessage(getClass(), "tagNameEmpty"));
        }

        if (stripped.length() > Tag.MAX_NAME_LENGTH) {
            throw new OperationRejectedException(messages.formatMessage(getClass(), "tagNameTooLong",
                    stripped.substring(0, Tag.MAX_NAME_LENGTH), Tag.MAX_NAME_LENGTH));
        }

        return stripped;
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

        if (carriers > 0) {
            throw new OperationRejectedException(messages.formatMessage(getClass(), "tagInUse", tag.getName()));
        }

        dataManager.remove(tag);
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
}
