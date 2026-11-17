package com.company.docai.view.login;

import com.company.docai.stand.CopyNotMadeException;
import com.company.docai.stand.StandLogin;
import com.company.docai.stand.StandScope;
import com.company.docai.stand.TemplateNotReadyException;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.login.AbstractLogin.LoginEvent;
import com.vaadin.flow.component.login.LoginI18n;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.i18n.LocaleChangeEvent;
import com.vaadin.flow.i18n.LocaleChangeObserver;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinSession;
import io.jmix.core.CoreProperties;
import io.jmix.core.MessageTools;
import io.jmix.core.security.AccessDeniedException;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.component.loginform.JmixLoginForm;
import io.jmix.flowui.kit.component.ComponentUtils;
import io.jmix.flowui.kit.component.loginform.EnhancedLoginForm;
import io.jmix.flowui.kit.component.loginform.JmixLoginI18n;
import io.jmix.flowui.view.*;
import io.jmix.securityflowui.authentication.AuthDetails;
import io.jmix.securityflowui.authentication.LoginViewSupport;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.log.LogFormatUtils;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Collectors;

@Route(value = "login")
@ViewController(id = "LoginView")
@ViewDescriptor(path = "login-view.xml")
public class LoginView extends StandardView implements LocaleChangeObserver {

    private static final Logger log = LoggerFactory.getLogger(LoginView.class);

    @Autowired
    private CoreProperties coreProperties;

    @Autowired
    private LoginViewSupport loginViewSupport;

    @Autowired
    private MessageTools messageTools;

    @Autowired
    private Notifications notifications;

    // the login of the stand, present only in its profile
    @Autowired
    private ObjectProvider<StandLogin> standLogin;

    @ViewComponent
    private JmixLoginForm login;

    @ViewComponent
    private Span languageWarning;

    @ViewComponent
    private MessageBundle messageBundle;

    @Value("${ui.login.defaultUsername:}")
    private String defaultUsername;

    @Value("${ui.login.defaultPassword:}")
    private String defaultPassword;

    // the notice of an attempt on the stand that found no copy to work in, closed when the next attempt starts
    @Nullable
    private Notification standNotice;

    @Subscribe
    public void onInit(final InitEvent event) {
        initLocales();
        initDefaultCredentials();
        showLanguageWarning(login.getSelectedLocale());
    }

    private void initLocales() {
        LinkedHashMap<Locale, String> locales = coreProperties.getAvailableLocales().stream()
                .collect(Collectors.toMap(Function.identity(), messageTools::getLocaleDisplayName, (s1, s2) -> s1,
                        LinkedHashMap::new));

        ComponentUtils.setItemsMap(login, locales);

        // the stand opens in the language of the browser's demo data, so that the login keeps them, and in English
        // without them, whatever the language of the browser
        StandLogin stand = standLogin.getIfAvailable();

        login.setSelectedLocale(stand == null
                ? VaadinSession.getCurrent().getLocale()
                : stand.formLocale(coreProperties.getAvailableLocales()));
    }

    private void initDefaultCredentials() {
        if (StringUtils.isNotBlank(defaultUsername)) {
            login.setUsername(defaultUsername);
        }

        if (StringUtils.isNotBlank(defaultPassword)) {
            login.setPassword(defaultPassword);
        }
    }

    @Subscribe("login")
    public void onLogin(final LoginEvent event) {
        if (standNotice != null) {
            standNotice.close();
            standNotice = null;
        }

        try {
            // on the stand the users and the views of the visitor live in the copy of the language of the form
            standLogin.ifAvailable(stand -> stand.prepare(login.getSelectedLocale(), event.getUsername(),
                    event.getPassword()));

            loginViewSupport.authenticate(
                    AuthDetails.of(event.getUsername(), event.getPassword())
                            .withLocale(login.getSelectedLocale())
                            .withRememberMe(login.isRememberMe())
            );
        } catch (final TemplateNotReadyException e) {
            // the form disables itself on a submit and waits for another try
            event.getSource().setEnabled(true);
            standNotice = openNotice("standGettingReady", Notifications.Type.WARNING);
        } catch (final CopyNotMadeException e) {
            // what failed names paths and indexes of the server, which the log keeps with the tags of the copies for
            // their keys, and the visitor does not need
            log.error("A copy of the demo data is not made at a login: {}", StandScope.withTags(e));
            event.getSource().setEnabled(true);
            standNotice = openNotice("standUnavailable", Notifications.Type.ERROR);
        } catch (final BadCredentialsException | DisabledException | LockedException | AccessDeniedException e) {
            // a typed username may hold line breaks, which would forge lines of the log
            String username = LogFormatUtils.formatValue(event.getUsername(), -1, true);
            log.warn("Login failed for user {}: {}", username, e.toString());
            event.getSource().setError(true);
        }
    }

    private Notification openNotice(String key, Notifications.Type type) {
        Notification notice = notifications.create(messageBundle.getMessage(key))
                .withType(type)
                .build();

        notice.open();

        return notice;
    }

    @Subscribe("login")
    public void onLoginLocaleChanged(final EnhancedLoginForm.LocaleChangedEvent event) {
        showLanguageWarning(event.getValue());
    }

    // on the stand a login in another language than the one of the copy of the browser deletes that copy
    private void showLanguageWarning(Locale locale) {
        StandLogin stand = standLogin.getIfAvailable();
        languageWarning.setVisible(stand != null && stand.startsAnew(locale));
    }

    @Override
    public void localeChange(final LocaleChangeEvent event) {
        UI.getCurrent().getPage().setTitle(messageBundle.getMessage("LoginView.title"));

        final JmixLoginI18n loginI18n = JmixLoginI18n.createDefault();

        final JmixLoginI18n.JmixForm form = new JmixLoginI18n.JmixForm();
        form.setTitle(messageBundle.getMessage("loginForm.headerTitle"));
        form.setUsername(messageBundle.getMessage("loginForm.username"));
        form.setPassword(messageBundle.getMessage("loginForm.password"));
        form.setSubmit(messageBundle.getMessage("loginForm.submit"));
        form.setForgotPassword(messageBundle.getMessage("loginForm.forgotPassword"));
        form.setRememberMe(messageBundle.getMessage("loginForm.rememberMe"));
        loginI18n.setForm(form);

        final LoginI18n.ErrorMessage errorMessage = new LoginI18n.ErrorMessage();
        errorMessage.setTitle(messageBundle.getMessage("loginForm.errorTitle"));
        errorMessage.setMessage(messageBundle.getMessage("loginForm.badCredentials"));
        errorMessage.setUsername(messageBundle.getMessage("loginForm.errorUsername"));
        errorMessage.setPassword(messageBundle.getMessage("loginForm.errorPassword"));
        loginI18n.setErrorMessage(errorMessage);

        login.setI18n(loginI18n);

        languageWarning.setText(messageBundle.getMessage("languageWarning.text"));
    }
}
