package com.company.docai.security.role;

import io.jmix.security.model.SecurityScope;
import io.jmix.security.role.annotation.ResourceRole;
import io.jmix.security.role.annotation.SpecificPolicy;
import io.jmix.securityflowui.role.UiMinimalPolicies;
import io.jmix.securityflowui.role.annotation.MenuPolicy;
import io.jmix.securityflowui.role.annotation.ViewPolicy;

@ResourceRole(name = "msg://UiMinimalRole.name", code = UiMinimalRole.CODE,
        description = "msg://UiMinimalRole.description", scope = SecurityScope.UI)
public interface UiMinimalRole extends UiMinimalPolicies {

    String CODE = "ui-minimal";

    @ViewPolicy(viewIds = "MainView")
    void main();

    /**
     * Opens the About page from its own item of the main menu.
     */
    @ViewPolicy(viewIds = "AboutView")
    @MenuPolicy(menuIds = "about")
    void about();

    @ViewPolicy(viewIds = "LoginView")
    @SpecificPolicy(resources = "ui.loginToUi")
    void login();
}
