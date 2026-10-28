package com.company.dms.user;

import com.company.dms.model.user.User;
import com.company.dms.test_support.AuthenticatedAsAdmin;
import io.jmix.core.DataManager;
import io.jmix.security.role.assignment.RoleAssignmentRoleType;
import io.jmix.securitydata.entity.RoleAssignmentEntity;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class DemoUsersTest {

    @Autowired
    private DataManager dataManager;

    @Autowired
    private AuthenticationManager authenticationManager;

    @ParameterizedTest
    @CsvSource({
            "peter, Peter, Wilson, peter.wilson@example.com",
            "alice, Alice, Morgan, alice.morgan@example.com",
            "helen, Helen, Carter, helen.carter@example.com",
            "maria, Maria, Lopez, maria.lopez@example.com",
            "john, John, Doe, john.doe@example.com"
    })
    void theDemoEmployeeIsAnActiveUser(String username, String firstName, String lastName, String email) {
        User user = dataManager.load(User.class)
                .query("e.username = ?1", username)
                .one();

        assertThat(user.getFirstName()).isEqualTo(firstName);
        assertThat(user.getLastName()).isEqualTo(lastName);
        assertThat(user.getEmail()).isEqualTo(email);
        assertThat(user.getActive()).isTrue();
    }

    @ParameterizedTest
    @CsvSource(nullValues = "-", value = {
            "peter, -",
            "alice, -",
            "helen, hr-department",
            "maria, accounting",
            "john, legal-department"
    })
    void theDemoEmployeeHasTheEmployeeRoleAndTheRoleOfTheirDepartment(String username, String department) {
        List<RoleAssignmentEntity> assignments = dataManager.load(RoleAssignmentEntity.class)
                .query("e.username = ?1", username)
                .list();

        List<Tuple> expected = department == null
                ? List.of(tuple("employee", RoleAssignmentRoleType.RESOURCE))
                : List.of(tuple("employee", RoleAssignmentRoleType.RESOURCE),
                        tuple(department, RoleAssignmentRoleType.RESOURCE));

        assertThat(assignments)
                .extracting(RoleAssignmentEntity::getRoleCode, RoleAssignmentEntity::getRoleType)
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"peter", "alice", "helen", "maria", "john"})
    void theDemoEmployeeSignsInWithTheUsernameAsThePassword(String username) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(username, username));

        assertThat(authentication.isAuthenticated()).isTrue();
    }

    @Test
    void aWrongPasswordIsRejected() {
        assertThatThrownBy(() -> authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken("peter", "alice")))
                .isInstanceOf(BadCredentialsException.class);
    }
}
