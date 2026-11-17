package com.company.docai.stand;

import io.jmix.core.security.Authenticated;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;

/**
 * The job of the stand that tries a template again once the pause after its failed build is over: the start gives it
 * a one-shot trigger for each failure, see {@link StandStartup}. One try runs at a time, and none while the start
 * builds a template.
 */
@DisallowConcurrentExecution
public class TemplateRetryJob implements Job {

    private final StandStartup startup;

    public TemplateRetryJob(StandStartup startup) {
        this.startup = startup;
    }

    /**
     * Tries the template of the trigger again as the system.
     */
    @Authenticated
    @Override
    public void execute(JobExecutionContext context) {
        try {
            startup.retry(context.getMergedJobDataMap());
        } catch (InterruptedException e) {
            // the application stops
            Thread.currentThread().interrupt();
        }
    }
}
