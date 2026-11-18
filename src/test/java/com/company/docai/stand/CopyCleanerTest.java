package com.company.docai.stand;

import io.jmix.core.TimeSource;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The cleaner deletes a copy only if it is still idle as it leaves the registry.
 */
class CopyCleanerTest {

    private final AtomicReference<Instant> clock = new AtomicReference<>(Instant.parse("2026-11-17T10:00:00Z"));

    private final CopyRegistry registry = spy(new CopyRegistry(timeSource()));

    private final CopyFactory copyFactory = mock(CopyFactory.class);

    @Test
    void aCopyTouchedBetweenTheCheckAndTheRemovalSurvives() {
        Copy idle = registry.register(CopyRegistry.newKey(), "en");
        Copy touched = registry.register(CopyRegistry.newKey(), "en");
        clock.set(clock.get().plus(Duration.ofMinutes(31)));

        // the browser of one copy sends a request right after the cleaner has found both copies idle
        doAnswer(invocation -> {
            Object found = invocation.callRealMethod();
            registry.touch(touched.key());

            return found;
        }).when(registry).idleLongerThan(any());

        new CopyCleaner(properties(), registry, copyFactory).deleteIdleCopies();

        verify(copyFactory).delete(idle.key());
        verify(copyFactory, never()).delete(touched.key());
        assertThat(registry.find(idle.key())).isEmpty();
        assertThat(registry.find(touched.key())).contains(touched);
    }

    private static StandProperties properties() {
        return new StandProperties(Duration.ofMinutes(30), DataSize.ofMegabytes(20), List.of("en"), List.of(),
                DataSize.ofGigabytes(1), Path.of("data"), Duration.ofMinutes(1), Duration.ofMinutes(10), true);
    }

    private TimeSource timeSource() {
        TimeSource timeSource = mock(TimeSource.class);
        when(timeSource.now()).thenAnswer(invocation -> ZonedDateTime.ofInstant(clock.get(), ZoneOffset.UTC));

        return timeSource;
    }
}
