package com.company.docai.app.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.function.support.RouterFunctionMapping;
import org.springframework.web.servlet.handler.AbstractUrlHandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The security filters of the pages: what a page may load, where an address of no view and an ended session lead, and
 * which handlers outside Vaadin take requests.
 */
@SpringBootTest
class UiSecurityConfigurationTest {

    private static final String MARK = "DOCAI_SESSION_ENDED";

    private static final String NEW_ENDPOINT = "A new endpoint outside Vaadin needs a security filter chain of its "
            + "own, as Spring Boot Actuator would: UiSecurityConfiguration lets any signed-in user reach an address of "
            + "no view";

    @Autowired
    private FilterChainProxy securityFilters;

    @Autowired
    private SessionRegistry sessionRegistry;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void aPageLoadsPicturesMediaStylesAndFontsFromTheApplicationOnly() throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        securityFilters.doFilter(new MockHttpServletRequest("GET", "/login"), response, new MockFilterChain());

        assertThat(response.getHeader("Content-Security-Policy")).isEqualTo("img-src 'self' data: blob:; "
                + "media-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; font-src 'self' data:");
    }

    @Test
    void anAddressOfNoViewSendsAnAnonymousVisitorToTheLogin() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/no-such-view");
        MockHttpServletResponse response = new MockHttpServletResponse();

        Authentication anonymous = new AnonymousAuthenticationToken("anonymous", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

        // the whole chain would also keep the request for after the login, which needs the routes of a running Vaadin
        AuthorizationResult decision = securityFilter(AuthorizationFilter.class, "/no-such-view")
                .getAuthorizationManager()
                .authorize(() -> anonymous, request);

        securityFilter(ExceptionTranslationFilter.class, "/no-such-view")
                .getAuthenticationEntryPoint()
                .commence(request, response, new InsufficientAuthenticationException("signed out"));

        assertThat(decision).isNotNull();
        assertThat(decision.isGranted()).isFalse();
        assertThat(response.getRedirectedUrl()).endsWith("/login");
    }

    @Test
    void anAddressOfNoViewReachesTheApplicationForASignedInUser() throws ServletException, IOException {
        MockFilterChain application = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        securityFilters.doFilter(signedIn(new MockHttpServletRequest("GET", "/no-such-view")), response,
                application);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(application.getRequest()).isNotNull();
    }

    @Test
    void theRequestHandlersOutsideVaadinAreTheKnownOnes() {
        List<Class<?>> controllers = applicationContext.getBeansOfType(RequestMappingInfoHandlerMapping.class)
                .values()
                .stream()
                .flatMap(mapping -> mapping.getHandlerMethods().values().stream())
                .<Class<?>>map(HandlerMethod::getBeanType)
                .distinct()
                .toList();

        Map<String, Set<String>> addresses = applicationContext.getBeansOfType(HandlerMapping.class)
                .entrySet()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> addressesOf(entry.getValue())));

        assertThat(controllers).as(NEW_ENDPOINT)
                .containsExactly(BasicErrorController.class);
        assertThat(addresses).as(NEW_ENDPOINT)
                .isEqualTo(Map.of(
                        "vaadinRootMapping", Set.of("/**"),
                        "resourceHandlerMapping", Set.of("/webjars/**", "/**"),
                        "requestMappingHandlerMapping", Set.of("/error"),
                        "routerFunctionMapping", Set.of(),
                        "beanNameHandlerMapping", Set.of(),
                        "welcomePageHandlerMapping", Set.of(),
                        "welcomePageNotAcceptableHandlerMapping", Set.of()));
        assertThat(applicationContext.getBeanNamesForType(ServletRegistrationBean.class)).as(NEW_ENDPOINT)
                .containsExactlyInAnyOrder("dispatcherServletRegistration", "servletRegistrationBean");
    }

    @Test
    void aPageRequestOfASessionTheLimitEndedGoesToThePageThatSaysSo() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/repository");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filterWithEndedSession(request, response);

        assertThat(response.getRedirectedUrl()).isEqualTo("/session-ended");
        assertMarkedForTwoMinutes(response);
    }

    @ParameterizedTest
    @ValueSource(strings = {"uidl", "heartbeat"})
    void aRequestOfAnOpenPageOfASessionTheLimitEndedTellsItsClientToGoToThatPage(String type)
            throws ServletException, IOException {
        // the client of a heartbeat reads no answer, so only the mark tells the next view the browser loads
        MockHttpServletRequest request = vaadinRequest(type);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filterWithEndedSession(request, response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("Vaadin-Refresh: /session-ended");
        assertMarkedForTwoMinutes(response);
    }

    @Test
    void aMarkedBrowserThatLoadsAViewSignedOutGoesToThePageInsteadOfTheLogin() throws ServletException, IOException {
        AuthenticationEntryPoint entryPoint = securityFilter(ExceptionTranslationFilter.class, "/repository")
                .getAuthenticationEntryPoint();

        MockHttpServletRequest marked = new MockHttpServletRequest("GET", "/repository");
        marked.setCookies(new Cookie(MARK, "1"));
        MockHttpServletResponse markedResponse = new MockHttpServletResponse();
        MockHttpServletResponse plainResponse = new MockHttpServletResponse();

        entryPoint.commence(marked, markedResponse, new InsufficientAuthenticationException("signed out"));

        entryPoint.commence(new MockHttpServletRequest("GET", "/repository"), plainResponse,
                new InsufficientAuthenticationException("signed out"));

        assertThat(markedResponse.getRedirectedUrl()).isEqualTo("/session-ended");
        // the mark stays for every tab that reloads in its time; a login or its age clears it
        assertThat(markedResponse.getCookie(MARK)).isNull();
        assertThat(plainResponse.getRedirectedUrl()).endsWith("/login");
        assertThat(plainResponse.getCookie(MARK)).isNull();
    }

    private <T> T securityFilter(Class<T> type, String address) {
        return Objects.requireNonNull(securityFilters.getFilters(address))
                .stream()
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst()
                .orElseThrow();
    }

    private static Set<String> addressesOf(HandlerMapping mapping) {
        return switch (mapping) {
            case AbstractUrlHandlerMapping urls -> urls.getHandlerMap().keySet();
            case RequestMappingInfoHandlerMapping methods -> methods.getHandlerMethods()
                    .keySet()
                    .stream()
                    .flatMap(info -> info.getPatternValues().stream())
                    .collect(Collectors.toSet());
            case RouterFunctionMapping functions -> Optional.ofNullable(functions.getRouterFunction())
                    .map(router -> Set.of(router.toString()))
                    .orElse(Set.of());
            // a kind of mapping that this test cannot read
            default -> Set.of(mapping.getClass().getName());
        };
    }

    private static void assertMarkedForTwoMinutes(MockHttpServletResponse response) {
        Cookie mark = response.getCookie(MARK);

        assertThat(mark).isNotNull();
        assertThat(mark.getMaxAge()).isEqualTo(120);
        assertThat(mark.isHttpOnly()).isTrue();
        assertThat(mark.getPath()).isEqualTo("/");
    }

    private static MockHttpServletRequest vaadinRequest(String type) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/");
        request.setParameter("v-r", type);
        request.setParameter("v-uiId", "0");

        return request;
    }

    private void filterWithEndedSession(MockHttpServletRequest request, MockHttpServletResponse response)
            throws ServletException, IOException {
        MockHttpSession session = new MockHttpSession();
        sessionRegistry.registerNewSession(session.getId(), "ended-session-test");

        Objects.requireNonNull(sessionRegistry.getSessionInformation(session.getId()))
                .expireNow();

        request.setSession(session);

        try {
            securityFilters.doFilter(request, response, new MockFilterChain());
        } finally {
            sessionRegistry.removeSessionInformation(session.getId());
        }
    }

    private static MockHttpServletRequest signedIn(MockHttpServletRequest request) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("peter", null, List.of()));

        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        request.setSession(session);

        return request;
    }
}
