package com.company.docai.view.main;

import com.company.docai.DocAiApplication;
import com.company.docai.test_support.TestMenus;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.main.JmixListMenu;
import io.jmix.flowui.kit.component.main.ListMenu;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.view.ViewRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The main menu of the administrator: under the folders, the trash, one closed group with the views of the
 * administrator and About.
 */
@UiTest
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
class MainMenuTest {

    @Autowired
    private ViewNavigators viewNavigators;

    @Autowired
    private ViewRegistry viewRegistry;

    @Test
    void documentTypesHaveTheirOwnItemAndTheAddOnGroupIsHidden() {
        viewNavigators.view(UiTestUtils.getCurrentView(), MainView.class).navigate();
        JmixListMenu menu = TestMenus.listMenu("menu");
        ListMenu.MenuItem documentTypes = menu.getMenuItem("documentTypes");
        ListMenu.MenuItem addOnGroup = menu.getMenuItem("dynat");

        assertThat(documentTypes).isNotNull();
        assertThat(documentTypes.isVisible()).isTrue();
        assertThat(addOnGroup).isNotNull();
        assertThat(addOnGroup.isVisible()).isFalse();
    }

    @Test
    void underTheFoldersStandTheTrashTheClosedAdministrationAndAbout() {
        viewNavigators.view(UiTestUtils.getCurrentView(), MainView.class).navigate();
        JmixListMenu menu = TestMenus.listMenu("menu");
        ListMenu.MenuItem administration = menu.getMenuItem("administration");

        assertThat(menu.getMenuItems())
                .filteredOn(ListMenu.MenuItem::isVisible)
                .extracting(ListMenu.MenuItem::getId)
                .containsExactly("TrashView", "administration", "about");
        assertThat(administration).isNotNull();
        assertThat(((ListMenu.MenuBarItem) administration).isOpened()).isFalse();
        // the root of the folder tree opens the repository
        assertThat(menu.getMenuItem("RepositoryView")).isNull();
    }

    @Test
    void theAdministrationHoldsTheViewsOfTheAdministratorAndTheItemsOfTheAddOns() {
        viewNavigators.view(UiTestUtils.getCurrentView(), MainView.class).navigate();
        JmixListMenu menu = TestMenus.listMenu("menu");

        assertThat(childIds(menu, "administration"))
                .containsExactly("User.list", "documentTypes", "DocumentStatus.list", "Tag.list",
                        "administrationSecurity");
        assertThat(childIds(menu, "administrationSecurity")).containsExactly("resourceRoles", "rowLevelRoles");
    }

    // the entity inspector edits any row of any entity, too much for a public stand
    @Test
    void theViewsOfDataToolsAreNotPartOfTheApplication() {
        viewNavigators.view(UiTestUtils.getCurrentView(), MainView.class).navigate();
        JmixListMenu menu = TestMenus.listMenu("menu");

        assertThat(viewRegistry.hasView("datatl_entityInspectorListView")).isFalse();
        assertThat(viewRegistry.hasView("datatl_dataModelListView")).isFalse();
        assertThat(menu.getMenuItem("datatools")).isNull();
        assertThat(menu.getMenuItem("administrationDataTools")).isNull();
    }

    private static List<String> childIds(JmixListMenu menu, String groupId) {
        ListMenu.MenuItem group = menu.getMenuItem(groupId);
        assertThat(group).isNotNull();

        return ((ListMenu.MenuBarItem) group).getChildItems().stream()
                .map(ListMenu.MenuItem::getId)
                .toList();
    }
}
