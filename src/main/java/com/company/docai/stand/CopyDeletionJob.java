package com.company.docai.stand;

import io.jmix.core.security.Authenticated;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The job of the stand that deletes a copy a browser has left for a new one, so no request waits for the deletion.
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
