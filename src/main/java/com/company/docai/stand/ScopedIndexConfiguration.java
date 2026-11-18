package com.company.docai.stand;

import io.jmix.search.index.IndexConfiguration;
import org.jspecify.annotations.Nullable;

/**
 * The configuration of an index of the search add-on whose name follows the scope of the thread:
 * {@code <prefix><scope>_<entity>}, or the index of the start without a scope.
 */
public class ScopedIndexConfiguration extends IndexConfiguration {

    private final String prefix;

    /**
     * Takes everything from the configuration the add-on made at the start, its name as the name of the start.
     *
     * @param prefix the configured prefix of every index name
     */
    public ScopedIndexConfiguration(IndexConfiguration base, String prefix) {
        super(base.getEntityName(),
                base.getEntityClass(),
                startName(base),
                base.getMapping(),
                base.getAffectedEntityClasses(),
                base.getIndexablePredicate(),
                base.getExtendedSearchSettings());

        this.prefix = prefix;
    }

    /**
     * The name of the index of the scope the thread works with, see {@link StandScopeHolder#resolve()}.
     */
    @Override
    public String getIndexName() {
        return getIndexName(StandScopeHolder.resolve());
    }

    /**
     * The name of the index of the scope; without a scope, the name of the start. An index named apart from the
     * prefix keeps its name in every scope.
     */
    public String getIndexName(@Nullable StandScope scope) {
        return scope == null || !indexName.startsWith(prefix)
                ? indexName
                : scope.indexPrefix(prefix) + indexName.substring(prefix.length());
    }

    // the add-on builds a configuration from another one when an add-on contributes fields to it
    private static String startName(IndexConfiguration base) {
        return base instanceof ScopedIndexConfiguration scoped
                ? scoped.indexName
                : base.getIndexName();
    }
}
