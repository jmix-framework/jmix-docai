package com.company.docai.stand;

import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * The jobs of the stand on the Quartz scheduler of the application.
 */
@Configuration(proxyBeanMethods = false)
@Profile("stand")
public class StandSchedulingConfiguration {

    /**
     * The group of the jobs and the triggers of the stand.
     */
    public static final String GROUP = "stand";

    /**
     * The job that deletes the idle copies, see {@link CopyCleaner}.
     */
    public static final JobKey CLEANER = JobKey.jobKey("CopyCleaner", GROUP);

    /**
     * The job that processes the search queues of the copies, see {@link SearchQueuesJob}.
     */
    public static final JobKey SEARCH_QUEUES = JobKey.jobKey("SearchQueues", GROUP);

    /**
     * The job that tries a template again, see {@link TemplateRetryJob}.
     */
    public static final JobKey TEMPLATE_RETRY = JobKey.jobKey("TemplateRetry", GROUP);

    private static final int CLEANER_MINUTES = 1;
    private static final int SEARCH_QUEUES_SECONDS = 5;

    /**
     * The job of the cleaner of the copies.
     */
    @Bean
    public JobDetail copyCleanerJob() {
        return JobBuilder.newJob(CopyCleaner.class)
                .withIdentity(CLEANER)
                .storeDurably()
                .build();
    }

    /**
     * Runs the cleaner of the copies every minute; a run that is due while the one before still runs waits for it.
     */
    @Bean
    public Trigger copyCleanerTrigger() {
        return TriggerBuilder.newTrigger()
                .forJob(CLEANER)
                .withIdentity(CLEANER.getName(), GROUP)
                .startNow()
                .withSchedule(SimpleScheduleBuilder.repeatMinutelyForever(CLEANER_MINUTES)
                        .withMisfireHandlingInstructionNextWithRemainingCount())
                .build();
    }

    /**
     * The job of the loop of the search queues.
     */
    @Bean
    public JobDetail searchQueuesJob() {
        return JobBuilder.newJob(SearchQueuesJob.class)
                .withIdentity(SEARCH_QUEUES)
                .storeDurably()
                .build();
    }

    /**
     * Runs the loop of the search queues every five seconds, a due run waiting for the one before.
     */
    @Bean
    public Trigger searchQueuesTrigger() {
        return TriggerBuilder.newTrigger()
                .forJob(SEARCH_QUEUES)
                .withIdentity(SEARCH_QUEUES.getName(), GROUP)
                .startNow()
                .withSchedule(SimpleScheduleBuilder.repeatSecondlyForever(SEARCH_QUEUES_SECONDS)
                        .withMisfireHandlingInstructionNextWithRemainingCount())
                .build();
    }

    /**
     * The job that tries a template again; it has no trigger of its own until a build of a template fails.
     */
    @Bean
    public JobDetail templateRetryJob() {
        return JobBuilder.newJob(TemplateRetryJob.class)
                .withIdentity(TEMPLATE_RETRY)
                .storeDurably()
                .build();
    }
}
