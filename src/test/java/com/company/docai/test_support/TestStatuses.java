package com.company.docai.test_support;

import com.company.docai.model.document.DocumentStatus;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.UnconstrainedDataManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The default statuses that the changelog creates, and new statuses for the tests that need their own rules.
 */
public final class TestStatuses {

    private TestStatuses() {
    }

    /**
     * The default initial status, Draft: it moves to Active.
     */
    public static DocumentStatus draft(DataManager dataManager) {
        return load(dataManager, DocumentStatus.DRAFT_ID);
    }

    /**
     * The default status Active: it moves to Archived.
     */
    public static DocumentStatus active(DataManager dataManager) {
        return load(dataManager, DocumentStatus.ACTIVE_ID);
    }

    /**
     * The default locked status Archived: it moves to Active.
     */
    public static DocumentStatus archived(DataManager dataManager) {
        return load(dataManager, DocumentStatus.ARCHIVED_ID);
    }

    /**
     * Saves a new status with a unique name after the prefix, moving to any status or to the listed ones.
     */
    public static DocumentStatus create(UnconstrainedDataManager dataManager, String prefix, boolean locked,
                                        List<DocumentStatus> targets) {
        DocumentStatus status = dataManager.create(DocumentStatus.class);
        status.setName(prefix + " " + UUID.randomUUID().toString().substring(0, 8));
        status.setColor("#3266e4");
        status.setPosition(100);
        status.setLocked(locked);
        status.setAnyTarget(targets.isEmpty());
        status.setTargets(new ArrayList<>(targets));
        return dataManager.save(status);
    }

    private static DocumentStatus load(DataManager dataManager, UUID id) {
        return dataManager.load(DocumentStatus.class)
                .id(id)
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("targets", FetchPlan.BASE))
                .one();
    }
}
