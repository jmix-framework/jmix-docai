package com.company.docai.security.role;

import io.jmix.security.model.SecurityScope;
import io.jmix.security.role.annotation.ResourceRole;

/**
 * The HR department: a role without rights of its own that folders and documents give access to.
 */
@ResourceRole(name = "msg://HrDepartmentRole.name", code = HrDepartmentRole.CODE,
        description = "msg://HrDepartmentRole.description", scope = SecurityScope.UI)
public interface HrDepartmentRole {

    String CODE = "hr-department";
}
