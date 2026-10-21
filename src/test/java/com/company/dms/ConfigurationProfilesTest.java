package com.company.dms;

import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.AbstractEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationProfilesTest {

    @Test
    void withoutAProfileTheApplicationRunsLocallyOnTheFileDatabase() {
        try (ConfigurableApplicationContext context = start()) {
            assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("local");
            assertThat(context.getEnvironment().getProperty("main.datasource.url"))
                    .isEqualTo("jdbc:hsqldb:file:.jmix/hsqldb/dms");
            assertThat(context.getEnvironment().getProperty("ui.login.defaultUsername")).isEqualTo("admin");
        }
    }

    @Test
    void theDatabaseCanBeReplacedFromOutside() {
        try (ConfigurableApplicationContext context = start(
                "--DMS_DB_URL=jdbc:postgresql://db.example/dms",
                "--DMS_DB_USERNAME=dms",
                "--DMS_DB_PASSWORD=secret")) {
            assertThat(context.getEnvironment().getProperty("main.datasource.url"))
                    .isEqualTo("jdbc:postgresql://db.example/dms");
            assertThat(context.getEnvironment().getProperty("main.datasource.username")).isEqualTo("dms");
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
