package com.company.dms.view.profile;

import com.company.dms.model.user.User;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.model.InstanceContainer;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.ReadEntityContainer;
import io.jmix.flowui.view.StandardReadView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.Target;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

@ViewController(id = "ProfileReadView")
@ViewDescriptor(path = "profile-read-view.xml")
@ReadEntityContainer("userDc")
@DialogMode(width = "32em")
public class ProfileReadView extends StandardReadView<User> {

    @Autowired
    private CurrentAuthentication currentAuthentication;

    @ViewComponent
    private TypedTextField<String> timeZoneField;

    @ViewComponent
    private MessageBundle messageBundle;

    @Subscribe(id = "userDc", target = Target.DATA_CONTAINER)
    public void onUserDcItemChange(final InstanceContainer.ItemChangeEvent<User> event) {
        timeZoneField.setValue(getTimeZoneText(event.getItem()));
    }

    private String getTimeZoneText(User user) {
        if (user.getTimeZoneId() != null) {
            return user.getTimeZoneId();
        }

        return messageBundle.formatMessage("browserTimeZone", currentAuthentication.getTimeZone().getID());
    }
}
