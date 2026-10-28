package com.company.dms.security;

import com.company.dms.app.service.access.AccessChecker;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.model.access.AccessSet;
import com.company.dms.model.user.User;
import com.company.dms.security.role.AccessRowLevelRole;
import com.company.dms.security.role.EmployeeRole;
import com.company.dms.security.role.FullAccessRole;
import com.company.dms.security.role.HrDepartmentRole;
import com.company.dms.test_support.AuthenticatedAsAdmin;
import com.company.dms.test_support.TestAccess;
import com.company.dms.test_support.TestAccess.DepartmentRole;
import io.jmix.core.DataManager;
import io.jmix.core.impl.QueryParamValuesManager;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.security.role.RoleGrantedAuthorityUtils;
import io.jmix.security.role.assignment.RoleAssignment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.GrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class AccessRoleAssignmentTest {

    private static final String TEST_USER_PREFIX = "access-role-assignment-test-";

    @Autowired
    private DataManager dataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private CurrentAuthentication currentAuthentication;

    @Autowired
    private RoleGrantedAuthorityUtils roleGrantedAuthorityUtils;

    @Autowired
    private QueryParamValuesManager queryParamValuesManager;

    @Autowired
    private AccessRoleAssignmentProvider accessRoleAssignmentProvider;

    @Autowired
    private AccessChecker accessChecker;

    @Autowired
    private FolderService folderService;

    @AfterEach
    void removeTestUsers() {
        dataManager.load(User.class)
                .query("e.username like ?1", TEST_USER_PREFIX + "%")
                .list()
                .forEach(dataManager::remove);
    }

    @Test
    void everyUserWithoutFullAccessGetsTheRoleAtSignIn() {
        String accessRole = roleGrantedAuthorityUtils.createRowLevelRoleGrantedAuthority(AccessRowLevelRole.CODE)
                .getAuthority();

        assertThat(authoritiesOf("peter")).contains(accessRole);
        assertThat(authoritiesOf("helen")).contains(accessRole);
        assertThat(authoritiesOf("admin"))
                .doesNotContain(accessRole)
                .contains(roleGrantedAuthorityUtils.createResourceRoleGrantedAuthority(FullAccessRole.CODE)
                        .getAuthority());
    }

    @Test
    void theProviderListsEveryUserButTheAdministrator() {
        List<String> usernames = accessRoleAssignmentProvider.getAllAssignments().stream()
                .map(RoleAssignment::getUsername)
                .toList();

        assertThat(usernames)
                .contains("peter", "helen", "alice", "maria", "john")
                .doesNotContain("admin");
    }

    @Test
    void theRoleCodesAreTheResourceRolesOfTheSignIn() {
        DepartmentRole department = DepartmentRole.assign(dataManager, "helen");

        try {
            assertThat(roleCodesOf("helen"))
                    .containsExactlyInAnyOrder(EmployeeRole.CODE, HrDepartmentRole.CODE, department.code());
            assertThat(roleCodesOf("peter")).containsExactly(EmployeeRole.CODE);
            assertThat(roleCodesOf("admin")).containsExactly(FullAccessRole.CODE);
        } finally {
            department.remove(dataManager);
        }
    }

    @Test
    void aUserWithoutRolesGetsACodeNoEntryHas() {
        User user = dataManager.create(User.class);
        user.setUsername(TEST_USER_PREFIX + System.nanoTime());
        dataManager.save(user);
        AccessSet rootSet = TestAccess.setOf(dataManager, folderService.getRoot());

        assertThat(roleCodesOf(user.getUsername())).containsExactly("");
        assertThat(systemAuthenticator.withUser(user.getUsername(), () -> accessChecker.getLevel(rootSet))).isNull();
    }

    @Test
    void theAdministratorIsRecognizedByTheFullAccessRole() {
        assertThat(systemAuthenticator.withUser("admin", accessChecker::hasFullAccess)).isTrue();
        assertThat(systemAuthenticator.withUser("peter", accessChecker::hasFullAccess)).isFalse();
    }

    private List<String> authoritiesOf(String username) {
        return systemAuthenticator.withUser(username, () -> currentAuthentication.getAuthentication()
                .getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList());
    }

    @SuppressWarnings("unchecked")
    private List<String> roleCodesOf(String username) {
        return systemAuthenticator.withUser(username,
                () -> (List<String>) queryParamValuesManager.getValue("current_role_codes"));
    }
}
