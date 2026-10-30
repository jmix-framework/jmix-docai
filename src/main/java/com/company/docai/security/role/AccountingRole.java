package com.company.docai.security.role;

import io.jmix.security.model.SecurityScope;
import io.jmix.security.role.annotation.ResourceRole;

/**
 * The accounting department: a role without rights of its own. Folders and documents give it access, as they would
 * give it to a group of the corporate directory mapped to the role.
 */
@ResourceRole(name = "msg://AccountingRole.name", code = AccountingRole.CODE,
        description = "msg://AccountingRole.description", scope = SecurityScope.UI)
public interface AccountingRole {

    String CODE = "accounting";
}
