package com.company.docai.stand;

import org.jspecify.annotations.Nullable;
import org.springframework.cache.Cache;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * A cache whose entries belong to scopes: each key carries the scope of the thread, so a scope reads, fills and clears
 * only its own entries. Without a scope a key stays as it is, and clearing the cache drops every entry.
 */
public class StandScopedCache implements Cache {

    private final Cache delegate;

    public StandScopedCache(Cache delegate) {
        this.delegate = delegate;
    }

    /**
     * The key of an entry of a scope.
     */
    public record ScopedKey(StandScope scope, Object key) {
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public Object getNativeCache() {
        return delegate.getNativeCache();
    }

    @Override
    public @Nullable ValueWrapper get(Object key) {
        return delegate.get(scoped(key));
    }

    @Override
    public <T> @Nullable T get(Object key, @Nullable Class<T> type) {
        return delegate.get(scoped(key), type);
    }

    @Override
    public <T> @Nullable T get(Object key, Callable<T> valueLoader) {
        return delegate.get(scoped(key), valueLoader);
    }

    @Override
    public @Nullable CompletableFuture<?> retrieve(Object key) {
        return delegate.retrieve(scoped(key));
    }

    @Override
    public <T> CompletableFuture<T> retrieve(Object key, Supplier<CompletableFuture<T>> valueLoader) {
        return delegate.retrieve(scoped(key), valueLoader);
    }

    @Override
    public void put(Object key, @Nullable Object value) {
        delegate.put(scoped(key), value);
    }

    @Override
    public @Nullable ValueWrapper putIfAbsent(Object key, @Nullable Object value) {
        return delegate.putIfAbsent(scoped(key), value);
    }

    @Override
    public void evict(Object key) {
        delegate.evict(scoped(key));
    }

    @Override
    public boolean evictIfPresent(Object key) {
        return delegate.evictIfPresent(scoped(key));
    }

    /**
     * Drops the entries of the thread's scope, which a changed type or role needs; without a scope drops all.
     */
    @Override
    public void clear() {
        invalidate(StandScopeHolder.resolve());
    }

    /**
     * Drops the entries of the scope of the thread at once; without a scope drops every entry.
     */
    @Override
    public boolean invalidate() {
        return invalidate(StandScopeHolder.resolve());
    }

    /**
     * Drops the entries of the scope at once; without a scope drops every entry.
     *
     * @return whether the cache had entries to drop, false when it cannot tell
     */
    public boolean invalidate(@Nullable StandScope scope) {
        // a cache whose entries cannot be gone through drops them all: every scope loads its own again
        if (scope == null || !(delegate.getNativeCache() instanceof Map<?, ?> entries)) {
            return delegate.invalidate();
        }

        return entries.keySet()
                .removeIf(key -> key instanceof ScopedKey scopedKey && scopedKey.scope().equals(scope));
    }

    private Object scoped(Object key) {
        StandScope scope = StandScopeHolder.resolve();

        return scope == null
                ? key
                : new ScopedKey(scope, key);
    }
}
