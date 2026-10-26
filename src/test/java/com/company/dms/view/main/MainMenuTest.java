package com.company.dms.view.main;

import com.company.dms.DMSApplication;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.main.JmixListMenu;
import io.jmix.flowui.kit.component.main.ListMenu;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The main menu of the administrator: document types have their own item, and the group of the dynamic
 * attributes add-on, merged into a hidden group of the application menu, does not show.
 */
@UiTest
@SpringBootTest(classes = {DMSApplication.class, FlowuiTestAssistConfiguration.class})
class MainMenuTest {

    @Autowired
    private ViewNavigators viewNavigators;

    @Test
    void documentTypesHaveTheirOwnItemAndTheAddOnGroupIsHidden() {
        viewNavigators.view(UiTestUtils.getCurrentView(), MainView.class).navigate();
        JmixListMenu menu = UiTestUtils.getComponent(UiTestUtils.getCurrentView(), "menu");
        ListMenu.MenuItem documentTypes = menu.getMenuItem("documentTypes");
        ListMenu.MenuItem addOnGroup = menu.getMenuItem("dynat");

        assertThat(documentTypes).isNotNull();
        assertThat(documentTypes.isVisible()).isTrue();
        assertThat(addOnGroup).isNotNull();
        assertThat(addOnGroup.isVisible()).isFalse();
    }
}
