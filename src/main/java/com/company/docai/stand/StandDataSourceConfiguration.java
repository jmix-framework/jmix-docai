package com.company.docai.stand;

import io.jmix.core.security.UserRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * The data source of the stand in place of the one of {@code main.datasource}: the control database, the templates
 * and the copies behind one router.
 */
@Configuration(proxyBeanMethods = false)
@Profile("stand")
public class StandDataSourceConfiguration {

    /**
     * The data source of the application, under the name Jmix looks the main one up by; at the start the changelogs
     * build the schema of the control database through it. No database of the stand reads a file, the control one
     * included.
     */
    @Bean
    @Primary
    public StandDataSource dataSource(ObjectProvider<UserRepository> userRepository) {
        StandDataSource.denyFileReads();

        return new StandDataSource(StandDataSource.pool("control", StandDataSource.CONTROL_URL), userRepository);
    }
}
