package com.company.dms.security;

import com.company.dms.app.service.access.AccessService;
import com.company.dms.app.service.access.AccessSubject;
import com.company.dms.security.role.AccountingRole;
import com.company.dms.security.role.HrDepartmentRole;
import com.company.dms.security.role.LegalDepartmentRole;
import com.company.dms.test_support.AuthenticatedAsAdmin;
import io.jmix.core.DataManager;
import io.jmix.securitydata.entity.RoleAssignmentEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class DepartmentRolesTest {

    private static final List<String> DEPARTMENTS = List.of(HrDepartmentRole.CODE, LegalDepartmentRole.CODE,
            AccountingRole.CODE);

    @Autowired
    private DataManager dataManager;

    @Autowired
    private AccessService accessService;

    @Test
    void theDemoEmployeesBelongToTheirDepartments() {
        List<String> assignments = dataManager.load(RoleAssignmentEntity.class)
                .query("e.roleCode in :codes")
                .parameter("codes", DEPARTMENTS)
                .list()
                .stream()
                .map(assignment -> assignment.getUsername() + ":" + assignment.getRoleCode())
                .toList();

        assertThat(assignments).containsExactlyInAnyOrder("helen:" + HrDepartmentRole.CODE,
                "john:" + LegalDepartmentRole.CODE, "maria:" + AccountingRole.CODE);
    }

    @Test
    void theDepartmentsAreOfferedForAccessToFoldersAndDocuments() {
        assertThat(accessService.findSubjects("department", 0, 50))
                .extracting(AccessSubject::roleCode)
                .contains(HrDepartmentRole.CODE, LegalDepartmentRole.CODE);
        assertThat(accessService.findSubjects("accounting", 0, 50))
                .extracting(AccessSubject::roleCode)
                .contains(AccountingRole.CODE);
    }
}
