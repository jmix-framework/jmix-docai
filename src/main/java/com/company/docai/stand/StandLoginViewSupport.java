package com.company.docai.stand;

import com.vaadin.flow.server.VaadinServletRequest;
import com.vaadin.flow.server.VaadinServletResponse;
import io.jmix.core.Entity;
import io.jmix.core.Id;
import io.jmix.core.security.SecurityContextHelper;
import io.jmix.securityflowui.authentication.AuthDetails;
import io.jmix.securityflowui.authentication.AuthDetailsValidationResult;
import io.jmix.securityflowui.authentication.AuthDetailsValidator;
import io.jmix.securityflowui.authentication.LoginViewSupport;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Locale;

/**
 * Takes Jmix's login steps for the sign-in {@link StandSignIn} makes on a language switch, so they stay Jmix's own.
 */
public class StandLoginViewSupport extends LoginViewSupport {

    /**
     * The name of the bean, by which the switch takes it.
     */
    public static final String NAME = "stand_LoginViewSupport";

    /**
     * Runs the validators of the details of a login on the details of a switch: the username, the language and no
     * password.
     *
     * @throws DisabledException when a validator refuses
     */
    protected void validate(String username, Locale locale) {
        AuthDetails details = detailsOf(username, locale);

        for (AuthDetailsValidator validator : authDetailsValidators) {
            AuthDetailsValidationResult result = validator.validate(details);

            if (!result.isValid()) {
                throw new DisabledException(result.getMessage(), result.getException());
            }
        }
    }

    /**
     * Signs the authentication in to the session of the current request as Jmix's login does once the password is
     * checked, a new session id against session fixation and the registry of sessions with its limit per user included;
     * the view the browser opens is the caller's.
     *
     * @param authentication what {@link StandSignIn#authenticate} gave
     * @throws InsufficientAuthenticationException when the authentication is not the user the session is signed in as,
     *                                             before any change
     */
    protected void signIn(Authentication authentication, Locale locale) {
        checkVisitor(authentication);

        // Jmix's login sets the authentication on the current context, which on a signed-in session is the one the
        // other threads of the session share: a context of its own becomes the one the session keeps
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContextHelper.installContext(context);

        try {
            preventSessionFixation(authentication);
            onSuccessfulAuthentication(authentication, detailsOf(authentication.getName(), locale));
        } finally {
            SecurityContextHelper.restoreContext(previous);
        }

        // the rest of the request works as the session does now
        SecurityContextHolder.setContext(context);
    }

    // Jmix's login opens in the current tab the address the visitor asked for before signing in, which after the switch
    // may name records of the old copy: the item of the switch loads the main view in every tab instead
    @Override
    protected void showInitialView(VaadinServletRequest request, VaadinServletResponse response) {
    }

    // a switch signs in again the very user the login of the session gave, as the row of another copy
    private static void checkVisitor(Authentication authentication) {
        if (!(SecurityContextHelper.getAuthentication() instanceof UsernamePasswordAuthenticationToken login)
                || !login.isAuthenticated()
                || !(login.getPrincipal() instanceof Entity visitor)
                || !(authentication.getPrincipal() instanceof Entity user)
                || !Id.of(user).equals(Id.of(visitor))) {
            throw new InsufficientAuthenticationException("The authentication is not the user of the session");
        }
    }

    // no password: a switch checks none, and Jmix's later steps read only the username, the language and remember-me
    private static AuthDetails detailsOf(String username, Locale locale) {
        return AuthDetails.of(username, "")
                .withLocale(locale);
    }
}
