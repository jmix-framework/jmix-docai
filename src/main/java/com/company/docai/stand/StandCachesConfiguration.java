package com.company.docai.stand;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Keeps the caches of the types of documents and of the roles stored in the database per scope on the stand: a copy
 * changes them in its own database, and an entry shared by the copies would show the change of one copy in all of
 * them, where a field the database of another copy does not have breaks the save of its documents.
 */
@Configuration(proxyBeanMethods = false)
@Profile("stand")
public class StandCachesConfiguration {

    /**
     * Puts the cache manager of the stand in place of the one of the application before the beans of Jmix take their
     * caches from it, which they do once, when they start; the method is static, so that the configuration itself is
     * not made that early.
     */
    @Bean
    public static BeanPostProcessor standCacheManager() {
        return new BeanPostProcessor() {

            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return bean instanceof CacheManager cacheManager && !(bean instanceof StandCacheManager)
                        ? new StandCacheManager(cacheManager)
                        : bean;
            }
        };
    }
}
