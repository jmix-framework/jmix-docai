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
 * The main menu of the administrator: under the folders, the trash and one closed group with the views of the
 * administrator and the items taken from the group of the security add-on; the groups of the add-ons themselves,
 * merged into hidden groups, do not show. The views of Data Tools are not part of the application.
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
    void underTheFoldersStandTheTrashAndTheClosedAdministration() {
        viewNavigators.view(UiTestUtils.getCurrentView(), MainView.class).navigate();
        JmixListMenu menu = TestMenus.listMenu("menu");

        assertThat(menu.getMenuItems())
                .filteredOn(ListMenu.MenuItem::isVisible)
                .extracting(ListMenu.MenuItem::getId)
                .containsExactly("TrashView", "administration");
        assertThat(((ListMenu.MenuBarItem) menu.getMenuItem("administration")).isOpened()).isFalse();
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

    @Test
    void theGroupsOfTheAddOnsAreHidden() {
        viewNavigators.view(UiTestUtils.getCurrentView(), MainView.class).navigate();
        JmixListMenu menu = TestMenus.listMenu("menu");

        assertThat(List.of("aitls", "security")).allSatisfy(id -> {
            assertThat(menu.getMenuItem(id)).isNotNull();
            assertThat(menu.getMenuItem(id).isVisible()).isFalse();
        });
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
        return ((ListMenu.MenuBarItem) menu.getMenuItem(groupId)).getChildItems().stream()
                .map(ListMenu.MenuItem::getId)
                .toList();
    }
}
