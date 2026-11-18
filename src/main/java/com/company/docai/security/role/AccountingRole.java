package com.company.docai.security.role;

import io.jmix.security.model.SecurityScope;
import io.jmix.security.role.annotation.ResourceRole;

/**
 * The accounting department: a role without rights of its own that folders and documents give access to.
 */
@ResourceRole(name = "msg://AccountingRole.name", code = AccountingRole.CODE,
        description = "msg://AccountingRole.description", scope = SecurityScope.UI)
public interface AccountingRole {

    String CODE = "accounting";
}
