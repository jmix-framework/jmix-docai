package com.company.docai;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DatabaseSetupTest {

    @Autowired
    private Environment environment;

    @Autowired
    private DataSource dataSource;

    @Test
    void testsRunOnTheTestProfileOnly() {
        assertThat(environment.getActiveProfiles())
                .containsExactly("test");
    }

    @Test
    void testsUseAnInMemoryDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL())
                    .startsWith("jdbc:hsqldb:mem:");
        }
    }

    @Test
    void theApplicationChangelogRanUnderItsOwnPath() {
        List<String> changeSets = new JdbcTemplate(dataSource).queryForList(
                "select ID from DATABASECHANGELOG where FILENAME = ? order by ORDEREXECUTED",
                String.class,
                "com/company/docai/liquibase/changelog/010-init-user.xml");

        assertThat(changeSets)
                .containsExactly("1", "2", "3", "4");
    }

    @Test
    void theAdminIsActive() {
        List<Boolean> active = new JdbcTemplate(dataSource).queryForList(
                "select ACTIVE from USER_ where USERNAME = ?",
                Boolean.class,
                "admin");

        assertThat(active)
                .containsExactly(true);
    }

    @Test
    void theAdminHasFullAccess() {
        List<String> roles = new JdbcTemplate(dataSource).queryForList(
                "select ROLE_CODE from SEC_ROLE_ASSIGNMENT where USERNAME = ?",
                String.class,
                "admin");

        assertThat(roles)
                .containsExactly("system-full-access");
    }
}
