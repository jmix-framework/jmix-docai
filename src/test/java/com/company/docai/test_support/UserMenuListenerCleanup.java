package com.company.docai.test_support;

import io.jmix.securityflowui.action.UserMenuSubstituteUserAction;
import org.springframework.context.ApplicationListener;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

import java.util.List;

/**
 * Takes the user menus of a UI test off its application context once the test is over: a UI test ends without detaching
 * its UI, so the cached context would keep the UI of every test of the run, and the heap runs out.
 */
public class UserMenuListenerCleanup extends AbstractTestExecutionListener {

    @Override
    public void afterTestMethod(TestContext testContext) {
        if (testContext.hasApplicationContext()
                && testContext.getApplicationContext() instanceof AbstractApplicationContext context) {
            List<ApplicationListener<?>> menus = context.getApplicationListeners()
                    .stream()
                    .filter(UserMenuSubstituteUserAction.class::isInstance)
                    .toList();

            menus.forEach(context::removeApplicationListener);
        }
    }
}
