package com.company.docai.stand;

import com.company.docai.search.semantic.DocumentVectorIndexer;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes and deletes the copies of the demo data. A copy is a new in-memory database filled from the script of the
 * template of its language, with the same rows and ids, under a new key, and clones of the search indexes of the
 * template; the data source of the application sends the scope of the copy to its database, and the add-on and the
 * index of fragments to its indexes.
 */
@Component
@Profile("stand")
public class CopyFactory {

    private static final Logger log = LoggerFactory.getLogger(CopyFactory.class);

    private final StandProperties properties;
    private final StandDataSource dataSource;
    private final CopyRegistry registry;
    private final StandIndexes indexes;
    private final DocumentVectorIndexer vectorIndexer;
    private final StandFileStorage files;
    private final StandCacheManager caches;

    // the statements of the script of each language, read once for each build of its template
    private final Map<String, Script> scripts = new ConcurrentHashMap<>();

    // the languages whose templates the start of this run has checked or built
    private final Set<String> allowed = ConcurrentHashMap.newKeySet();

    private record Script(FileTime modified, List<String> statements) {
    }

    public CopyFactory(StandProperties properties,
                       StandDataSource dataSource,
                       CopyRegistry registry,
                       StandIndexes indexes,
                       DocumentVectorIndexer vectorIndexer,
                       StandFileStorage files,
                       CacheManager cacheManager) {
        this.properties = properties;
        this.dataSource = dataSource;
        this.registry = registry;
        this.indexes = indexes;
        this.vectorIndexer = vectorIndexer;
        this.files = files;

        // caches shared by the copies would show a type changed in one copy in all of them
        if (!(cacheManager instanceof StandCacheManager standCaches)) {
            throw new IllegalStateException("The caches of the stand are not kept per scope: " + cacheManager);
        }

        this.caches = standCaches;
    }

    /**
     * Whether a copy of the template of the language can be made now: the start of this run of the application allows
     * the template, which it does once it has deleted what an earlier run left and checked or built the template, and
     * the ready marker of the template, written after its build and gone while it is built again, is in its folder.
     */
    public boolean isReady(String language) {
        return allowed.contains(language) && Files.isRegularFile(inTemplate(language, TemplateBuilder.READY));
    }

    /**
     * Lets the logins make copies of the template of the language once it is ready, see {@link StandStartup}.
     */
    public void allow(String language) {
        allowed.add(language);
    }

    /**
     * Keeps the logins from making copies of the template of the language until it is allowed again.
     */
    public void disallow(String language) {
        allowed.remove(language);
    }

    /**
     * Makes a copy of the template of the language under a new key, with hard links to the files of the template and
     * clones of its indexes, and registers it; the loop of the search queues takes it from then on.
     *
     * @throws IllegalStateException when the template has no script, the copy cannot be filled from it, or its files
     *                               are not linked or its indexes are not cloned; nothing of the copy stays then
     */
    public Copy create(String language) {
        long started = System.nanoTime();
        List<String> statements = statementsOf(language);
        String key = CopyRegistry.newKey();
        StandScope scope = StandScope.copy(key);

        fill(scope, statements);
        linkFiles(language, scope);
        cloneIndexes(language, scope);
        dataSource.add(scope, StandDataSource.copyPool(scope));
        Copy copy = registry.register(key, language);
        indexes.join(scope);

        log.info("A copy of the template {} is made in {} ms; copies: {}", language,
                (System.nanoTime() - started) / 1_000_000, registry.all().size());

        return copy;
    }

    /**
     * Deletes the copy: forgets its key, takes it out of the loop of the search queues and of the vector indexer,
     * deletes its indexes, shuts its database down, drops its entries from the caches kept per scope and deletes its
     * folder with its files. A copy the registry has forgotten already, as the cleaner forgets an idle one, is deleted
     * all the same; the key of no copy deletes nothing.
     *
     * @throws IllegalArgumentException if the key is not well-formed
     */
    public void delete(String key) {
        StandScope scope = StandScope.copy(key);

        boolean registered = registry.remove(key)
                .isPresent();

        // the loop lets the copy go while its database still answers its batch in flight
        indexes.leave(scope);
        HikariDataSource database = dataSource.remove(scope);

        if (!registered && database == null) {
            return;
        }

        // a task of the copy that starts from now on fails at the data source before it reaches OpenSearch, and the
        // one in flight is waited for: nothing writes the indexes after their deletion, which would create them anew
        vectorIndexer.forget(scope);
        deleteIndexes(scope);

        if (database != null) {
            database.close();
        }

        StandDataSource.shutdown(scope);
        // no load of the copy succeeds once its database is gone, so its entries do not come back
        caches.evict(scope);
        // last: the work of the copy finds its files through its database, which is gone by now
        deleteFolder(scope);
        log.info("A copy is deleted; copies: {}", registry.all().size());
    }

    private Path inTemplate(String language, String name) {
        return StandScope.template(language)
                .folder(properties.dataDir())
                .resolve(name);
    }

    // a template built again is read again
    private List<String> statementsOf(String language) {
        Path path = inTemplate(language, TemplateBuilder.SCRIPT);

        try {
            FileTime modified = Files.getLastModifiedTime(path);
            Script script = scripts.get(language);

            if (script == null || !script.modified().equals(modified)) {
                script = new Script(modified, TemplateScript.read(path));
                scripts.put(language, script);
            }

            return script.statements();
        } catch (IOException e) {
            throw new IllegalStateException("The template of " + language + " has no script: " + path, e);
        }
    }

    // a copy without the files of its template would show documents whose files are gone
    private void linkFiles(String language, StandScope scope) {
        try {
            files.linkTemplate(language, scope);
        } catch (IOException | RuntimeException e) {
            StandDataSource.shutdown(scope);
            deleteFolder(scope);

            throw new IllegalStateException("The files of the template " + language + " are not linked", e);
        }
    }

    // a copy without the indexes of its template would find nothing, and its writes would create the indexes with a
    // mapping OpenSearch guesses
    private void cloneIndexes(String language, StandScope scope) {
        try {
            indexes.cloneTemplate(language, scope);
        } catch (RuntimeException e) {
            StandDataSource.shutdown(scope);
            deleteFolder(scope);

            throw new IllegalStateException("The indexes of the template " + language + " are not cloned", e);
        }
    }

    // logged, not thrown: the copy is gone by then, and a folder left behind is found by its key later, as indexes are
    // by their names
    private void deleteFolder(StandScope scope) {
        try {
            files.deleteFolder(scope);
        } catch (IOException e) {
            log.warn("The folder of {} is not deleted: {}", scope.tag(), StandScope.withTags(e.toString()));
        }
    }

    // the database goes even when OpenSearch does not answer: indexes left behind are found by their names later
    private void deleteIndexes(StandScope scope) {
        try {
            indexes.delete(scope);
        } catch (RuntimeException e) {
            log.warn("The indexes of {} are not deleted: {}", scope.tag(), StandScope.withTags(e.toString()));
        }
    }

    // a database filled halfway would stay in memory until its shutdown
    private void fill(StandScope scope, List<String> statements) {
        try (Connection connection = StandDataSource.connect(scope)) {
            TemplateScript.fill(connection, statements);
            StandDataSource.addVisitor(connection);
        } catch (SQLException e) {
            StandDataSource.shutdown(scope);

            throw new IllegalStateException("The copy " + scope.tag() + " is not filled", e);
        }
    }
}
