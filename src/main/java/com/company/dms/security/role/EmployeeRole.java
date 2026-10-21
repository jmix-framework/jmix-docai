package com.company.dms.security.role;

import com.company.dms.model.user.User;
import io.jmix.security.model.EntityAttributePolicyAction;
import io.jmix.security.model.EntityPolicyAction;
import io.jmix.security.model.SecurityScope;
import io.jmix.security.role.annotation.EntityAttributePolicy;
import io.jmix.security.role.annotation.EntityPolicy;
import io.jmix.security.role.annotation.ResourceRole;

@ResourceRole(name = "msg://EmployeeRole.name", code = EmployeeRole.CODE,
        description = "msg://EmployeeRole.description", scope = SecurityScope.UI)
public interface EmployeeRole extends UiMinimalRole {

    String CODE = "employee";

    @EntityPolicy(entityClass = User.class, actions = EntityPolicyAction.READ)
    @EntityAttributePolicy(entityClass = User.class,
            attributes = {"username", "firstName", "lastName", "email", "timeZoneId"},
            action = EntityAttributePolicyAction.VIEW)
    void user();
}
