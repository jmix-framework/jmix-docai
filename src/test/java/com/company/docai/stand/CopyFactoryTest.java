package com.company.docai.stand;

import com.company.docai.search.semantic.DocumentVectorIndexer;
import com.zaxxer.hikari.HikariDataSource;
import io.jmix.core.TimeSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The copies, their files and their indexes: a copy has links to the files of its template before the indexes of the
 * template are cloned, joins the loop of the search queues only with the clones, and a copy whose clones fail is not
 * made and leaves no folder; a deleted copy leaves the loop while its database answers, the indexer forgets it once
 * its database is out of reach, its indexes go after that, the caches forget it once its database is shut down, and
 * its database and its folder go even when OpenSearch does not answer, whose message the log takes with the tag of the
 * copy for its key. A template is ready for copies while the start of the run allows it and its ready marker is in its
 * folder. The databases and the files are real, the indexes and the indexer are stand-ins, and the caches are watched.
 */
@ExtendWith(OutputCaptureExtension.class)
class CopyFactoryTest {

    private static final String NOTE = "2026/11/17/note.txt";

    @TempDir
    private Path dataDir;

    private final StandIndexes indexes = mock(StandIndexes.class);
    private final DocumentVectorIndexer vectorIndexer = mock(DocumentVectorIndexer.class);
    private final StandCacheManager caches = spy(new StandCacheManager(new ConcurrentMapCacheManager()));
    private final StandDataSource dataSource = new StandDataSource(mock(HikariDataSource.class), mock());
    private final CopyRegistry registry = new CopyRegistry(timeSource());

    private CopyFactory copyFactory;

    @BeforeEach
    void writeTheTemplate() throws IOException {
        Path script = StandScope.template("en")
                .folder(dataDir)
                .resolve(TemplateBuilder.SCRIPT);

        Files.createDirectories(script.getParent());

        Files.writeString(script, """
                CREATE MEMORY TABLE PUBLIC.PROBE(ID INTEGER PRIMARY KEY)
                INSERT INTO PROBE VALUES(1)
                """, StandardCharsets.ISO_8859_1);

        Files.createDirectories(templateNote().getParent());
        Files.writeString(templateNote(), "a note of the template");

        StandProperties properties = new StandProperties(Duration.ofMinutes(30), DataSize.ofMegabytes(20),
                List.of("en"), List.of(), DataSize.ofGigabytes(1), dataDir, Duration.ofMinutes(1),
                Duration.ofMinutes(10), true);

        copyFactory = new CopyFactory(properties, dataSource, registry, indexes, vectorIndexer,
                new StandFileStorage(properties, mock(StandUploadGuard.class)), caches);
    }

    @AfterEach
    void deleteTheCopies() {
        registry.all()
                .forEach(copy -> copyFactory.delete(copy.key()));
    }

    @Test
    void aCopyJoinsTheLoopOnlyWithTheClonesOfTheIndexesOfItsTemplate() {
        List<String> registered = new ArrayList<>();

        doAnswer(invocation -> {
            registered.add("copies at the clone: " + registry.all().size());

            return null;
        }).when(indexes).cloneTemplate(eq("en"), any());

        Copy copy = copyFactory.create("en");
        StandScope scope = StandScope.copy(copy.key());

        InOrder order = inOrder(indexes);
        order.verify(indexes).cloneTemplate("en", scope);
        order.verify(indexes).join(scope);
        assertThat(registered).containsExactly("copies at the clone: 0");
        assertThat(registry.find(copy.key())).isPresent();
        assertThat(reaches(scope)).isTrue();
    }

    // the files are local and quick, so a copy whose files fail asks OpenSearch for no clones
    @Test
    void aCopyHasLinksToTheFilesOfItsTemplateBeforeItsIndexesAreCloned() {
        List<Boolean> linked = new ArrayList<>();

        doAnswer(invocation -> {
            Path link = noteOf(invocation.getArgument(1));
            linked.add(Files.isRegularFile(link) && Files.isSameFile(link, templateNote()));

            return null;
        }).when(indexes).cloneTemplate(eq("en"), any());

        copyFactory.create("en");

        assertThat(linked).containsExactly(true);
    }

    @Test
    void aCopyWhoseIndexesDoNotCloneIsNotMade() {
        List<StandScope> cloned = new ArrayList<>();

        doAnswer(invocation -> {
            cloned.add(invocation.getArgument(1));

            throw new IllegalStateException("OpenSearch does not answer");
        }).when(indexes).cloneTemplate(eq("en"), any());

        assertThatThrownBy(() -> copyFactory.create("en"))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("OpenSearch does not answer");

        StandScope scope = cloned.getFirst();
        assertThat(registry.all()).isEmpty();
        assertThat(reaches(scope)).isFalse();
        assertThat(exists(scope)).isFalse();
        assertThat(scope.folder(dataDir)).doesNotExist();
        verify(indexes, never()).join(any());
    }

