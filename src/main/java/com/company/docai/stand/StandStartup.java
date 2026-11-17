package com.company.docai.stand;

import io.jmix.core.TimeSource;
import jakarta.annotation.PreDestroy;
import org.jspecify.annotations.Nullable;
import org.quartz.JobDataMap;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

/**
 * The start of the stand. Once the application is ready, a thread of its own deletes what an earlier run of the
 * application left, the folders and the indexes of copies no registry knows any more, then makes the template of each
 * language of {@code docai.stand.template-languages} ready, one after another in that order: a template whose ready
 * marker, script, files and indexes are all there is used as it is, as after a restart of the container, and any
 * other is built anew in a cleared folder, as after a deploy. A template takes logins from the moment it is ready, so
 * the first language is open while the next one builds; a login in a language whose template is not ready waits, see
 * {@link StandLogin}. A template that fails is tried again until it is ready, by a one-shot trigger of
 * {@link TemplateRetryJob} on Quartz, first after {@code docai.stand.template-retry-pause}, then after a pause twice
 * as long each time, up to {@code docai.stand.template-retry-pause-max}; the next language is made ready meanwhile, and
 * no two builds overlap.
 */
@Component
@Profile("stand")
public class StandStartup {

    private static final Logger log = LoggerFactory.getLogger(StandStartup.class);

    private static final String DAY = "day";
    private static final String INDEXES = "indexes";

    // the data of a try again: the start it belongs to, the language, the tries that failed so far and the pause after
    // the try, should it fail too
    private static final String START = "start";
    private static final String LANGUAGE = "language";
    private static final String FAILURES = "failures";
    private static final String PAUSE = "pause";

    private final StandProperties properties;
    private final TemplateBuilder templateBuilder;
    private final CopyFactory copyFactory;
    private final CopyRegistry registry;
    private final StandIndexes indexes;
    private final StandFileStorage files;
    private final TimeSource timeSource;
    private final Scheduler scheduler;

