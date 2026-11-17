package com.company.docai.stand;

import com.company.docai.DocAiApplication;
import com.company.docai.demo.DemoDataLoader;
import com.company.docai.test_support.StubAiConfiguration;
import com.vaadin.flow.spring.SpringVaadinServletService;
import io.jmix.autoconfigure.search.IndexingQueueProcessingScheduleAutoConfiguration;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.quartz.impl.JmixLocalDataSourceJobStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.quartz.impl.matchers.GroupMatcher;
import org.quartz.simpl.RAMJobStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.TaskSchedulerRouter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The start of the stand: the templates of the languages are built one after another in their order, and a login in
 * a language works as soon as its template is ready and waits while it is built; a start finds the templates of an
 * earlier run and uses them as they are, but builds again in a cleared folder a template without its ready marker or
 * without its indexes; the start deletes the folders of copies an earlier run left, and a template whose build fails
 * is tried again by the retry job. The jobs of the stand run on Quartz, which keeps them in memory: the cleaner
 * deletes a copy nobody has used for longer than the idle timeout, and the job of the search queues runs their loop as
 * the system. The stand brings no scheduler of its own, so Vaadin finds its executor. Search is off, so a spy says
 * which template has its indexes. The tests start the scheduler only while they need it, as the tests of the
 * application keep Quartz in standby.
 * <p>
 * The class has the configuration of {@link StandDataTest}, its spies included, so the two share a Spring context:
 * the heap of a full test run takes no further context of the stand.
 */
@SpringBootTest(classes = {DocAiApplication.class, StubAiConfiguration.class},
        properties = "docai.stand.data-dir=build/test-stand")
