package com.company.docai.stand;

import io.jmix.core.TimeSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.invocation.InvocationOnMock;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.simpl.SimpleThreadPool;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How the start of the stand gets over a failure: a template whose build fails, as when OpenSearch does not answer, is
 * tried again and again by a one-shot trigger of the retry job on Quartz, after a pause that doubles from the first
 * one up to the longest, with an ERROR line for each failure, while the next template is made ready; a template whose
 * indexes cannot be checked is left as it is for the next try. No template takes a login before the start has deleted
 * what an earlier run left and checked the template, a try of an earlier start does nothing, and the start ends with
 * the application. The builder and the indexes are stand-ins, the folders and the scheduler are real.
 */
@ExtendWith(OutputCaptureExtension.class)
class StandStartupRetryTest {

    private static final Duration PAUSE = Duration.ofMillis(50);

    private static final Duration LONGEST_PAUSE = Duration.ofMillis(200);

    // Quartz fires a trigger up to two milliseconds before its time, which it keeps in whole milliseconds
    private static final Duration FIRING_PRECISION = Duration.ofMillis(3);

    private final TemplateBuilder templateBuilder = mock(TemplateBuilder.class);
    private final CopyFactory copyFactory = mock(CopyFactory.class);
    private final StandIndexes indexes = mock(StandIndexes.class);
    private final TimeSource timeSource = mock(TimeSource.class);

    @TempDir
    private Path dataDir;

    private StandFileStorage files;
    private Scheduler scheduler;
    private StandStartup startup;

    @BeforeEach
    void setUp() throws SchedulerException {
        when(timeSource.now()).thenReturn(ZonedDateTime.now());
        when(indexes.exist(any())).thenReturn(true);

        StandProperties properties = properties(List.of("en", "ru"));
        files = spy(new StandFileStorage(properties, mock(StandUploadGuard.class)));
        scheduler = inMemoryScheduler();

        startup = new StandStartup(properties, templateBuilder, copyFactory, new CopyRegistry(timeSource), indexes,
                files, timeSource, scheduler);

        // the job and its making as the configuration of the stand and Spring give them
        scheduler.setJobFactory((bundle, ignored) -> new TemplateRetryJob(startup));
        scheduler.addJob(new StandSchedulingConfiguration().templateRetryJob(), false);
        scheduler.start();
    }

    @AfterEach
    void stopTheStart() throws SchedulerException {
        startup.stop();
        scheduler.shutdown(true);
    }

    // OpenSearch away for a while, as at a deploy, then back
    @Test
    void aTemplateThatFailsIsTriedAgainAfterAPauseThatDoublesUpToTheLongest(CapturedOutput output) throws Exception {
        List<Long> tries = new CopyOnWriteArrayList<>();

        when(templateBuilder.build("en")).thenAnswer(invocation -> {
            tries.add(System.nanoTime());

            if (tries.size() <= 5) {
                throw new IllegalStateException("OpenSearch does not answer");
            }

            return build(invocation);
        });

        when(templateBuilder.build("ru")).thenAnswer(this::build);

        start();

        assertThat(tries).hasSize(6);
        assertThat(pausesBetween(tries)).zipSatisfy(List.of(PAUSE, PAUSE.multipliedBy(2), LONGEST_PAUSE, LONGEST_PAUSE,
                        LONGEST_PAUSE),
                (pause, least) -> assertThat(pause).isGreaterThanOrEqualTo(least.minus(FIRING_PRECISION)));
        verify(copyFactory).allow("en");
        verify(copyFactory).allow("ru");
        assertThat(errorLines(output)).containsExactly(
                "The template tpl_en is not ready after try 1; the next try in PT0.05S",
                "The template tpl_en is not ready after try 2; the next try in PT0.1S",
                "The template tpl_en is not ready after try 3; the next try in PT0.2S",
                "The template tpl_en is not ready after try 4; the next try in PT0.2S",
                "The template tpl_en is not ready after try 5; the next try in PT0.2S");
    }

