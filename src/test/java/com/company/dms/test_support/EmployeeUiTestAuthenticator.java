package com.company.dms.test_support;

import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.testassist.UiTestAuthenticator;
import org.springframework.context.ApplicationContext;

public class EmployeeUiTestAuthenticator implements UiTestAuthenticator {

    public static final String USERNAME = "peter";

    @Override
    public void setupAuthentication(ApplicationContext context) {
        context.getBean(SystemAuthenticator.class).begin(USERNAME);
    }

    @Override
    public void removeAuthentication(ApplicationContext context) {
        context.getBean(SystemAuthenticator.class).end();
    }
}
