package com.company.docai.security.role;

import io.jmix.security.model.SecurityScope;
import io.jmix.security.role.annotation.ResourceRole;

/**
 * The HR department: a role without rights of its own. Folders and documents give it access, as they would give it
 * to a group of the corporate directory mapped to the role.
 */
@ResourceRole(name = "msg://HrDepartmentRole.name", code = HrDepartmentRole.CODE,
        description = "msg://HrDepartmentRole.description", scope = SecurityScope.UI)
public interface HrDepartmentRole {

    String CODE = "hr-department";
}
