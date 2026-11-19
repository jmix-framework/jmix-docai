package com.company.docai.view.usagehelp;

import com.company.docai.stand.StandLogin;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The usage scenarios that the login form opens: the users to sign in as and what to try. The form opens the view
 * before the sign-in, so it is open to everybody.
 */
@AnonymousAllowed
@ViewController(id = "UsageHelpView")
@ViewDescriptor(path = "usage-help-view.xml")
@DialogMode(width = "40em", closeOnEsc = true, closeOnOutsideClick = true)
public class UsageHelpView extends StandardView {

    @Autowired
    private ObjectProvider<StandLogin> standLogin;

    @ViewComponent
    private Paragraph privateNote;

    @Subscribe
    public void onInit(final InitEvent event) {
        privateNote.setVisible(standLogin.getIfAvailable() != null);
    }
}
