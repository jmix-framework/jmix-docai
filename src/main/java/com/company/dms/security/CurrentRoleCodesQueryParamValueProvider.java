package com.company.dms.security;

import com.company.dms.security.role.AccessRowLevelRole;
import io.jmix.core.QueryParamValueProvider;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.security.role.RoleGrantedAuthorityUtils;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Provides the {@code :current_role_codes} query parameter of {@link AccessRowLevelRole}: the codes of the resource
 * roles in the authorities of the current authentication. The authorities are built at sign-in, so a change of
 * the user's roles takes effect at their next sign-in.
 */
@Component
public class CurrentRoleCodesQueryParamValueProvider implements QueryParamValueProvider {

    private static final String PARAMETER_NAME = "current_role_codes";

    // an empty IN list is not valid SQL, and no access entry has a blank role code
    private static final List<String> NO_ROLES = List.of("");

    private final CurrentAuthentication currentAuthentication;
    private final RoleGrantedAuthorityUtils roleGrantedAuthorityUtils;

    public CurrentRoleCodesQueryParamValueProvider(CurrentAuthentication currentAuthentication,
                                                   RoleGrantedAuthorityUtils roleGrantedAuthorityUtils) {
        this.currentAuthentication = currentAuthentication;
        this.roleGrantedAuthorityUtils = roleGrantedAuthorityUtils;
    }

    @Override
    public boolean supports(String paramName) {
        return PARAMETER_NAME.equals(paramName);
    }

    @Override
    public Object getValue(String paramName) {
        List<String> codes = currentRoleCodes();
        return codes.isEmpty() ? NO_ROLES : codes;
    }

    /**
     * Returns the codes of the resource roles in the authorities of the current authentication, or an empty list
     * when nobody is authenticated.
     */
    public List<String> currentRoleCodes() {
        if (!currentAuthentication.isSet()) {
            return List.of();
        }

        String prefix = roleGrantedAuthorityUtils.getDefaultRolePrefix();
        return currentAuthentication.getAuthentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(Objects::nonNull)
                .filter(authority -> authority.startsWith(prefix))
                .map(authority -> authority.substring(prefix.length()))
                .distinct()
                .toList();
    }
}
