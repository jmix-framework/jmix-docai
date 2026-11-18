package com.company.docai.stand;

import io.jmix.core.security.Authenticated;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * The job of the stand that deletes the copies unused for longer than {@code docai.stand.copy-idle-timeout}; a request
 * that comes after the check keeps its copy.
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
            log.warn("An idle copy is not deleted: {}", StandScope.withTags(e.toString()));
        }
    }
}
