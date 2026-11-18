package com.company.docai.stand;

import io.jmix.core.DataManager;
import io.jmix.core.Metadata;
import io.jmix.data.StoreAwareLocator;
import io.jmix.dynattr.AttributeDefinition;
import io.jmix.dynattrflowui.impl.AttributeOptionsLoaderImpl;
import io.jmix.dynattrflowui.impl.DynAttrGroovyFeatureSupport;

import java.util.List;

/**
 * The loader of the options of a field of a document type on the stand, where any visitor may sign in as the admin and
 * edit the fields of the types: a field whose options come from an SQL script, or from a JPQL loader with SQL(...) or
 * FUNCTION(...), gets none.
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