    // the scheduler holds the try until its pause is over, and the next try doubles the pause
    @Test
    void aFailedBuildIsAOneShotTriggerOfTheRetryJobAfterThePause() throws Exception {
        scheduler.standby();

        when(templateBuilder.build("en"))
                .thenThrow(new IllegalStateException("OpenSearch does not answer"))
                .thenAnswer(this::build);

        when(templateBuilder.build("ru")).thenAnswer(this::build);

        Instant before = Instant.now();
        CompletableFuture<Void> started = startup.start();
        verify(copyFactory, timeout(10_000)).allow("ru");
        Instant after = Instant.now();

        List<? extends Trigger> triggers = scheduler.getTriggersOfJob(StandSchedulingConfiguration.TEMPLATE_RETRY);

        assertThat(triggers).singleElement().isInstanceOfSatisfying(SimpleTrigger.class, trigger -> {
            assertThat(trigger.getRepeatCount()).isZero();
            assertThat(trigger.getMisfireInstruction()).isEqualTo(SimpleTrigger.MISFIRE_INSTRUCTION_FIRE_NOW);
            assertThat(trigger.getStartTime().toInstant())
                    .isBetween(before.plus(PAUSE).minus(FIRING_PRECISION), after.plus(PAUSE));
        });
        // the try after it, should it fail too, waits twice as long
        assertThat(triggers.getFirst().getJobDataMap())
                .containsEntry("language", "en")
                .containsEntry("failures", 1)
                .containsEntry("pause", PAUSE.multipliedBy(2).toMillis());
        assertThat(started).isNotDone();
        verify(copyFactory, never()).allow("en");

        scheduler.start();
        started.get(1, TimeUnit.MINUTES);
        await(triggers.getFirst());

        verify(templateBuilder, times(2)).build("en");
        verify(copyFactory).allow("en");
        assertThat(scheduler.getTriggersOfJob(StandSchedulingConfiguration.TEMPLATE_RETRY)).isEmpty();
    }

    // a language whose template keeps failing holds no other language back, and nothing gives it up
    @Test
    void theNextTemplateIsMadeReadyWhileOneIsTriedAgain() throws Exception {
        List<String> steps = new CopyOnWriteArrayList<>();

        when(templateBuilder.build("en")).thenAnswer(invocation -> {
            steps.add("build en");

            throw new IllegalStateException("OpenSearch does not answer");
        });

        when(templateBuilder.build("ru")).thenAnswer(invocation -> {
            steps.add("build ru");

            return build(invocation);
        });

        doAnswer(invocation -> steps.add("allow " + invocation.getArgument(0))).when(copyFactory).allow(anyString());

        CompletableFuture<Void> started = startup.start();

        verify(templateBuilder, timeout(10_000).atLeast(5)).build("en");

        assertThat(steps.subList(0, 4)).containsExactly("build en", "build ru", "allow ru", "build en");
        assertThat(started).isNotDone();
        verify(copyFactory, never()).allow("en");

        // the application stops: the start ends in its pause
        startup.stop();
        started.get(10, TimeUnit.SECONDS);
    }

    @Test
    void aTemplateThatFailsOnceIsReadyAtTheNextTry(CapturedOutput output) throws Exception {
        when(templateBuilder.build("en"))
                .thenThrow(new IllegalStateException("The search queue of tpl_en is not processed in 10 minutes"))
                .thenAnswer(this::build);

        when(templateBuilder.build("ru")).thenAnswer(this::build);

        start();

        verify(templateBuilder, times(2)).build("en");
        verify(copyFactory).allow("en");
        verify(copyFactory).allow("ru");
        assertThat(inTemplate("en", TemplateBuilder.READY)).isRegularFile();
        assertThat(errorLines(output)).containsExactly(
                "The template tpl_en is not ready after try 1; the next try in PT0.05S");
    }

    // OpenSearch does not answer the check of a template an earlier run built, which may well be whole
    @Test
    void aTemplateWhoseIndexesCannotBeCheckedIsLeftAsItIsForTheNextTry() throws Exception {
        builtByAnEarlierRun("en");
        builtByAnEarlierRun("ru");

        when(indexes.exist(StandScope.template("en")))
                .thenThrow(new UncheckedIOException(new IOException("OpenSearch does not answer")))
                .thenReturn(true);

        start();

        verify(indexes, times(2)).exist(StandScope.template("en"));
        verify(templateBuilder, never()).build(anyString());
        verify(files, never()).deleteTemplateFolder(any());
        verify(copyFactory).allow("en");
        verify(copyFactory).allow("ru");
    }

