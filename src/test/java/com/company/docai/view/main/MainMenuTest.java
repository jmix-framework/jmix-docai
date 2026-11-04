package com.company.docai.view.main;

import com.company.docai.DocAiApplication;
import com.company.docai.test_support.TestMenus;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.main.JmixListMenu;
import io.jmix.flowui.kit.component.main.ListMenu;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The main menu of the administrator: under the folders, the trash and one closed group with the views of the
 * administrator and the items taken from the groups of the security and data tools add-ons; the groups of the add-ons
 * themselves, merged into hidden groups, do not show.
 */
@UiTest
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
class MainMenuTest {

    @Autowired
    private ViewNavigators viewNavigators;

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
                        "administrationSecurity", "administrationDataTools");
        assertThat(childIds(menu, "administrationSecurity")).containsExactly("resourceRoles", "rowLevelRoles");
        assertThat(childIds(menu, "administrationDataTools")).containsExactly("entityInspector", "dataModel");
    }

    @Test
    void theGroupsOfTheAddOnsAreHidden() {
        viewNavigators.view(UiTestUtils.getCurrentView(), MainView.class).navigate();
        JmixListMenu menu = TestMenus.listMenu("menu");

        assertThat(List.of("aitls", "security", "datatools")).allSatisfy(id -> {
            assertThat(menu.getMenuItem(id)).isNotNull();
            assertThat(menu.getMenuItem(id).isVisible()).isFalse();
        });
    }

    private static List<String> childIds(JmixListMenu menu, String groupId) {
        return ((ListMenu.MenuBarItem) menu.getMenuItem(groupId)).getChildItems().stream()
                .map(ListMenu.MenuItem::getId)
                .toList();
    }
}
