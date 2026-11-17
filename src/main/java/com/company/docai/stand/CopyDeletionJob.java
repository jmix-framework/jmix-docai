package com.company.docai.stand;

import io.jmix.core.security.Authenticated;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The job of the stand that deletes a copy the browser of a visitor has left for a new one, after a login in another
 * language or a change of the language: the request does not wait for the deletion, which waits for a document of the
 * copy being indexed, see {@link StandLogin}. Each replaced copy gets a job of its own with a one-shot trigger.
 */
public class CopyDeletionJob implements Job {

    /**
     * The key of the copy in the data of the job.
     */
    public static final String COPY = "copy";

    private static final Logger log = LoggerFactory.getLogger(CopyDeletionJob.class);

    private final CopyFactory copyFactory;

    public CopyDeletionJob(CopyFactory copyFactory) {
        this.copyFactory = copyFactory;
    }

    /**
     * Deletes the copy of the data of the job as the system, see {@link CopyFactory#delete}.
     */
    @Authenticated
    @Override
    public void execute(JobExecutionContext context) {
        try {
            copyFactory.delete(context.getMergedJobDataMap()
                    .getString(COPY));
        } catch (RuntimeException e) {
            log.warn("A replaced copy is not deleted: {}", StandScope.withTags(e.toString()));
        }
    }
}
