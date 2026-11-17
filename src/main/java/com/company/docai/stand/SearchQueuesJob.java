package com.company.docai.stand;

import io.jmix.core.security.Authenticated;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;

/**
 * The job of the stand that processes the search queues of the copies, see {@link StandIndexes#processQueues}. Quartz
 * runs it every five seconds, see {@link StandSchedulingConfiguration}, never two runs at once.
 */
@DisallowConcurrentExecution
public class SearchQueuesJob implements Job {

    private final StandIndexes indexes;

    public SearchQueuesJob(StandIndexes indexes) {
        this.indexes = indexes;
    }

    /**
     * Processes the next batch of the search queue of each live copy as the system.
     */
    @Authenticated
    @Override
    public void execute(JobExecutionContext context) {
        indexes.processQueues();
    }
}
