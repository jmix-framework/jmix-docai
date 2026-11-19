package com.company.docai.view.sessionended;

import com.company.docai.view.login.LoginView;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The page of a browser whose session the limit of sessions per user ended: says why and offers to log in again. The
 * browser comes here signed out, so the page is open to everybody.
 */
@AnonymousAllowed
@Route(value = SessionEndedView.ROUTE)
@ViewController(id = "SessionEndedView")
@ViewDescriptor(path = "session-ended-view.xml")
public class SessionEndedView extends StandardView {

    /**
     * The route of the page.
     */
    public static final String ROUTE = "session-ended";

    @Autowired
    private ViewNavigators viewNavigators;

    @Subscribe(id = "loginButton", subject = "clickListener")
    public void onLoginButtonClick(final ClickEvent<JmixButton> event) {
        viewNavigators.view(this, LoginView.class)
                .navigate();
    }
}