    // a login in the first moments of a run would otherwise get a copy whose folder the start deletes, or one of a
    // template whose indexes are gone
    @Test
    void aTemplateTakesLoginsOnlyOnceTheStartHasDeletedWhatAnEarlierRunLeftAndCheckedIt() throws Exception {
        builtByAnEarlierRun("en");
        builtByAnEarlierRun("ru");

        start();

        InOrder order = inOrder(copyFactory, files, indexes);
        order.verify(copyFactory).disallow("en");
        order.verify(copyFactory).disallow("ru");
        order.verify(files).deleteFoldersOfGoneCopies(any());
        order.verify(indexes).deleteIndexesOfGoneCopies(any());
        order.verify(indexes).exist(StandScope.template("en"));
        order.verify(copyFactory).allow("en");
        order.verify(indexes).exist(StandScope.template("ru"));
        order.verify(copyFactory).allow("ru");
    }

    // a start makes every template ready anew, so a try the start before it scheduled has nothing left to do
    @Test
    void aTryOfAnEarlierStartDoesNothing() throws Exception {
        scheduler.standby();

        when(templateBuilder.build("en"))
                .thenThrow(new IllegalStateException("OpenSearch does not answer"))
                .thenAnswer(this::build);

        when(templateBuilder.build("ru")).thenAnswer(this::build);

        startup.start();
        verify(copyFactory, timeout(10_000)).allow("ru");

        Trigger tryOfTheEarlierStart = scheduler.getTriggersOfJob(StandSchedulingConfiguration.TEMPLATE_RETRY)
                .getFirst();

        start();
        scheduler.start();

        await(tryOfTheEarlierStart);

        // the later start built the template again, and the try of the earlier one built nothing more
        verify(templateBuilder, times(2)).build("en");
        verify(copyFactory).allow("en");
    }

    @Test
    void aLanguageThatNamesNoTemplateFailsTheStartOfTheApplication() {
        StandProperties properties = properties(List.of("en", "EN"));
        CopyRegistry registry = new CopyRegistry(timeSource);

        assertThatThrownBy(() -> new StandStartup(properties, templateBuilder, copyFactory, registry, indexes, files,
                timeSource, scheduler))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void start() throws Exception {
        startup.start()
                .get(1, TimeUnit.MINUTES);
    }

    // the trigger has fired and its job has run: Quartz forgets a one-shot trigger once its job is done
    private void await(Trigger trigger) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (scheduler.checkExists(trigger.getKey())) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(10);
        }
    }

    private StandProperties properties(List<String> languages) {
        return new StandProperties(Duration.ofMinutes(30), DataSize.ofMegabytes(20), languages, List.of(),
                DataSize.ofGigabytes(1), dataDir, PAUSE, LONGEST_PAUSE, true);
    }

    // what a build leaves in the folder of the template
    private Path build(InvocationOnMock invocation) throws IOException {
        String language = invocation.getArgument(0);
        Files.createDirectories(inTemplate(language, StandFileStorage.FILES));

        return Files.writeString(inTemplate(language, TemplateBuilder.SCRIPT), "SET SCHEMA PUBLIC");
    }

    private void builtByAnEarlierRun(String language) throws IOException {
        Files.createDirectories(inTemplate(language, StandFileStorage.FILES));
        Files.writeString(inTemplate(language, TemplateBuilder.SCRIPT), "SET SCHEMA PUBLIC");
        Files.writeString(inTemplate(language, TemplateBuilder.READY), "day=2026-11-16");
    }

    private Path inTemplate(String language, String name) {
        return StandScope.template(language)
                .folder(dataDir)
                .resolve(name);
    }

    // a scheduler of its own for each test, with the store in memory as on the stand
    private static Scheduler inMemoryScheduler() throws SchedulerException {
        Properties settings = new Properties();
        settings.setProperty(StdSchedulerFactory.PROP_SCHED_INSTANCE_NAME, "retry-test-" + UUID.randomUUID());
        settings.setProperty(StdSchedulerFactory.PROP_THREAD_POOL_CLASS, SimpleThreadPool.class.getName());
        settings.setProperty(StdSchedulerFactory.PROP_THREAD_POOL_PREFIX + ".threadCount", "2");

        return new StdSchedulerFactory(settings).getScheduler();
    }

    private static List<Duration> pausesBetween(List<Long> times) {
        List<Duration> pauses = new ArrayList<>();

        for (int i = 1; i < times.size(); i++) {
            pauses.add(Duration.ofNanos(times.get(i) - times.get(i - 1)));
        }

        return pauses;
    }

    // the message of each ERROR line of the start, without its exception
    private static List<String> errorLines(CapturedOutput output) {
        return output.getAll()
                .lines()
                .filter(line -> line.contains("ERROR") && line.contains("is not ready after try"))
                .map(line -> line.substring(line.indexOf("The template")))
                .toList();
    }
}
