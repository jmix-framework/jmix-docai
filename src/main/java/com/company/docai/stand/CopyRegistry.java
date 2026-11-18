package com.company.docai.stand;

import io.jmix.core.TimeSource;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The copies of the demo data that exist now, by their keys.
 */
@Component
@Profile("stand")
public class CopyRegistry {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final TimeSource timeSource;

    // its compute methods run atomically, which the removal of an idle copy needs
    private final ConcurrentHashMap<String, Copy> copies = new ConcurrentHashMap<>();

    public CopyRegistry(TimeSource timeSource) {
        this.timeSource = timeSource;
    }

    /**
     * Makes a key of 128 random bits, as 32 lower-case hexadecimal characters.
     */
    public static String newKey() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);

        return HexFormat.of().formatHex(bytes);
    }

    /**
     * Finds a registered copy; a missing or malformed key finds nothing.
     */
    public Optional<Copy> find(@Nullable String key) {
        if (key == null) {
            return Optional.empty();
        }

        return Optional.ofNullable(copies.get(key));
    }

    /**
     * Finds a registered copy and marks it as used now, in one step: a removal of an idle copy, see
     * {@link #removeIfIdleLongerThan}, comes either before, and the copy is not found, or after, and the copy is no
     * longer idle.
     */
    public Optional<Copy> touch(@Nullable String key) {
        if (key == null) {
            return Optional.empty();
        }

        Instant now = now();

        return Optional.ofNullable(copies.computeIfPresent(key, (name, copy) -> {
            copy.lastAccess()
                    .set(now);

            return copy;
        }));
    }

    /**
     * Registers a copy, which counts as used now.
     *
     * @throws IllegalArgumentException if the key or the language is not well-formed
     */
    public Copy register(String key, String language) {
        StandScope.copy(key);
        StandScope.template(language);

        Instant now = now();
        Copy copy = new Copy(key, language, now, new AtomicReference<>(now));
        copies.put(key, copy);

        return copy;
    }

    /**
     * Forgets the copy; its database is the caller's to shut down.
     */
    public Optional<Copy> remove(String key) {
        return Optional.ofNullable(copies.remove(key));
    }

    /**
     * Forgets the copy if it is still not used for longer than the timeout, in one step with the check: a request that
     * marks the copy as used before keeps it.
     *
     * @return the copy that was forgotten, if any; its database is the caller's to shut down
     */
    public Optional<Copy> removeIfIdleLongerThan(String key, Duration timeout) {
        Instant limit = now().minus(timeout);
        AtomicReference<Copy> removed = new AtomicReference<>();

        copies.computeIfPresent(key, (name, copy) -> {
            if (!copy.lastAccess()
                    .get()
                    .isBefore(limit)) {
                return copy;
            }

            removed.set(copy);

            return null;
        });

        return Optional.ofNullable(removed.get());
    }

    /**
     * The copies not used for longer than the timeout.
     */
    public List<Copy> idleLongerThan(Duration timeout) {
        Instant limit = now().minus(timeout);

        return copies.values()
                .stream()
                .filter(copy -> copy.lastAccess()
                        .get()
                        .isBefore(limit))
                .toList();
    }

    /**
     * The copies registered now, as a snapshot.
     */
    public Collection<Copy> all() {
        return List.copyOf(copies.values());
    }

    private Instant now() {
        return timeSource.now().toInstant();
    }
}