    // the first tries of a start, one start at a time
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofPlatform()
            .name("stand-startup")
            .daemon()
            .factory());

    // one build of a template at a time, whether a start or a try again makes it
    private final ReentrantLock building = new ReentrantLock();

    private final AtomicLong starts = new AtomicLong();

    // the start that runs, whose number its tries again carry
    private volatile @Nullable Start current;

    private volatile boolean stopped;

    public StandStartup(StandProperties properties,
                        TemplateBuilder templateBuilder,
                        CopyFactory copyFactory,
                        CopyRegistry registry,
                        StandIndexes indexes,
                        StandFileStorage files,
                        TimeSource timeSource,
                        Scheduler scheduler) {
        this.properties = properties;
        this.templateBuilder = templateBuilder;
        this.copyFactory = copyFactory;
        this.registry = registry;
        this.indexes = indexes;
        this.files = files;
        this.timeSource = timeSource;
        this.scheduler = scheduler;

        // a language that names no template fails the start of the application, not the build in the background
        for (String language : properties.templateLanguages()) {
            StandScope.template(language);
        }
    }

    /**
     * Starts the stand once the application is ready, unless {@code docai.stand.auto-startup} is off.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (properties.autoStartup()) {
            start();
        }
    }

    /**
     * Starts the stand on the thread of the start, after a start that runs: takes the logins from every template
     * until it is checked, deletes the folders and the indexes of the copies the registry does not know, then makes
     * the templates ready in the order of their languages, each one taking logins from then on. The tries again of an
     * earlier start do nothing from then on.
     *
     * @return a future that completes when this start has made every template ready, or when the application stops
     */
    public CompletableFuture<Void> start() {
        Start start = new Start(starts.incrementAndGet(), ConcurrentHashMap.newKeySet(), new CompletableFuture<>());
        executor.execute(() -> startNow(start));

        return start.ready();
    }

    /**
     * Tries a template again, as a trigger of {@link TemplateRetryJob} asks: makes it ready, or schedules the next try
     * after a pause twice as long, up to the longest. A try of an earlier start, or one that comes as the application
     * stops, does nothing.
     *
     * @param data the data the start gave the trigger
     * @throws InterruptedException when the application stops while another build runs
     */
    public void retry(JobDataMap data) throws InterruptedException {
        building.lockInterruptibly();

        try {
            Start start = current;
            String language = data.getString(LANGUAGE);

            if (stopped || start == null || start.number() != data.getLong(START)
                    || !start.waiting().contains(language)) {
                return;
            }

            tryToMakeReady(start, StandScope.template(language), data.getInt(FAILURES),
                    Duration.ofMillis(data.getLong(PAUSE)));
        } finally {
            building.unlock();
        }
    }

    /**
     * Stops the start that runs, as the application stops; its future completes, and the tries again do nothing.
     */
    @PreDestroy
    public void stop() {
        stopped = true;
        executor.shutdownNow();
        Start start = current;

        if (start != null) {
            start.ready().complete(null);
        }
    }

    private void startNow(Start start) {
        try {
            building.lockInterruptibly();
        } catch (InterruptedException e) {
            // the application stops while a try again builds a template
            Thread.currentThread().interrupt();
            start.ready().complete(null);

            return;
        }

        try {
            List<String> languages = properties.templateLanguages();
            start.waiting().addAll(languages);
            current = start;

            // a marker an earlier run left vouches for a template only once it is checked, and a copy made meanwhile
            // could lose its folder to the sweep
            languages.forEach(copyFactory::disallow);
            deleteWhatAnEarlierRunLeft();
            Duration firstPause = atMostTheLongest(properties.templateRetryPause());

            for (String language : languages) {
                if (stopped) {
                    return;
                }

                tryToMakeReady(start, StandScope.template(language), 0, firstPause);
            }
        } catch (RuntimeException e) {
            start.ready().completeExceptionally(e);
        } finally {
            building.unlock();
        }
    }

    // the registry of a run starts empty, so a copy it does not know is one an earlier run left; what is not deleted
    // stays until the next start, while the templates matter now
    private void deleteWhatAnEarlierRunLeft() {
        Predicate<String> live = key -> registry.find(key)
                .isPresent();

        int folders = 0;
        int copyIndexes = 0;

        try {
            folders = files.deleteFoldersOfGoneCopies(live);
        } catch (IOException | RuntimeException e) {
            log.warn("The folders of copies an earlier run left are not deleted: {}",
                    StandScope.withTags(e.toString()));
        }

        try {
            copyIndexes = indexes.deleteIndexesOfGoneCopies(live);
        } catch (RuntimeException e) {
            log.warn("The indexes of copies an earlier run left are not deleted: {}",
                    StandScope.withTags(e.toString()));
        }

        if (folders + copyIndexes > 0) {
            log.info("The start deleted {} folders and {} indexes of copies an earlier run left", folders,
                    copyIndexes);
        }
    }

    // an OpenSearch that stays away for a while, as at a deploy, does not keep a language closed until the next start:
    // a template that fails is tried again by a trigger once the pause is over, while the next language goes on
    private void tryToMakeReady(Start start, StandScope template, int failures, Duration pause) {
        try {
            makeReady(template);
            copyFactory.allow(template.language());
            start.waiting().remove(template.language());

            if (start.waiting().isEmpty()) {
                start.ready().complete(null);
            }
        } catch (RuntimeException e) {
            if (stopped || Thread.currentThread().isInterrupted()) {
                return;
            }

            log.error("The template {} is not ready after try {}; the next try in {}", template.id(), failures + 1,
                    pause, e);

            tryAgainLater(start, template, failures + 1, pause);
        }
    }

    private void tryAgainLater(Start start, StandScope template, int failures, Duration pause) {
        Trigger trigger = TriggerBuilder.newTrigger()
                .forJob(StandSchedulingConfiguration.TEMPLATE_RETRY)
                .usingJobData(START, start.number())
                .usingJobData(LANGUAGE, template.language())
                .usingJobData(FAILURES, failures)
                .usingJobData(PAUSE, atMostTheLongest(pause.multipliedBy(2)).toMillis())
                .startAt(Instant.now()
                        .plus(pause))
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withMisfireHandlingInstructionFireNow())
                .build();

        try {
            scheduler.scheduleJob(trigger);
        } catch (SchedulerException e) {
            if (!stopped) {
                log.error("The template {} is not tried again until the next start", template.id(), e);
            }
        }
    }

    private void makeReady(StandScope template) {
        Path folder = template.folder(properties.dataDir());
        String builtOn = builtOnIn(folder);

        // the indexes are asked for last: a template whose indexes cannot be checked stays as it is for the next try
        if (builtOn != null
                && Files.isRegularFile(folder.resolve(TemplateBuilder.SCRIPT))
                && Files.isDirectory(folder.resolve(StandFileStorage.FILES))
                && indexes.exist(template)) {
            log.info("The template {} of {} is ready as an earlier run built it", template.id(), builtOn);

            return;
        }

        LocalDate day = timeSource.now()
                .toLocalDate();

        try {
            // the marker goes with the folder, and so do the files of an earlier build that no row refers to
            files.deleteTemplateFolder(template);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        templateBuilder.build(template.language());
        writeMarker(folder, template, day);
        log.info("The template {} is ready", template.id());
    }

    // the day of the build the ready marker in the folder vouches for, null without a marker; one that cannot be read
    // counts as none, and its template is built again
    private static @Nullable String builtOnIn(Path folder) {
        Path path = folder.resolve(TemplateBuilder.READY);

        if (!Files.isRegularFile(path)) {
            return null;
        }

        Properties marker = new Properties();

        try (Reader reader = Files.newBufferedReader(path)) {
            marker.load(reader);
        } catch (IOException | IllegalArgumentException e) {
            log.warn("The ready marker {} is not read: {}", path, e.toString());

            return null;
        }

        return marker.getProperty(DAY);
    }

    // written whole under another name, then moved in at once: a marker vouches for a whole template or is not there
    private void writeMarker(Path folder, StandScope template, LocalDate day) {
        Properties marker = new Properties();
        marker.setProperty(DAY, day.toString());
        marker.setProperty(INDEXES, String.join(",", indexes.namesInOpenSearch(template)));

        try {
            Path written = Files.createTempFile(folder, TemplateBuilder.READY, ".tmp");

            try (Writer writer = Files.newBufferedWriter(written)) {
                marker.store(writer, "the template " + template.id() + " is built");
            }

            Files.move(written, folder.resolve(TemplateBuilder.READY), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Duration atMostTheLongest(Duration pause) {
        Duration longest = properties.templateRetryPauseMax();

        return pause.compareTo(longest) > 0
                ? longest
                : pause;
    }

    // a start: its number, the languages whose templates it has not made ready yet, and its future
    private record Start(long number, Set<String> waiting, CompletableFuture<Void> ready) {
    }
}
