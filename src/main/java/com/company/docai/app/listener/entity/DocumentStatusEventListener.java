package com.company.docai.app.listener.entity;

import com.company.docai.app.service.OperationRejectedException;
import com.company.docai.app.service.document.DocumentStatusService;
import com.company.docai.model.document.DocumentStatus;
import io.jmix.core.EntityStates;
import io.jmix.core.Messages;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.event.EntityChangedEvent;
import io.jmix.core.event.EntitySavingEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Keeps the dictionary of statuses whole on every save path: a name loses the spaces around it, is not empty, fits
 * {@link DocumentStatus#MAX_NAME_LENGTH} and differs from the names of the other statuses whatever the case; a color
 * is {@code #rrggbb}; a status does not list itself; and exactly one status is given to new documents, and it does not
 * lock them.
 */
@Component
public class DocumentStatusEventListener {

    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");

    private final DocumentStatusService statusService;
    private final UnconstrainedDataManager dataManager;
    private final EntityStates entityStates;
    private final Messages messages;

    public DocumentStatusEventListener(DocumentStatusService statusService, UnconstrainedDataManager dataManager,
                                       EntityStates entityStates, Messages messages) {
        this.statusService = statusService;
        this.dataManager = dataManager;
        this.entityStates = entityStates;
        this.messages = messages;
    }

    @EventListener
    public void onStatusSaving(EntitySavingEvent<DocumentStatus> event) {
        DocumentStatus status = event.getEntity();
        status.setName(statusService.checkName(status.getName()));

        if (status.getColor() == null || !COLOR.matcher(status.getColor()).matches()) {
            throw new OperationRejectedException(messages.getMessage(getClass(), "statusColorInvalid"));
        }

        // the first version of a new document is a change that a locked status refuses
        if (Boolean.TRUE.equals(status.getInitial()) && Boolean.TRUE.equals(status.getLocked())) {
            throw new OperationRejectedException(messages.getMessage(getClass(), "statusInitialLocked"));
        }

        statusService.find(status.getName())
                .filter(namesake -> !namesake.equals(status))
                .ifPresent(namesake -> {
                    throw new OperationRejectedException(messages.formatMessage(getClass(), "statusNameTaken",
                            namesake.getName()));
                });

        if (entityStates.isLoaded(status, "targets") && status.getTargets() != null) {
            status.getTargets().remove(status);
        }
    }

    // the check runs after the save is flushed, when the status that takes over the flag and the one that gives it
    // up are both written
    @EventListener
    public void onStatusChanged(EntityChangedEvent<DocumentStatus> event) {
        long initial = dataManager.loadValue("select count(s) from DocumentStatus s where s.initial = true",
                        Long.class)
                .one();

        if (initial != 1) {
            throw new OperationRejectedException(messages.getMessage(getClass(), "statusInitialRequired"));
        }
    }
}
