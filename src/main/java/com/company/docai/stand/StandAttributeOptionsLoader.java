package com.company.docai.stand;

import io.jmix.core.DataManager;
import io.jmix.core.Metadata;
import io.jmix.data.StoreAwareLocator;
import io.jmix.dynattr.AttributeDefinition;
import io.jmix.dynattrflowui.impl.AttributeOptionsLoaderImpl;
import io.jmix.dynattrflowui.impl.DynAttrGroovyFeatureSupport;

import java.util.List;

/**
 * The loader of the options of a field of a document type on the stand, where any visitor may sign in as the admin
 * and edit the fields of the types: no loader kept in a copy runs SQL of its own. A field whose options come from an
 * SQL script gets none, and so does a field whose JPQL loader puts SQL(...) or FUNCTION(...) into its join or where
 * clause, see {@link NativeSqlValidator#namesNativeSql}: Jmix runs both on the database of the copy as they are
 * written. The other loaders work as in Jmix.
 */
public class StandAttributeOptionsLoader extends AttributeOptionsLoaderImpl {

    public StandAttributeOptionsLoader(StoreAwareLocator storeAwareLocator,
                                       DataManager dataManager,
                                       Metadata metadata,
                                       DynAttrGroovyFeatureSupport dynAttrGroovyFeatureSupport) {
        super(storeAwareLocator, dataManager, metadata, dynAttrGroovyFeatureSupport);
    }

    @Override
    protected List<?> executeSql(Object entity, AttributeDefinition attribute, String script) {
        return List.of();
    }

    @Override
    protected List<?> executeJpql(Object entity, AttributeDefinition attribute, String script) {
        AttributeDefinition.Configuration configuration = attribute.getConfiguration();

        if (NativeSqlValidator.namesNativeSql(configuration.getJoinClause())
                || NativeSqlValidator.namesNativeSql(configuration.getWhereClause())) {
            return List.of();
        }

        return super.executeJpql(entity, attribute, script);
    }
}
