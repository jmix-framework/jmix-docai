package com.company.docai.app.service.document;

import com.company.docai.app.service.OperationRejectedException;
import com.company.docai.model.document.DocumentStatus;
import io.jmix.core.DataManager;
import io.jmix.core.EntityStates;
import io.jmix.core.FetchPlan;
import io.jmix.core.FetchPlanBuilder;
import io.jmix.core.Messages;
import io.jmix.core.SaveContext;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.data.PersistenceHints;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Reads the dictionary of document statuses and keeps it whole: one status is given to new documents, a name is
 * unique whatever its case, and a status that a document has, one in the trash included, is not deleted.
 */
@Service
public class DocumentStatusService {

    private final DataManager dataManager;
    // which status new documents get and whether a document has a status are questions of integrity: the documents
    // of the others count too
    private final UnconstrainedDataManager unconstrainedDataManager;
    private final EntityStates entityStates;
    private final Messages messages;

    public DocumentStatusService(DataManager dataManager, UnconstrainedDataManager unconstrainedDataManager,
                                 EntityStates entityStates, Messages messages) {
        this.dataManager = dataManager;
        this.unconstrainedDataManager = unconstrainedDataManager;
        this.entityStates = entityStates;
        this.messages = messages;
    }

    /**
     * Returns the status that new documents get.
     */
    public DocumentStatus getInitial() {
        return unconstrainedDataManager.load(DocumentStatus.class)
                .query("select s from DocumentStatus s where s.initial = true")
                .fetchPlan(DocumentStatusService::fetchPlan)
                .one();
    }

    /**
     * Returns the status with its flags and the statuses it moves to, as the database holds it. Within a long
     * transaction, such as the load of a demo set, a document may refer to its status by id only, so a rule reads
     * the status through here.
     */
    public DocumentStatus reload(DocumentStatus status) {
        return unconstrainedDataManager.load(DocumentStatus.class)
                .id(status.getId())
                .fetchPlan(DocumentStatusService::fetchPlan)
                .one();
    }

    /**
     * Returns all the statuses in their order, with the statuses each of them moves to.
     */
    public List<DocumentStatus> loadAll() {
        return dataManager.load(DocumentStatus.class)
                .query("select s from DocumentStatus s order by s.position, s.nameKey")
                .fetchPlan(DocumentStatusService::fetchPlan)
                .list();
    }

    /**
     * Returns the status with its flags and the statuses it moves to, unless it has been deleted.
     */
    public Optional<DocumentStatus> findById(UUID id) {
        return unconstrainedDataManager.load(DocumentStatus.class)
                .id(id)
                .fetchPlan(DocumentStatusService::fetchPlan)
                .optional();
    }

    /**
     * Returns the status and the statuses that a document in it may move to, in their order.
     */
    public List<DocumentStatus> loadChoices(DocumentStatus status) {
        List<DocumentStatus> all = loadAll();
        // the given status may come without its targets
        DocumentStatus current = all.stream()
                .filter(status::equals)
                .findFirst()
                .orElseThrow();

        return all.stream()
                .filter(other -> other.equals(current) || current.canMoveTo(other))
                .toList();
    }

    /**
     * Returns the statuses, in their order, that each document in one of the given statuses either has already or
     * may move to, leaving out a status that all of them have.
     */
    public List<DocumentStatus> loadCommonTargets(Collection<DocumentStatus> statuses) {
        List<DocumentStatus> all = loadAll();
        // the given statuses may come without their targets
        List<DocumentStatus> current = all.stream()
                .filter(statuses::contains)
                .toList();

        return all.stream()
                .filter(target -> current.stream()
                        .allMatch(status -> status.equals(target) || status.canMoveTo(target)))
                .filter(target -> current.stream()
                        .anyMatch(status -> !status.equals(target)))
                .toList();
    }

    /**
     * Returns the position after the last status, for a new one.
     */
    public int nextPosition() {
        Integer last = unconstrainedDataManager.loadValue("select max(s.position) from DocumentStatus s",
                        Integer.class)
                .one();

        return last == null ? 1 : last + 1;
    }

    /**
     * Puts the status right above or right below the target and numbers all the statuses from one again, in one
     * save. A status or a target deleted meanwhile leaves the order as it is.
     *
     * @param status the status to move
     * @param target the status it is dropped on
     * @param below  whether the status goes below the target rather than above it
     */
    public void move(DocumentStatus status, DocumentStatus target, boolean below) {
        List<DocumentStatus> order = new ArrayList<>(loadAll());
        int from = order.indexOf(status);

        if (from < 0 || status.equals(target) || !order.contains(target)) {
            return;
        }

        DocumentStatus moved = order.remove(from);
        int place = order.indexOf(target);
        order.add(below ? place + 1 : place, moved);

        SaveContext saveContext = new SaveContext();

        for (int i = 0; i < order.size(); i++) {
            DocumentStatus next = order.get(i);

            if (next.getPosition() != i + 1) {
                next.setPosition(i + 1);
                saveContext.saving(next);
            }
        }

        dataManager.save(saveContext);
    }

