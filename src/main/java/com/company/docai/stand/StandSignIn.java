package com.company.docai.stand;

import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.WrappedSession;
import io.jmix.core.AccessManager;
import io.jmix.core.Entity;
import io.jmix.core.Id;
import io.jmix.core.security.AccessDeniedException;
import io.jmix.core.security.ClientDetails;
import io.jmix.core.security.PostAuthenticationChecks;
import io.jmix.core.security.PreAuthenticationChecks;
import io.jmix.core.security.SecurityContextHelper;
import io.jmix.core.security.UserRepository;
import io.jmix.security.model.SecurityScope;
import io.jmix.securityflowui.accesscontext.UiLoginToUiContext;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;

/**
 * Signs the user a visitor is signed in as in to another copy of the demo data without asking for the password again:
 * the user comes from the copy, with its authorities there, and Jmix's own login takes the steps it takes once the
 * password is checked, see {@link StandLoginViewSupport}. Which user is signed in comes only from the authentication
 * of the session, never from the request, and the copy has to have that very user, not only the username.
 */
@Component
@Profile("stand")
public class StandSignIn {

    private static final Logger log = LoggerFactory.getLogger(StandSignIn.class);

    private final UserRepository userRepository;
    private final PreAuthenticationChecks preAuthenticationChecks;
    private final PostAuthenticationChecks postAuthenticationChecks;
    private final AccessManager accessManager;
    private final StandLoginViewSupport loginViewSupport;

    public StandSignIn(UserRepository userRepository,
                       PreAuthenticationChecks preAuthenticationChecks,
                       PostAuthenticationChecks postAuthenticationChecks,
                       AccessManager accessManager,
                       @Qualifier(StandLoginViewSupport.NAME) StandLoginViewSupport loginViewSupport) {
        this.userRepository = userRepository;
        this.preAuthenticationChecks = preAuthenticationChecks;
        this.postAuthenticationChecks = postAuthenticationChecks;
        this.accessManager = accessManager;
        this.loginViewSupport = loginViewSupport;
    }

    /**
     * The user the visitor is signed in as: the principal of the authentication of the session, which the login of the
     * application gave.
     *
     * @throws InsufficientAuthenticationException when the session has no such authentication, as when the visitor
     *                                             works as a substituted user
     */
    public UserDetails visitor() {
        return (UserDetails) login().getPrincipal();
    }

    /**
     * Runs the validators of the details of a login that Jmix's login runs before it checks the credentials, on the
     * visitor and the language of a switch, see {@link StandLoginViewSupport#validate}.
     *
     * @throws org.springframework.security.authentication.DisabledException when a validator refuses the switch
     */
    public void validate(UserDetails visitor, Locale locale) {
        loginViewSupport.validate(visitor.getUsername(), locale);
    }

    /**
     * Loads the visitor as the database of the thread has them, a copy or the control one, and checks their account
     * as Jmix's login does.
     *
     * @throws org.springframework.security.core.AuthenticationException when the database has no such user, has
     *         another user under the username, or the account may not sign in
     */
    public UserDetails check(UserDetails visitor) {
        UserDetails user = userRepository.loadUserByUsername(visitor.getUsername());

        // the copies keep the rows of their template with their ids, and a user the visitor made in their copy has an
        // id of its own: the username alone would sign them in as the demo user of that name
        if (!isSameUser(user, visitor)) {
            throw new UsernameNotFoundException("The user of the session is not in the database");
        }

        preAuthenticationChecks.check(user);
        postAuthenticationChecks.check(user);

        return user;
    }

    /**
     * Authenticates the user of the session again, as the copy of the scope of the thread has it: the user loaded
     * from the copy with its authorities there and the factors the login proved, and the details of the login with
     * the language. The account and the right to use the UI are checked as Jmix's login checks them, see
     * {@link #check}. Nothing changes: {@link #signIn} does.
     *
     * @throws org.springframework.security.core.AuthenticationException when the session has no login of the
     *         application, see {@link #visitor}, or the copy does not take the user, see {@link #check}
     * @throws AccessDeniedException when the user may not use the UI in the copy
     */
    public Authentication authenticate(Locale locale) {
        Authentication login = login();
        UserDetails user = check((UserDetails) login.getPrincipal());

        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(user,
                null, authoritiesOf(user, login));

        authentication.setDetails(detailsOf(login, locale));
        checkLoginToUi(authentication);

        return authentication;
    }

