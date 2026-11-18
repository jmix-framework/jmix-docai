package com.company.docai.stand;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Keeps the caches of the document types and the stored roles per scope on the stand, so no copy sees another's change.
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
