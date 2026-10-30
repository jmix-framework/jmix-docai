package com.company.docai.security.role;

import io.jmix.security.model.SecurityScope;
import io.jmix.security.role.annotation.ResourceRole;

/**
 * The legal department: a role without rights of its own. Folders and documents give it access, as they would give it
 * to a group of the corporate directory mapped to the role.
 */
@ResourceRole(name = "msg://LegalDepartmentRole.name", code = LegalDepartmentRole.CODE,
        description = "msg://LegalDepartmentRole.description", scope = SecurityScope.UI)
public interface LegalDepartmentRole {

    String CODE = "legal-department";
}
