package com.company.docai;

import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

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
    void theStandProfileLoadsTheRussianSetAndLeavesTheLoginFormEmpty() {
        try (ConfigurableApplicationContext context = start("--spring.profiles.active=stand")) {
            assertThat(context.getEnvironment().getProperty("docai.demo-data")).isEqualTo("ru");
            assertThat(context.getEnvironment().getProperty("ui.login.defaultUsername")).isNull();
            assertThat(context.getEnvironment().getProperty("ui.login.defaultPassword")).isNull();
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
