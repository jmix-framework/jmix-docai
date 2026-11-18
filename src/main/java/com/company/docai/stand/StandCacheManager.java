package com.company.docai.stand;

import io.jmix.dynattr.DynAttrMetadata;
import io.jmix.security.role.ResourceRoleRepository;
import io.jmix.security.role.RowLevelRoleRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The cache manager of the stand, with the caches of document types and stored roles per scope, as a copy changes them.
 */
public class StandCacheManager implements CacheManager {

    /**
     * The names of the caches kept per scope: the dynamic attributes, the resource roles and the row-level roles.
     */
    public static final Set<String> SCOPED_CACHES = Set.of(
            DynAttrMetadata.DYN_ATTR_CACHE_NAME,
            ResourceRoleRepository.RESOURCE_ROLES_CACHE_NAME,
            RowLevelRoleRepository.ROW_LEVEL_ROLES_CACHE_NAME);

    private final CacheManager delegate;

    // the beans of Jmix take their caches once, when they start, and evict reaches the same ones
    private final Map<String, StandScopedCache> scopedCaches = new ConcurrentHashMap<>();

    public StandCacheManager(CacheManager delegate) {
        this.delegate = delegate;
    }

    @Override
    public @Nullable Cache getCache(String name) {
        Cache cache = delegate.getCache(name);

        if (cache == null || !SCOPED_CACHES.contains(name)) {
            return cache;
        }

        return scopedCaches.computeIfAbsent(name, ignored -> new StandScopedCache(cache));
    }

    @Override
    public Collection<String> getCacheNames() {
        return delegate.getCacheNames();
    }

    @Override
    public void resetCaches() {
        delegate.resetCaches();
    }

    /**
     * Drops the entries of the scope from the caches kept per scope, as a deleted copy needs: nothing reads them
     * again, since the key of a copy is never given out twice.
     */
    public void evict(StandScope scope) {
        scopedCaches.values()
                .forEach(cache -> cache.invalidate(scope));
    }
}
