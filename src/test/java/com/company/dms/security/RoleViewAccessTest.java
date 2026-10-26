package com.company.dms.security;

import io.jmix.core.AccessManager;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.accesscontext.UiShowViewContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RoleViewAccessTest {

    @Autowired
    private AccessManager accessManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @ParameterizedTest
    @CsvSource({
            "peter, MainView, true",
            "peter, ProfileReadView, true",
            "peter, RepositoryView, true",
            "peter, Document.detail, true",
            "peter, Folder.detail, true",
            "peter, Folder.lookup, true",
            "peter, AccessDraft.detail, true",
            "peter, User.list, false",
            "peter, User.detail, false",
            "peter, roleAssignmentView, false",
            "peter, sec_ResourceRoleModel.list, false",
            "peter, datatl_entityInspectorListView, false",
            "peter, dynat_CategoryView.list, false",
            "admin, MainView, true",
            "admin, ProfileReadView, true",
            "admin, User.list, true",
            "admin, roleAssignmentView, true",
            "admin, datatl_entityInspectorListView, true",
            "admin, dynat_CategoryView.list, true"
    })
    void theUserOpensOnlyTheViewsOfTheirRoles(String username, String viewId, boolean permitted) {
        UiShowViewContext context = new UiShowViewContext(viewId);

        systemAuthenticator.runWithUser(username, () -> accessManager.applyRegisteredConstraints(context));

        assertThat(context.isPermitted()).isEqualTo(permitted);
    }
}
