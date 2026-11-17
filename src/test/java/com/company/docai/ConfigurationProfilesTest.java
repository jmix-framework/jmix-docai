package com.company.docai;

import com.company.docai.stand.StandProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationProfilesTest {

    @Test
    void withoutAProfileTheApplicationRunsLocallyOnTheFileDatabase() {
        try (ConfigurableApplicationContext context = start()) {
            assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("local");
            assertThat(context.getEnvironment().getProperty("main.datasource.url"))
                    .isEqualTo("jdbc:hsqldb:file:.jmix/hsqldb/docai");
            assertThat(context.getEnvironment().getProperty("ui.login.defaultUsername")).isEqualTo("admin");
        }
    }

    @Test
    void theDatabaseCanBeReplacedFromOutside() {
        try (ConfigurableApplicationContext context = start(
                "--DOCAI_DB_URL=jdbc:postgresql://db.example/docai",
                "--DOCAI_DB_USERNAME=docai",
                "--DOCAI_DB_PASSWORD=secret")) {
            assertThat(context.getEnvironment().getProperty("main.datasource.url"))
                    .isEqualTo("jdbc:postgresql://db.example/docai");
            assertThat(context.getEnvironment().getProperty("main.datasource.username")).isEqualTo("docai");
            assertThat(context.getEnvironment().getProperty("main.datasource.password")).isEqualTo("secret");
        }
    }

    @Test
    void onlyTheLocalProfilePrefillsTheLoginForm() {
        try (ConfigurableApplicationContext context = start("--spring.profiles.active=online")) {
            assertThat(context.getEnvironment().getProperty("ui.login.defaultUsername")).isNull();
            assertThat(context.getEnvironment().getProperty("ui.login.defaultPassword")).isNull();
        }
    }

    @Test
    void theStandProfileLeavesTheLoginFormEmpty() {
        try (ConfigurableApplicationContext context = start("--spring.profiles.active=stand")) {
            assertThat(context.getEnvironment().getProperty("ui.login.defaultUsername")).isNull();
            assertThat(context.getEnvironment().getProperty("ui.login.defaultPassword")).isNull();
        }
    }

    // the stand gives every visitor a copy of the set of the login form, so its profile names no set
    @Test
    void theStandProfileNamesNoDemoSet() throws IOException {
        Properties stand = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application-stand.properties"));

        assertThat(stand).doesNotContainKey("docai.demo-data");
    }

    // a public stand reads the files of its visitors within limits; elsewhere a file is read whole, as before the stand
    @Test
    void onlyTheStandProfileLimitsReadingTheTextsOfFiles() {
        try (ConfigurableApplicationContext context = start("--spring.profiles.active=stand")) {
            assertThat(context.getEnvironment().getProperty("docai.files.max-text-length", Integer.class))
                    .isEqualTo(1_000_000);
            assertThat(context.getEnvironment().getProperty("docai.files.text-timeout", Duration.class))
                    .isEqualTo(Duration.ofSeconds(10));
        }

        try (ConfigurableApplicationContext context = start()) {
            assertThat(context.getEnvironment().containsProperty("docai.files.max-text-length")).isFalse();
            assertThat(context.getEnvironment().containsProperty("docai.files.text-timeout")).isFalse();
        }
    }

    // any visitor can sign in as the admin, and a script kept in a copy would run in the one JVM of every copy
    @Test
    void onlyTheStandProfileRunsNoScriptsKeptInTheData() {
        List<String> switches = List.of("jmix.core.unsafe-runtime-features-enabled", "jmix.dynattr.ui.groovy-enabled",
                "jmix.security.data.groovy-enabled");

        try (ConfigurableApplicationContext context = start("--spring.profiles.active=stand")) {
            assertThat(switches).allSatisfy(name -> assertThat(context.getEnvironment().getProperty(name,
                    Boolean.class)).as(name).isFalse());
        }

        try (ConfigurableApplicationContext context = start()) {
            assertThat(switches).allSatisfy(name -> assertThat(context.getEnvironment().containsProperty(name))
                    .as(name).isFalse());
        }
    }

    // the jobs of the stand run on Quartz in memory, as the control database of the stand lives there; Spring's own
    // executors stay as Boot makes them, and so does the store of Quartz outside the stand
    @Test
    void onlyTheStandProfileKeepsTheJobsOfQuartzInMemory() {
        try (ConfigurableApplicationContext context = start("--spring.profiles.active=stand")) {
            assertThat(context.getEnvironment().getProperty("spring.quartz.job-store-type")).isEqualTo("memory");
            assertThat(context.getEnvironment().containsProperty("spring.task.execution.mode")).isFalse();
            assertThat(context.getEnvironment().containsProperty("spring.task.scheduling.pool.size")).isFalse();
        }

        try (ConfigurableApplicationContext context = start()) {
            assertThat(context.getEnvironment().containsProperty("spring.quartz.job-store-type")).isFalse();
        }
    }

    // behind a proxy that ends TLS the request still counts as secure, so the cookies of a visitor keep Secure
    @Test
    void theStandProfileTakesTheProtocolTheProxyForwards() {
        try (ConfigurableApplicationContext context = start("--spring.profiles.active=stand")) {
            assertThat(context.getEnvironment().getProperty("server.forward-headers-strategy")).isEqualTo("native");
        }

        try (ConfigurableApplicationContext context = start()) {
            assertThat(context.getEnvironment().containsProperty("server.forward-headers-strategy")).isFalse();
        }
    }

    // a public stand keeps the files and the cost of the assistant per visitor small
    @Test
    void theStandProfileLimitsTheFilesAndTheAssistant() {
        try (ConfigurableApplicationContext context = start("--spring.profiles.active=stand")) {
            assertThat(context.getEnvironment().getProperty("docai.files.max-file-size", DataSize.class))
                    .isEqualTo(DataSize.ofMegabytes(5));
            assertThat(context.getEnvironment().getProperty("jmix.aitools.ui.data.chat-memory-max-messages",
                    Integer.class)).isEqualTo(10);
            assertThat(context.getEnvironment().getProperty("jmix.aitools.dataload.jpql-execution-max-result-limit",
                    Integer.class)).isEqualTo(50);
            assertThat(context.getEnvironment().getProperty("jmix.aitools.ui.assistant-response-timeout",
                    Duration.class)).isEqualTo(Duration.ofMinutes(2));
        }
    }

    @Test
    void theStandProfileSetsTheCopiesOfTheStand() {
        try (ConfigurableApplicationContext context = start("--spring.profiles.active=stand")) {
            StandProperties properties = Binder.get(context.getEnvironment())
                    .bind("docai.stand", StandProperties.class)
                    .get();

            assertThat(properties.copyIdleTimeout()).isEqualTo(Duration.ofMinutes(30));
            assertThat(properties.copyUploadQuota()).isEqualTo(DataSize.ofMegabytes(20));
            assertThat(properties.templateLanguages()).containsExactly("en", "ru");
            assertThat(properties.uploadTypes()).containsExactly(
                    "application/pdf",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "text/plain",
                    "text/csv",
                    "image/png",
                    "image/jpeg");
            assertThat(properties.minFreeDisk()).isEqualTo(DataSize.ofGigabytes(1));
            assertThat(properties.dataDir()).isEqualTo(Path.of(".jmix/stand"));
            assertThat(properties.templateRetryPause()).isEqualTo(Duration.ofMinutes(1));
            assertThat(properties.templateRetryPauseMax()).isEqualTo(Duration.ofMinutes(10));
            assertThat(properties.autoStartup()).isTrue();
        }
    }

    // a copy opens a pool of connections to its database and closes it with the copy, which is no news
    @Test
    void theStandProfileLeavesThePoolsOfItsDatabasesOutOfTheLog() {
        try (ConfigurableApplicationContext context = start("--spring.profiles.active=stand")) {
            assertThat(context.getEnvironment().getProperty("logging.level.com.zaxxer.hikari")).isEqualTo("warn");
        }
    }

    @Test
    void theCopiesOfTheStandCanBeTunedFromOutside() {
        try (ConfigurableApplicationContext context = start(
                "--spring.profiles.active=stand",
                "--docai.stand.copy-idle-timeout=5m",
                "--docai.stand.template-languages=en")) {
            StandProperties properties = Binder.get(context.getEnvironment())
                    .bind("docai.stand", StandProperties.class)
                    .get();

            assertThat(properties.copyIdleTimeout()).isEqualTo(Duration.ofMinutes(5));
            assertThat(properties.templateLanguages()).containsExactly("en");
        }
    }

    // the data source of the stand sends each thread to a database of its own, so the profile names their type
    @Test
    void theStandProfileNamesTheTypeOfItsDatabases() {
        try (ConfigurableApplicationContext context = start("--spring.profiles.active=stand")) {
            assertThat(context.getEnvironment().getProperty("jmix.data.dbms-type")).isEqualTo("hsql");
        }
    }

    // the queue of search lives in the database of each copy, and the jobs of the add-on read the control database only
    @Test
    void theStandProfileProcessesTheQueuesOfSearchWithoutTheJobsOfTheAddOn() {
        String indexingJob = "jmix.search.use-default-indexing-queue-processing-quartz-configuration";
        String enqueueingJob = "jmix.search.use-default-enqueueing-session-processing-quartz-configuration";

        try (ConfigurableApplicationContext context = start("--spring.profiles.active=stand")) {
            assertThat(context.getEnvironment().getProperty(indexingJob)).isEqualTo("false");
            assertThat(context.getEnvironment().getProperty(enqueueingJob)).isEqualTo("false");
        }
    }

    // the local development keeps one database: no copies, no templates
    @Test
    void withoutTheStandProfileNothingSetsTheCopies() {
        try (ConfigurableApplicationContext context = start()) {
            assertThat(context.getEnvironment().containsProperty("docai.stand.data-dir")).isFalse();
        }
    }

    // the main view opens after the sign-in with the dashboard at its root; a default view would open instead of it
    @Test
    void noProfileNamesAViewToOpenAfterTheSignIn() {
        try (ConfigurableApplicationContext context = start()) {
            assertThat(context.getEnvironment().containsProperty("jmix.ui.default-view-id")).isFalse();
        }
    }

    private ConfigurableApplicationContext start(String... args) {
        return new SpringApplicationBuilder(EmptyConfiguration.class)
                .environment(new IsolatedEnvironment())
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .run(args);
    }

    @Configuration(proxyBeanMethods = false)
    static class EmptyConfiguration {
    }

    // Keeps the system properties and environment variables of the machine out of the test
    static class IsolatedEnvironment extends AbstractEnvironment {
    }
}
