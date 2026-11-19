package com.company.docai.view.notfound;

import com.company.docai.view.login.LoginView;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.icon.SvgIcon;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.ErrorParameter;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.NotFoundException;
import com.vaadin.flow.router.RouteNotFoundError;
import com.vaadin.flow.server.HttpStatusCode;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import io.jmix.core.Messages;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.flowui.UiProperties;
import io.jmix.flowui.view.navigation.ViewNavigationSupport;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AnonymousAuthenticationToken;

import java.util.List;

/**
 * The page of an address that leads to no view: says that such a page does not exist and offers the way back, the
 * start page to a signed-in user and the login to an anonymous visitor. An anonymous visitor who loads such an address
 * goes to the login first, by {@link com.company.docai.app.config.UiSecurityConfiguration}, and meets this page only
 * on a navigation inside an open page. A view the user may not open comes here too and keeps the answer of Jmix.
 */
@AnonymousAllowed
public class PageNotFoundError extends RouteNotFoundError implements HasDynamicTitle {

    private final Messages messages;
    private final CurrentAuthentication currentAuthentication;
    private final ViewNavigationSupport viewNavigationSupport;
    private final UiProperties uiProperties;

    @Nullable
    private String pageTitle;

    public PageNotFoundError(Messages messages, CurrentAuthentication currentAuthentication,
                             ViewNavigationSupport viewNavigationSupport, UiProperties uiProperties) {
        this.messages = messages;
        this.currentAuthentication = currentAuthentication;
        this.viewNavigationSupport = viewNavigationSupport;
        this.uiProperties = uiProperties;
    }

    @Override
    public int setErrorParameter(BeforeEnterEvent event, ErrorParameter<NotFoundException> parameter) {
        // Vaadin keeps this instance for the next navigation that ends here too
        getElement().removeAllChildren();
        getElement().getClassList().clear();
        pageTitle = null;

        // Vaadin turns a view the user may not open into this error at the view's own address
        boolean viewAddress = event.getSource()
                .getRegistry()
                .getNavigationTarget(event.getLocation().getPath())
                .isPresent();

        if (viewAddress) {
            return super.setErrorParameter(event, parameter);
        }

        boolean signedIn = currentAuthentication.isSet()
                && !(currentAuthentication.getAuthentication() instanceof AnonymousAuthenticationToken);

        SvgIcon logo = new SvgIcon("themes/jmix-docai-aura/images/logo.svg");
        logo.setSize("40px");
        logo.getElement().getClassList().add("login-brand-logo");

        H1 name = new H1(messages.getMessage(LoginView.class, "brandName"));
        name.addClassName("login-brand-name");

        Div brand = new Div(logo, name);
        brand.addClassName("login-brand");

        H2 heading = new H2(message("pageNotFound.heading"));
        heading.addClassName("message-heading");

        Paragraph explanation = new Paragraph(message("pageNotFound.text"));
        explanation.addClassName("message-text");

        // by the view's id, as Jmix navigates: a view that Studio's hot deploy loaded anew is not the class literal
        Button wayBack = signedIn
                ? new Button(message("homeButton.text"),
                        click -> viewNavigationSupport.navigate(uiProperties.getMainViewId()))
                : new Button(message("loginButton.text"),
                        click -> viewNavigationSupport.navigate(uiProperties.getLoginViewId()));

        wayBack.addThemeVariants(ButtonVariant.PRIMARY);

        Div card = new Div(brand, heading, explanation, wayBack);
        card.addClassName("login-card");

        getElement().getClassList().addAll(List.of("login-view", "message-page"));
        getElement().appendChild(card.getElement());
        pageTitle = message("pageNotFound.title");

        return HttpStatusCode.NOT_FOUND.getCode();
    }

    @Override
    @Nullable
    public String getPageTitle() {
        return pageTitle;
    }

    private String message(String key) {
        return messages.getMessage(PageNotFoundError.class, key);
    }
}