    // the loop and the indexer write the indexes of a copy until they let it go, and a task that starts later fails at
    // the data source before it reaches OpenSearch
    @Test
    void aDeletedCopyLeavesTheLoopsBeforeItsIndexesGo() {
        Copy copy = copyFactory.create("en");
        StandScope scope = StandScope.copy(copy.key());
        List<String> steps = new ArrayList<>();

        doAnswer(invocation -> {
            steps.add("the loop lets it go, its database " + (reaches(scope) ? "answers" : "is out of reach"));

            return null;
        }).when(indexes).leave(scope);

        doAnswer(invocation -> {
            steps.add("the indexer forgets it, its database " + (reaches(scope) ? "answers" : "is out of reach"));

            return null;
        }).when(vectorIndexer).forget(scope);

        doAnswer(invocation -> {
            steps.add("its indexes are deleted");

            return null;
        }).when(indexes).delete(scope);

        doAnswer(invocation -> {
            steps.add("the caches forget it, its database " + (exists(scope) ? "lives" : "is shut down"));

            return null;
        }).when(caches).evict(scope);

        copyFactory.delete(copy.key());

        assertThat(steps).containsExactly(
                "the loop lets it go, its database answers",
                "the indexer forgets it, its database is out of reach",
                "its indexes are deleted",
                "the caches forget it, its database is shut down");
        assertThat(registry.find(copy.key())).isEmpty();
        assertThat(exists(scope)).isFalse();
        assertThat(scope.folder(dataDir)).doesNotExist();
        assertThat(templateNote()).isRegularFile();
    }

    @Test
    void aTemplateIsReadyWhileTheStartAllowsItAndItsMarkerIsThere() throws IOException {
        Path marker = StandScope.template("en")
                .folder(dataDir)
                .resolve(TemplateBuilder.READY);

        assertThat(copyFactory.isReady("en")).isFalse();

        // the marker of an earlier run counts once the start of this run has checked the template
        Files.writeString(marker, "day=2026-11-16");
        assertThat(copyFactory.isReady("en")).isFalse();

        copyFactory.allow("en");
        assertThat(copyFactory.isReady("en")).isTrue();

        // a template that is built again has no marker until its build is over
        Files.delete(marker);
        assertThat(copyFactory.isReady("en")).isFalse();

        Files.writeString(marker, "day=2026-11-17");
        copyFactory.disallow("en");
        assertThat(copyFactory.isReady("en")).isFalse();
    }

    // indexes left behind are found by their names later, a database left behind would keep its memory; the log names
    // the copy by its tag, also where OpenSearch names an index of the copy
    @Test
    void aCopyWhoseIndexesAreNotDeletedStillLosesItsDatabase(CapturedOutput output) {
        Copy copy = copyFactory.create("en");
        StandScope scope = StandScope.copy(copy.key());

        doThrow(new IllegalStateException("no such index [docai_" + scope.id() + "_document]")).when(indexes)
                .delete(scope);

        copyFactory.delete(copy.key());

        assertThat(registry.find(copy.key())).isEmpty();
        assertThat(exists(scope)).isFalse();
        assertThat(scope.folder(dataDir)).doesNotExist();
        assertThat(output.getAll()
                .lines()
                .filter(line -> line.contains("The indexes of"))
                .toList())
                .singleElement()
                .asString()
                .contains("no such index [docai_" + scope.tag() + "_document]")
                .doesNotContain(copy.key());
    }

    private Path templateNote() {
        return StandScope.template("en")
                .folder(dataDir)
                .resolve("files")
                .resolve(NOTE);
    }

    private Path noteOf(StandScope copy) {
        return copy.folder(dataDir)
                .resolve("files")
                .resolve(NOTE);
    }

    // the data source of the application sends the connections of the scope to the database of the copy
    private boolean reaches(StandScope scope) {
        try {
            return StandScopeHolder.call(scope, () -> {
                try (Connection connection = dataSource.getConnection()) {
                    return connection.getMetaData()
                            .getURL()
                            .startsWith(scope.databaseUrl());
                }
            });
        } catch (Exception e) {
            return false;
        }
    }

    // ifexists=true does not create an empty database in place of one that was shut down
    private static boolean exists(StandScope scope) {
        try (Connection ignored = DriverManager.getConnection(scope.databaseUrl() + ";ifexists=true", "SA", "")) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private static TimeSource timeSource() {
        TimeSource timeSource = mock(TimeSource.class);
        when(timeSource.now()).thenAnswer(invocation -> ZonedDateTime.now());

        return timeSource;
    }
}
