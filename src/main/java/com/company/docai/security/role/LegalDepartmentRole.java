package com.company.docai.security.role;

import io.jmix.security.model.SecurityScope;
import io.jmix.security.role.annotation.ResourceRole;

/**
 * The legal department: a role without rights of its own that folders and documents give access to.
 */
@ResourceRole(name = "msg://LegalDepartmentRole.name", code = LegalDepartmentRole.CODE,
        description = "msg://LegalDepartmentRole.description", scope = SecurityScope.UI)
public interface LegalDepartmentRole {

    String CODE = "legal-department";
}