@ActiveProfiles({"test", "stand"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StandStartupTest {

    private static final Locale RUSSIAN = Locale.forLanguageTag("ru");

    // a start builds two templates, about ten seconds
    private static final Duration START = Duration.ofMinutes(3);

    @Autowired
    private StandStartup startup;

    @Autowired
    private CopyFactory copyFactory;

    @Autowired
    private CopyRegistry registry;

    @Autowired
    private StandLogin standLogin;

    @Autowired
    private StandProperties properties;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Scheduler scheduler;

    @Autowired
    private CurrentAuthentication currentAuthentication;

    // holds and notes the builds of the templates
    @MockitoSpyBean
    private DemoDataLoader loader;

    // says which templates have their indexes
    @MockitoSpyBean
    private StandIndexes indexes;

    @BeforeAll
    void startTheStand() throws Exception {
        start();
    }

    @AfterEach
    void deleteTheCopies() {
        registry.all()
                .forEach(copy -> copyFactory.delete(copy.key()));
    }

    @Test
    void theTemplatesAreBuiltOneAfterAnotherInTheirOrder() throws Exception {
        List<String> builds = new CopyOnWriteArrayList<>();

        doAnswer(invocation -> {
            String language = invocation.getArgument(0);
            builds.add(language + " loads, ready: " + readyLanguages());
            Object outcome = invocation.callRealMethod();
            builds.add(language + " is loaded");

            return outcome;
        }).when(loader).loadIfEmpty(anyString());

        // a new container: no template has its marker
        Files.delete(markerOf("en"));
        Files.delete(markerOf("ru"));
        start();

        assertThat(builds).containsExactly(
                "en loads, ready: []",
                "en is loaded",
                "ru loads, ready: [en]",
                "ru is loaded");
        assertThat(readyLanguages()).containsExactly("en", "ru");
    }

    @Test
    void loginWaitsWhileTheTemplateIsBuilding() throws Exception {
        CountDownLatch building = new CountDownLatch(1);
        CountDownLatch goOn = new CountDownLatch(1);

        // the build of ru stops right before its set loads, until the test lets it go on
        doAnswer(invocation -> {
            building.countDown();

            if (!goOn.await(START.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("The test did not let the build go on");
            }

            return invocation.callRealMethod();
        }).when(loader).loadIfEmpty("ru");

        Files.delete(markerOf("ru"));
        CompletableFuture<Void> start = startup.start();

        try {
            assertThat(building.await(START.toSeconds(), TimeUnit.SECONDS)).isTrue();
            assertThat(copyFactory.isReady("ru")).isFalse();
            assertThatThrownBy(() -> standLogin.prepare(RUSSIAN, "alice", "alice"))
                    .isInstanceOf(TemplateNotReadyException.class);
            assertThat(registry.all()).isEmpty();
            // the template before it in the order is ready, and a login in its language gets a copy
            assertThat(copyFactory.isReady("en")).isTrue();
            assertThat(newCopy("en")).isNotNull();
        } finally {
            goOn.countDown();
        }

        start.get(START.toSeconds(), TimeUnit.SECONDS);

        assertThat(copyFactory.isReady("ru")).isTrue();
        assertThat(registry.find(newCopy("ru"))).map(Copy::language).hasValue("ru");
    }

    // a restart of the same container keeps the data folder, and OpenSearch keeps its indexes
    @Test
    void aStartWithTheReadyMarkersBuildsNothing() throws Exception {
        start();
        List<FileTime> scripts = scriptsModified();
        clearInvocations(loader, indexes);

        start();

        verify(loader, never()).loadIfEmpty(anyString());
        verify(indexes, never()).renew(any());
        assertThat(scriptsModified()).isEqualTo(scripts);
        assertThat(readyLanguages()).containsExactly("en", "ru");
    }

    @Test
    void aTemplateWithoutItsMarkerIsBuiltAgainInAClearedFolder() throws Exception {
        start();

        Path stale = StandScope.template("en")
                .folder(properties.dataDir())
                .resolve("stale.txt");

        Files.writeString(stale, "a file of an earlier build that no row refers to");
        Files.delete(markerOf("en"));
        clearInvocations(loader);

        start();

        verify(loader).loadIfEmpty("en");
        verify(loader, never()).loadIfEmpty("ru");
        assertThat(stale).doesNotExist();
        assertThat(markerOf("en")).isRegularFile();
        assertThat(readyLanguages()).containsExactly("en", "ru");
    }

    // the indexes of a template are gone, as when OpenSearch has lost its data
    @Test
    void aTemplateWhoseIndexesAreGoneIsBuiltAgain() throws Exception {
        start();
        clearInvocations(loader);
        doReturn(false).when(indexes).exist(StandScope.template("ru"));

        start();

        verify(loader).loadIfEmpty("ru");
        verify(loader, never()).loadIfEmpty("en");
        assertThat(readyLanguages()).containsExactly("en", "ru");
    }

    @Test
    void theFoldersOfCopiesAnEarlierRunLeftAreDeletedAtTheStart() throws Exception {
        String live = newCopy("en");
        Path left = folderOf(CopyRegistry.newKey());
        Path stranger = left.resolveSibling("not-a-copy");

        Files.createDirectories(left.resolve(StandFileStorage.FILES));
        Files.writeString(left.resolve(Path.of(StandFileStorage.FILES, "note.txt")), "a file of an earlier run");
        Files.createDirectories(stranger);

        try {
            start();

            assertThat(left).doesNotExist();
            assertThat(folderOf(live)).isDirectory();
            assertThat(stranger).isDirectory();
        } finally {
            Files.deleteIfExists(stranger);
        }
    }

    // Quartz runs the job on a thread of its own, without a scope
    @Test
    void theCleanerJobDeletesACopyIdleLongerThanTheTimeoutAndKeepsAFreshOne() throws Exception {
        String idle = newCopy("en");
        String fresh = newCopy("en");

        Instant pastTheTimeout = Instant.now()
                .minus(properties.copyIdleTimeout())
                .minusSeconds(60);

        registry.find(idle)
                .orElseThrow()
                .lastAccess()
                .set(pastTheTimeout);

        runJob(StandSchedulingConfiguration.CLEANER, () -> !exists(StandScope.copy(idle))
                && Files.notExists(folderOf(idle)));

        assertThat(registry.find(idle)).isEmpty();
        assertThat(exists(StandScope.copy(idle))).isFalse();
        assertThat(folderOf(idle)).doesNotExist();
        assertThat(registry.find(fresh)).isPresent();
        assertThat(exists(StandScope.copy(fresh))).isTrue();
        assertThat(folderOf(fresh)).isDirectory();
    }

    // the add-on reads and writes the queue of each live copy as the system, in the scope of the copy
    @Test
    void theJobOfTheSearchQueuesRunsTheirLoopAsTheSystem() throws Exception {
        AtomicReference<String> user = new AtomicReference<>();

        doAnswer(invocation -> {
            user.compareAndSet(null, currentAuthentication.getUser()
                    .getUsername());

            return invocation.callRealMethod();
        }).when(indexes).processQueues();

        runJob(StandSchedulingConfiguration.SEARCH_QUEUES, () -> user.get() != null);

        assertThat(user.get()).isEqualTo("system");
    }

    // a deploy while OpenSearch is away: the build fails once, the job tries the template again
    @Test
    void aTemplateWhoseBuildFailsIsTriedAgainByTheRetryJob() throws Exception {
        doThrow(new IllegalStateException("OpenSearch does not answer"))
                .doCallRealMethod()
                .when(loader).loadIfEmpty("en");

        Files.delete(markerOf("en"));
        CompletableFuture<Void> started = startup.start();

        // the start schedules the try once en has failed, and only then checks ru
        await().atMost(START)
                .until(() -> !triesOfTemplates().isEmpty() && copyFactory.isReady("ru"));

        List<? extends Trigger> tries = triesOfTemplates();

        assertThat(tries).singleElement()
                .isInstanceOfSatisfying(SimpleTrigger.class, trigger -> assertThat(trigger.getStartTime())
                        .isInTheFuture());
        assertThat(copyFactory.isReady("en")).isFalse();
        assertThat(started).isNotDone();

        // the pause of the stand is a minute, so the try comes at once
        Trigger now = tries.getFirst()
                .getTriggerBuilder()
                .startNow()
                .build();

        scheduler.rescheduleJob(now.getKey(), now);
        scheduler.start();

        try {
            started.get(START.toSeconds(), TimeUnit.SECONDS);
        } finally {
            scheduler.standby();
        }

        assertThat(readyLanguages()).containsExactly("en", "ru");
        verify(loader, times(2)).loadIfEmpty("en");
    }

    // the cleaner and the loop of the search queues run whatever the add-ons enable, and each run of a job comes
    // after the one before is over; nothing of them outlives the application, as its control database does not
    @Test
    void theJobsOfTheStandAreQuartzJobsInMemoryThatDoNotOverlap() throws Exception {
        List<JobKey> jobs = List.of(StandSchedulingConfiguration.CLEANER, StandSchedulingConfiguration.SEARCH_QUEUES,
                StandSchedulingConfiguration.TEMPLATE_RETRY);

        assertThat(scheduler.getMetaData().getJobStoreClass()).isEqualTo(RAMJobStore.class);
        assertThat(scheduler.getJobKeys(GroupMatcher.jobGroupEquals(StandSchedulingConfiguration.GROUP)))
                .containsExactlyInAnyOrderElementsOf(jobs);
        assertThat(jobs).allSatisfy(job -> assertThat(concurrentExecutionDisallowed(job)).as(job.getName()).isTrue());
        assertThat(repeatOf(StandSchedulingConfiguration.CLEANER)).isEqualTo(Duration.ofMinutes(1));
        assertThat(repeatOf(StandSchedulingConfiguration.SEARCH_QUEUES)).isEqualTo(Duration.ofSeconds(5));
    }

    // Boot makes its executor next to the schedulers of Jmix only while the application brings no executor of its own
    @Test
    void theStandBringsNoSchedulerOfItsOwn() {
        assertThat(applicationContext.containsBean(TaskSchedulerRouter.DEFAULT_TASK_SCHEDULER_BEAN_NAME)).isFalse();
        assertThat(applicationContext.containsBean(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME))
                .isTrue();
        assertThat(applicationContext.getBeansOfType(Executor.class).values())
                .extracting(bean -> bean.getClass().getName())
                .noneMatch(name -> name.startsWith("com.company.docai"));
        assertThat(applicationContext.getBeansWithAnnotation(EnableScheduling.class).values())
                .extracting(bean -> bean.getClass().getPackageName())
                .doesNotContain(StandScope.class.getPackageName());
        assertThat(applicationContext.getEnvironment().getProperty("spring.task.execution.mode")).isNull();
    }

    // the servlet of Vaadin runs its tasks on the one plain executor of the context and does not start when it finds
    // schedulers only, such as the two of Jmix
    @Test
    void theServletOfVaadinFindsItsExecutorNextToTheSchedulers() {
        ServletService service = new ServletService(applicationContext);
        service.chooseExecutor();

        assertThatCode(service::getExecutor).doesNotThrowAnyException();
    }

    private void start() throws Exception {
        startup.start()
                .get(START.toSeconds(), TimeUnit.SECONDS);
    }

    // the job runs as Quartz runs it, made by Spring and on a thread of the scheduler, until the condition holds
    private void runJob(JobKey job, BooleanSupplier done) throws SchedulerException {
        scheduler.start();

        try {
            scheduler.triggerJob(job);

            await().atMost(Duration.ofMinutes(1))
                    .until(done::getAsBoolean);
        } finally {
            scheduler.standby();
        }
    }

    private List<? extends Trigger> triesOfTemplates() throws SchedulerException {
        return scheduler.getTriggersOfJob(StandSchedulingConfiguration.TEMPLATE_RETRY);
    }

    private boolean concurrentExecutionDisallowed(JobKey job) {
        try {
            return scheduler.getJobDetail(job)
                    .isConcurrentExecutionDisallowed();
        } catch (SchedulerException e) {
            throw new IllegalStateException(e);
        }
    }

    // the interval of the one trigger of the job, which repeats as long as the application runs
    private Duration repeatOf(JobKey job) throws SchedulerException {
        SimpleTrigger trigger = (SimpleTrigger) scheduler.getTriggersOfJob(job)
                .getFirst();

        assertThat(trigger.getRepeatCount()).isEqualTo(SimpleTrigger.REPEAT_INDEFINITELY);

        return Duration.ofMillis(trigger.getRepeatInterval());
    }

    private String newCopy(String language) {
        return copyFactory.create(language)
                .key();
    }

    private List<String> readyLanguages() {
        return properties.templateLanguages()
                .stream()
                .filter(copyFactory::isReady)
                .toList();
    }

    private List<FileTime> scriptsModified() throws IOException {
        return List.of(Files.getLastModifiedTime(inTemplate("en", TemplateBuilder.SCRIPT)),
                Files.getLastModifiedTime(inTemplate("ru", TemplateBuilder.SCRIPT)));
    }

    private Path markerOf(String language) {
        return inTemplate(language, TemplateBuilder.READY);
    }

    private Path inTemplate(String language, String name) {
        return StandScope.template(language)
                .folder(properties.dataDir())
                .resolve(name);
    }

    private Path folderOf(String key) {
        return StandScope.copy(key)
                .folder(properties.dataDir());
    }

    // ifexists=true does not create an empty database in place of one that was shut down
    private static boolean exists(StandScope scope) {
        try (Connection ignored = DriverManager.getConnection(scope.databaseUrl() + ";ifexists=true", "SA", "")) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    // the context that most tests share, without the profile of the stand
    @Nested
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @SpringBootTest
    class OutsideTheStand {

        @Autowired
        private Scheduler applicationScheduler;

        // the add-on of search keeps its jobs in the database of the application, as Jmix sets Quartz up
        @Test
        void quartzRunsTheJobsOfSearchAndNoneOfTheStand() throws SchedulerException {
            JobKey searchQueue = JobKey.jobKey(IndexingQueueProcessingScheduleAutoConfiguration.JOB_NAME);

            assertThat(applicationScheduler.getMetaData().getJobStoreClass())
                    .isEqualTo(JmixLocalDataSourceJobStore.class);
            assertThat(applicationScheduler.checkExists(searchQueue)).isTrue();
            assertThat(applicationScheduler.getJobKeys(GroupMatcher.jobGroupEquals(StandSchedulingConfiguration.GROUP)))
                    .isEmpty();
        }
    }

    // the service of the servlet of Vaadin, whose start chooses its executor among the beans of the context
    private static class ServletService extends SpringVaadinServletService {

        ServletService(ApplicationContext context) {
            super(null, null, context);
        }

        void chooseExecutor() {
            createDefaultExecutor();
        }
    }
}
