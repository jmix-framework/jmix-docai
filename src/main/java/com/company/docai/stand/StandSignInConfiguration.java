package com.company.docai.stand;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * The login support of the language switch of the stand, beside Jmix's own, which the login form keeps.
 */
@Configuration(proxyBeanMethods = false)
@Profile("stand")
public class StandSignInConfiguration {

    /**
     * Not a default candidate: Jmix makes its login support only when no other bean takes its place, and no injection
     * by the type takes this one, so only the switch has it, by its name.
     */
    @Bean(name = StandLoginViewSupport.NAME, defaultCandidate = false)
    public StandLoginViewSupport standLoginViewSupport() {
        return new StandLoginViewSupport();
    }
}
