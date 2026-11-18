package com.company.docai.stand;

import io.jmix.core.security.Authenticated;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;

/**
 * The job of the stand that tries a template again after the pause, see {@link StandStartup}.
 */
@DisallowConcurrentExecution
public class TemplateRetryJob implements Job {

    private final StandStartup startup;

    public TemplateRetryJob(StandStartup startup) {
        this.startup = startup;
    }

    @Authenticated
    @Override
    public void execute(JobExecutionContext context) {
        try {
            startup.retry(context.getMergedJobDataMap());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
