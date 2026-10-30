package com.company.docai.test_support;

import io.jmix.core.CoreProperties;
import io.jmix.core.security.SecurityContextHelper;
import io.jmix.core.security.UserRepository;
import io.jmix.flowui.testassist.UiTestAuthenticator;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

public class AnonymousUiTestAuthenticator implements UiTestAuthenticator {

    @Override
    public void setupAuthentication(ApplicationContext context) {
        UserDetails anonymousUser = context.getBean(UserRepository.class).getAnonymousUser();
        String key = context.getBean(CoreProperties.class).getAnonymousAuthenticationTokenKey();

        SecurityContextHelper.setAuthentication(new AnonymousAuthenticationToken(key, anonymousUser,
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
    }

    @Override
    public void removeAuthentication(ApplicationContext context) {
        SecurityContextHolder.clearContext();
    }
}
