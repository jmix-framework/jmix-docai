package com.company.docai.stand;

import io.jmix.core.Metadata;
import io.jmix.core.impl.method.ContextArgumentResolverComposite;
import io.jmix.search.SearchProperties;
import io.jmix.search.index.IndexConfiguration;
import io.jmix.search.index.mapping.ContributedIndexDefinition;
import io.jmix.search.index.mapping.processor.AttributesGroupProcessor;
import io.jmix.search.index.mapping.processor.MappingFieldAnnotationProcessorsRegistry;
import io.jmix.search.index.mapping.processor.impl.AnnotatedIndexDefinitionProcessor;
import io.jmix.search.index.mapping.processor.impl.InstanceNameRelatedPropertiesResolver;
import io.jmix.search.index.mapping.propertyvalue.PropertyValueExtractorProvider;

import java.util.List;

/**
 * The processor of the search add-on's index definitions on the stand, which name the index of the scope of the thread.
 */
public class ScopedIndexDefinitionProcessor extends AnnotatedIndexDefinitionProcessor {

    public ScopedIndexDefinitionProcessor(Metadata metadata,
                                          MappingFieldAnnotationProcessorsRegistry processorsRegistry,
                                          PropertyValueExtractorProvider propertyValueExtractorProvider,
                                          SearchProperties searchProperties,
                                          ContextArgumentResolverComposite resolvers,
                                          List<AttributesGroupProcessor<?>> attributesGroupProcessors,
                                          InstanceNameRelatedPropertiesResolver instanceNameResolver) {
        super(metadata, processorsRegistry, propertyValueExtractorProvider, searchProperties, resolvers,
                attributesGroupProcessors, instanceNameResolver);
    }

    @Override
    public IndexConfiguration createIndexConfiguration(String className) {
        return scoped(super.createIndexConfiguration(className));
    }

    @Override
    public IndexConfiguration createIndexConfiguration(ContributedIndexDefinition definition) {
        return scoped(super.createIndexConfiguration(definition));
    }

    @Override
    public IndexConfiguration appendContributedFields(IndexConfiguration base, ContributedIndexDefinition definition) {
        return scoped(super.appendContributedFields(base, definition));
    }

    private IndexConfiguration scoped(IndexConfiguration configuration) {
        return configuration instanceof ScopedIndexConfiguration
                ? configuration
                : new ScopedIndexConfiguration(configuration, searchProperties.getSearchIndexNamePrefix());
    }
}
