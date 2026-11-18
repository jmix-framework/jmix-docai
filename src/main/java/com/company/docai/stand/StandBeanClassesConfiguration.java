package com.company.docai.stand;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Gives beans of Jmix the classes of the stand, which every injection point gets in place of the classes of Jmix.
 */
@Configuration(proxyBeanMethods = false)
@Profile("stand")
public class StandBeanClassesConfiguration {

    /**
     * Replaces the classes before any bean is made; the method is static, so that the configuration itself is not
     * made that early. A bean that Jmix renames fails the start instead of keeping its class.
     */
    @Bean
    public static BeanFactoryPostProcessor standBeanClasses() {
        return beanFactory -> {
            beanFactory.getBeanDefinition("flowui_BackgroundWorkerImpl")
                    .setBeanClassName(StandBackgroundWorker.class.getName());

            beanFactory.getBeanDefinition("flowui_UiAsyncTasks")
                    .setBeanClassName(StandUiAsyncTasks.class.getName());

            beanFactory.getBeanDefinition("search_AnnotatedIndexDefinitionProcessor")
                    .setBeanClassName(ScopedIndexDefinitionProcessor.class.getName());

            beanFactory.getBeanDefinition("locfs_FileStorage")
                    .setBeanClassName(StandFileStorage.class.getName());

            beanFactory.getBeanDefinition("dynat_AttributeOptionsLoader")
                    .setBeanClassName(StandAttributeOptionsLoader.class.getName());
        };
    }
}