    /**
     * Signs the authentication in to the session of the current request through the steps of Jmix's login, see
     * {@link StandLoginViewSupport#signIn}, and gives the Vaadin session the language, as the login form does. A step
     * that fails once the session works as the authentication, as a listener of the event of the login, is logged, and
     * the sign-in completes.
     *
     * @param authentication what {@link #authenticate} gave
     * @param locale         the language the authentication has
     * @throws RuntimeException what failed before the session works as the authentication
     */
    public void signIn(Authentication authentication, Locale locale) {
        try {
            loginViewSupport.signIn(authentication, locale);
        } catch (RuntimeException e) {
            SecurityContext saved = savedContextOf(authentication);

            if (saved == null) {
                throw e;
            }

            log.error("A step of the sign-in failed once the session worked as the user of the new copy, who stays "
                    + "signed in: {}", StandScope.withTags(e));

            // the rest of the request works as the session does now
            SecurityContextHolder.setContext(saved);
        }

        VaadinSession.getCurrent()
                .setLocale(locale);
    }

    // the security context the session keeps for its threads and its next requests, when it holds the authentication;
    // a session that another request of the visitor ends meanwhile keeps none
    private static @Nullable SecurityContext savedContextOf(Authentication authentication) {
        try {
            WrappedSession session = VaadinRequest.getCurrent()
                    .getWrappedSession(false);

            Object context = session == null
                    ? null
                    : session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);

            return context instanceof SecurityContext saved && saved.getAuthentication() == authentication
                    ? saved
                    : null;
        } catch (IllegalStateException e) {
            return null;
        }
    }

    // the login of the application: anything else, a substituted user or the system, is no visitor to sign in again
    private static UsernamePasswordAuthenticationToken login() {
        if (!(SecurityContextHelper.getAuthentication() instanceof UsernamePasswordAuthenticationToken login)
                || !login.isAuthenticated()
                || !(login.getPrincipal() instanceof UserDetails)) {
            throw new InsufficientAuthenticationException("No user is signed in with the login of the application");
        }

        return login;
    }

    private static boolean isSameUser(UserDetails user, UserDetails visitor) {
        return user instanceof Entity
                && visitor instanceof Entity
                && Id.of(user).equals(Id.of(visitor));
    }

    // the factors the login proved stay, as Jmix's substitution of a user keeps them; no factor is added, since no
    // credentials are checked again
    private static Collection<GrantedAuthority> authoritiesOf(UserDetails user, Authentication login) {
        Collection<GrantedAuthority> authorities = new LinkedHashSet<>(user.getAuthorities());

        login.getAuthorities()
                .stream()
                .filter(FactorGrantedAuthority.class::isInstance)
                .forEach(authorities::add);

        return authorities;
    }

    // the details of the login with the language, as the language item keeps them outside the stand
    private static ClientDetails detailsOf(Authentication login, Locale locale) {
        ClientDetails.Builder details = ClientDetails.builder();

        if (login.getDetails() instanceof ClientDetails clientDetails) {
            details.of(clientDetails);
        } else {
            details.scope(SecurityScope.UI);
        }

        return details.locale(locale)
                .build();
    }

    // with the authentication for this thread only: the context of the session stays as it is until the sign-in
    private void checkLoginToUi(Authentication authentication) {
        UiLoginToUiContext loginToUi = new UiLoginToUiContext();
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHelper.installContext(context);

        try {
            accessManager.applyRegisteredConstraints(loginToUi);
        } finally {
            SecurityContextHelper.restoreContext(previous);
        }

        if (!loginToUi.isPermitted()) {
            throw new AccessDeniedException("specific", loginToUi.getName());
        }
    }
}
