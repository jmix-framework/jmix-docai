package com.company.docai.stand;

import io.jmix.core.security.Authenticated;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * The job of the stand that deletes the copies of the visitors who have left: a copy no request of its browser has
 * used for longer than {@code docai.stand.copy-idle-timeout} goes with its database, indexes, files and cache entries,
 * see {@link CopyFactory#delete}. A copy leaves the registry only if it is still idle at that moment, so a request
 * that comes after the check keeps it. The next login of its browser starts with a fresh copy, and a session the
 * browser still has returns to the login form, see {@link StandScopeFilter}. Quartz runs the job every minute, see
 * {@link StandSchedulingConfiguration}, never two runs at once.
 */
@DisallowConcurrentExecution
public class CopyCleaner implements Job {

    private static final Logger log = LoggerFactory.getLogger(CopyCleaner.class);

    private final StandProperties properties;
    private final CopyRegistry registry;
    private final CopyFactory copyFactory;

    public CopyCleaner(StandProperties properties, CopyRegistry registry, CopyFactory copyFactory) {
        this.properties = properties;
        this.registry = registry;
        this.copyFactory = copyFactory;
    }

    /**
     * Deletes the idle copies as the system, on a thread of the scheduler, without a scope.
     */
    @Authenticated
    @Override
    public void execute(JobExecutionContext context) {
        deleteIdleCopies();
    }

    /**
     * Deletes the copies that are idle for longer than the timeout.
     */
    public void deleteIdleCopies() {
        Duration timeout = properties.copyIdleTimeout();

        for (Copy idle : registry.idleLongerThan(timeout)) {
            registry.removeIfIdleLongerThan(idle.key(), timeout)
                    .ifPresent(this::delete);
        }
    }

    private void delete(Copy copy) {
        try {
            copyFactory.delete(copy.key());
        } catch (RuntimeException e) {
            // one copy that fails keeps no other copy
            log.warn("An idle copy is not deleted: {}", StandScope.withTags(e.toString()));
        }
    }
}
