package com.company.docai.stand;

import com.company.docai.demo.DemoDataLoader;
import com.zaxxer.hikari.HikariDataSource;
import io.jmix.autoconfigure.data.JmixLiquibaseCreator;
import io.jmix.core.security.SystemAuthenticator;
import liquibase.exception.LiquibaseException;
import liquibase.integration.spring.SpringLiquibase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;

/**
 * Builds the template of a language: the schema and the demo set for today in a script, its files and sealed indexes.
 */
@Component
@Profile("stand")
public class TemplateBuilder {

    /**
     * The name of the script in the folder of a template.
     */
    public static final String SCRIPT = "db.script";

    /**
     * The marker the start of the stand writes into the folder of a template once its build is over.
     */
    public static final String READY = "ready";

    private static final Logger log = LoggerFactory.getLogger(TemplateBuilder.class);

    private final StandProperties properties;
    private final StandDataSource dataSource;
    private final LiquibaseProperties liquibaseProperties;
    private final ResourceLoader resourceLoader;
    private final DemoDataLoader loader;
    private final SystemAuthenticator systemAuthenticator;
    private final StandIndexes indexes;

    public TemplateBuilder(StandProperties properties,
                           StandDataSource dataSource,
                           @Qualifier("jmix_LiquibaseProperties") LiquibaseProperties liquibaseProperties,
                           ResourceLoader resourceLoader,
                           DemoDataLoader loader,
                           SystemAuthenticator systemAuthenticator,
                           StandIndexes indexes) {
        this.properties = properties;
        this.dataSource = dataSource;
        this.liquibaseProperties = liquibaseProperties;
        this.resourceLoader = resourceLoader;
        this.loader = loader;
        this.systemAuthenticator = systemAuthenticator;
        this.indexes = indexes;
    }

    /**
     * Builds the template of the language anew, in place of an earlier build.
     *
     * @return the script of the template
     * @throws IllegalStateException when the template is not built; its database is shut down then too
     */
    public Path build(String language) {
        StandScope scope = StandScope.template(language);
        Path folder = scope.folder(properties.dataDir());
        Path script = folder.resolve(SCRIPT);
        long started = System.nanoTime();

        try {
            Files.createDirectories(folder);
            // OpenSearch keeps the indexes across the starts of the application, and the add-on cannot recreate an
            // index of a scope: it looks the index up among the names of the start
            indexes.renew(scope);

            try (HikariDataSource database = StandDataSource.pool(scope.id(), scope.databaseUrl())) {
                migrate(database);
                load(scope, database);
                indexes.seal(scope);

                try (Connection connection = database.getConnection()) {
                    TemplateScript.dump(connection, script);
                }
            } finally {
                StandDataSource.shutdown(scope);
            }
        } catch (Exception e) {
            throw new IllegalStateException("The template " + scope.id() + " is not built", e);
        }

        log.info("The template {} is built in {} ms: {}", scope.id(), (System.nanoTime() - started) / 1_000_000,
                script);

        return script;
    }

    private void migrate(HikariDataSource database) throws LiquibaseException {
        SpringLiquibase liquibase = JmixLiquibaseCreator.create(database, liquibaseProperties);
        liquibase.setResourceLoader(resourceLoader);
        liquibase.afterPropertiesSet();
    }

    // the loader, the queue of search and the vector indexer work through the data source of the application, which
    // sends the scope of the template to its database while the set loads and its indexes fill
    private void load(StandScope scope, HikariDataSource database) throws Exception {
        dataSource.add(scope, database);

        try {
            DemoDataLoader.Outcome outcome = StandScopeHolder.call(scope,
                    () -> systemAuthenticator.withSystem(() -> loader.loadIfEmpty(scope.language())));

            if (outcome != DemoDataLoader.Outcome.LOADED) {
                throw new IllegalStateException("The demo set did not load into the template: " + outcome);
            }

            indexes.complete(scope);
        } finally {
            dataSource.remove(scope);
        }
    }
}