    /**
     * Returns the status with the name, whatever its case and the spaces around it.
     */
    public Optional<DocumentStatus> find(String name) {
        return unconstrainedDataManager.load(DocumentStatus.class)
                .query("select s from DocumentStatus s where s.nameKey = :key")
                .parameter("key", name.strip().toLowerCase(Locale.ROOT))
                .optional();
    }

    /**
     * Returns the name without the spaces around it.
     *
     * @throws OperationRejectedException if the name is empty, longer than {@link DocumentStatus#MAX_NAME_LENGTH}
     *                                    or holds a line break or another control character
     */
    public String checkName(@Nullable String name) {
        String stripped = name == null ? "" : name.strip();

        if (stripped.isEmpty()) {
            throw new OperationRejectedException(messages.getMessage(getClass(), "statusNameEmpty"));
        }

        if (stripped.chars().anyMatch(Character::isISOControl)) {
            throw new OperationRejectedException(messages.getMessage(getClass(), "statusNameControl"));
        }

        if (stripped.length() > DocumentStatus.MAX_NAME_LENGTH) {
            throw new OperationRejectedException(messages.formatMessage(getClass(), "statusNameTooLong",
                    stripped.substring(0, DocumentStatus.MAX_NAME_LENGTH), DocumentStatus.MAX_NAME_LENGTH));
        }

        return stripped;
    }

    /**
     * Saves the status. A status given to new documents takes that over from the status that had it, in the same
     * save, so the dictionary never has two of them or none.
     */
    public DocumentStatus save(DocumentStatus status) {
        // the dialog may still offer a status that another administrator has deleted meanwhile
        if (entityStates.isLoaded(status, "targets") && status.getTargets() != null
                && !status.getTargets().isEmpty()) {
            Set<UUID> stored = unconstrainedDataManager.loadValues(
                            "select s.id from DocumentStatus s where s.id in :ids")
                    .properties("id")
                    .parameter("ids", status.getTargets().stream()
                            .map(DocumentStatus::getId)
                            .toList())
                    .list()
                    .stream()
                    .map(value -> value.<UUID>getValue("id"))
                    .collect(Collectors.toSet());

            status.getTargets().stream()
                    .filter(target -> !stored.contains(target.getId()))
                    .findFirst()
                    .ifPresent(gone -> {
                        throw new OperationRejectedException(messages.formatMessage(getClass(), "statusTargetGone",
                                gone.getName()));
                    });
        }

        SaveContext saveContext = new SaveContext().saving(status);

        if (Boolean.TRUE.equals(status.getInitial())) {
            unconstrainedDataManager.load(DocumentStatus.class)
                    .query("select s from DocumentStatus s where s.initial = true and s.id <> :id")
                    .parameter("id", status.getId())
                    .list()
                    .forEach(other -> {
                        other.setInitial(false);
                        saveContext.saving(other);
                    });
        }

        return dataManager.save(saveContext).get(status);
    }

    /**
     * Deletes the status for good unless new documents get it or a document has it, a document in the trash
     * included: such a document can come back with its status. The statuses that list it as a target lose it in the
     * same save. The foreign key stops the other ways to delete a status in use.
     *
     * @throws OperationRejectedException if new documents get the status or a document has it
     */
    public void delete(DocumentStatus status) {
        // the copy of a list may be older than the flag, which another status may have taken over since
        Optional<DocumentStatus> stored = findById(status.getId());

        if (stored.isEmpty()) {
            return;
        }

        DocumentStatus current = stored.get();

        if (Boolean.TRUE.equals(current.getInitial())) {
            throw new OperationRejectedException(messages.formatMessage(getClass(), "statusInitialNotDeleted",
                    current.getName()));
        }

        long documents = unconstrainedDataManager.loadValue(
                        "select count(d) from Document d where d.status.id = :status", Long.class)
                .parameter("status", current.getId())
                .hint(PersistenceHints.SOFT_DELETION, false)
                .one();

        // the documents in the trash are out of sight, so the refusal says where they are
        if (documents > 0) {
            long live = unconstrainedDataManager.loadValue(
                            "select count(d) from Document d where d.status.id = :status", Long.class)
                    .parameter("status", current.getId())
                    .one();

            throw new OperationRejectedException(messages.formatMessage(getClass(),
                    live > 0 ? "statusInUse" : "statusInTrash", current.getName()));
        }

        // the keys of the join table do not cascade, as Studio maps them, so the lists change in the same save
        SaveContext saveContext = new SaveContext().setDiscardSaved(true);
        unconstrainedDataManager.load(DocumentStatus.class)
                .query("select s from DocumentStatus s join s.targets t where t.id = :status")
                .parameter("status", current.getId())
                .fetchPlan(DocumentStatusService::fetchPlan)
                .list()
                .forEach(other -> {
                    other.getTargets().remove(current);
                    saveContext.saving(other);
                });

        dataManager.save(saveContext.removing(current));
    }

    private static void fetchPlan(FetchPlanBuilder fetchPlan) {
        fetchPlan.addFetchPlan(FetchPlan.BASE)
                .add("targets", FetchPlan.BASE);
    }
}
