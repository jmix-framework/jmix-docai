package com.company.docai.stand;

import io.jmix.core.TimeSource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CopyRegistryTest {

    private final AtomicReference<Instant> clock = new AtomicReference<>(Instant.parse("2026-11-16T10:00:00Z"));

    private final CopyRegistry registry = new CopyRegistry(timeSource());

    @Test
    void aNewKeyIs32LowerCaseHexCharactersAndDiffers() {
        Set<String> keys = new HashSet<>();

        for (int i = 0; i < 20; i++) {
            String key = CopyRegistry.newKey();

            assertThat(key).matches("[0-9a-f]{32}");
            keys.add(key);
        }

        assertThat(keys).hasSize(20);
    }

    @Test
    void onlyARegisteredKeyFindsACopy() {
        String key = CopyRegistry.newKey();

        assertThat(registry.find(null)).isEmpty();
        assertThat(registry.find("x")).isEmpty();
        assertThat(registry.find(key)).isEmpty();

        Copy copy = registry.register(key, "en");

        assertThat(registry.find(key)).contains(copy);
        assertThat(copy.language()).isEqualTo("en");
        assertThat(copy.created()).isEqualTo(clock.get());
        assertThat(registry.all()).containsExactly(copy);
    }

    @Test
    void touchMovesTheLastAccess() {
        String key = CopyRegistry.newKey();
        Copy copy = registry.register(key, "en");
        clock.set(clock.get().plus(Duration.ofMinutes(5)));

        registry.touch(key);

        assertThat(copy.lastAccess().get()).isEqualTo(clock.get());
        assertThat(copy.created()).isBefore(clock.get());
    }

    @Test
    void onlyACopyIdleLongerThanTheTimeoutIsIdle() {
        Copy old = registry.register(CopyRegistry.newKey(), "en");
        clock.set(clock.get().plus(Duration.ofMinutes(20)));
        Copy recent = registry.register(CopyRegistry.newKey(), "ru");
        clock.set(clock.get().plus(Duration.ofMinutes(20)));

        assertThat(registry.idleLongerThan(Duration.ofMinutes(30))).containsExactly(old);

        registry.touch(old.key());
        clock.set(clock.get().plus(Duration.ofMinutes(15)));

        assertThat(registry.idleLongerThan(Duration.ofMinutes(30))).containsExactly(recent);
    }

    @Test
    void removeForgetsTheCopy() {
        String key = CopyRegistry.newKey();
        Copy copy = registry.register(key, "en");

        assertThat(registry.remove(key)).contains(copy);
        assertThat(registry.remove(key)).isEmpty();
        assertThat(registry.find(key)).isEmpty();
        assertThat(registry.all()).isEmpty();
    }

    @Test
    void aMalformedKeyOrLanguageIsNotRegistered() {
        assertThatThrownBy(() -> registry.register("x", "en")).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> registry.register(CopyRegistry.newKey(), "english"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(registry.all()).isEmpty();
    }

    private TimeSource timeSource() {
        TimeSource timeSource = Mockito.mock(TimeSource.class);
        Mockito.when(timeSource.now()).thenAnswer(invocation -> ZonedDateTime.ofInstant(clock.get(), ZoneOffset.UTC));

        return timeSource;
    }
}
