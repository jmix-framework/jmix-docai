package com.company.docai.stand;

import io.jmix.core.security.UserRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * The stand's data source instead of {@code main.datasource}: a router over the control database, templates and copies.
 */
@Configuration(proxyBeanMethods = false)
@Profile("stand")
public class StandDataSourceConfiguration {

    /**
     * The data source of the application, under the name Jmix looks the main one up by. No database of the stand reads
     * a file, the control one included.
     */
    @Bean
    @Primary
    public StandDataSource dataSource(ObjectProvider<UserRepository> userRepository) {
        StandDataSource.denyFileReads();

        return new StandDataSource(StandDataSource.pool("control", StandDataSource.CONTROL_URL), userRepository);
    }
}
