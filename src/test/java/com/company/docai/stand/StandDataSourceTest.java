package com.company.docai.stand;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pools of connections of the databases of the stand: one thread keeps them all, so a copy waiting for its
 * visitor costs no thread of its own, and a pool that closes takes its tasks with it.
 */
class StandDataSourceTest {

    @Test
    void thePoolsOfTheStandShareOneHousekeeper() {
        StandScope first = StandScope.copy(CopyRegistry.newKey());
        StandScope second = StandScope.copy(CopyRegistry.newKey());

        try (HikariDataSource one = StandDataSource.pool("probe-one", first.databaseUrl());
             HikariDataSource two = StandDataSource.pool("probe-two", second.databaseUrl())) {
            assertThat(one.getScheduledExecutor()).isNotNull().isSameAs(two.getScheduledExecutor());
            assertThat(one.getScheduledExecutor()).isInstanceOfSatisfying(ScheduledThreadPoolExecutor.class,
                    housekeeper -> assertThat(housekeeper.getRemoveOnCancelPolicy()).isTrue());
            assertThat(threadNames()).noneMatch(name -> name.startsWith("stand-probe") && name.endsWith("housekeeper"));
        } finally {
            StandDataSource.shutdown(first);
            StandDataSource.shutdown(second);
        }
    }

    private static List<String> threadNames() {
        return Thread.getAllStackTraces()
                .keySet()
                .stream()
                .map(Thread::getName)
                .toList();
    }
}
