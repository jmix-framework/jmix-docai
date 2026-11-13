package com.company.docai.view.main;

import com.company.docai.DocAiApplication;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.TestDashboards;
import com.company.docai.test_support.TestFiles;
import com.company.docai.test_support.TestMenus;
import com.company.docai.view.dashboard.DashboardFragment;
import com.company.docai.view.document.DocumentCards;
import com.company.docai.view.document.DocumentDetailView;
import com.company.docai.view.repository.RepositoryView;
import com.company.docai.view.status.DocumentStatusListView;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.router.QueryParameters;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import io.jmix.core.Messages;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.grid.TreeDataGrid;
import io.jmix.flowui.settings.UserSettingsCache;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.testassist.notification.NotificationInfo;
import io.jmix.flowui.testassist.notification.OpenedNotifications;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dashboard in the main view of an employee: it stands at the root in place of a view and names itself in the
 * header, reads its blocks again whenever the root shows, and opens its documents in their cards over itself.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
class MainViewDashboardTest {

    @Autowired
    private ViewNavigators viewNavigators;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private OpenedNotifications openedNotifications;

    @Autowired
    private UserSettingsCache userSettingsCache;

    @Autowired
    private Messages messages;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(folderService.getRoot());
        folder.setName("main-view-dashboard-test-" + UUID.randomUUID());
        sandbox = folderService.save(folder);
    }

    @AfterEach
    void deleteSandbox() {
        dataManager.load(Id.of(sandbox))
                .optional()
                .ifPresent(folderService::delete);
        // leaving a view saves the settings of its fragments for the user
        userSettingsCache.delete("MainView.folderTree");
        userSettingsCache.delete("RepositoryView.documentList");
    }

    @Test
    void theRootShowsTheDashboardUnderTheTitleHomeWithNoFolderChosen() {
        MainView mainView = TestMenus.mainView();
        TreeDataGrid<Folder> folders = TestMenus.folderTreeComponent("foldersTree");

        assertThat(dashboard().getParent()).contains(mainView.getInitialLayout());

        assertThat(UiTestUtils.<H1>getComponent(mainView, "viewTitle").getText())
                .isEqualTo(messages.getMessage(DashboardFragment.class, "dashboardFragment.title"));

        assertThat(folders.getSelectedItems()).isEmpty();
    }

    @Test
    void aDocumentUploadedOnAnotherViewShowsWhenTheRootShowsAgain() {
        viewNavigators.view(UiTestUtils.getCurrentView(), RepositoryView.class)
                .withQueryParameters(QueryParameters.of(RepositoryView.FOLDER_PARAMETER, sandbox.getId().toString()))
                .navigate();

        Document scan = upload();

        viewNavigators.view(UiTestUtils.getCurrentView(), MainView.class)
                .navigate();

        assertThat(TestDashboards.hasRow(dashboard(), "untypedBox", scan.getName())).isTrue();

        assertThat(UiTestUtils.<H1>getComponent(TestMenus.mainView(), "viewTitle").getText())
                .isEqualTo(messages.getMessage(DashboardFragment.class, "dashboardFragment.title"));
    }

    @Test
    void theDashboardReadsOnlyWhileTheRootShowsIt() {
        H2 greeting = TestDashboards.component(dashboard(), "greeting");
        // a text the dashboard never writes: it stays while the dashboard is hidden and goes when the dashboard reads
        String marker = "Not read since " + UUID.randomUUID();
        greeting.setText(marker);

        viewNavigators.view(UiTestUtils.getCurrentView(), RepositoryView.class)
                .navigate();

        viewNavigators.view(UiTestUtils.getCurrentView(), DocumentStatusListView.class)
                .navigate();

        assertThat(greeting.getText()).isEqualTo(marker);

        viewNavigators.view(UiTestUtils.getCurrentView(), MainView.class)
                .navigate();

        assertThat(greeting.getText()).isNotEqualTo(marker);
    }

    @Test
    void aDocumentOpensInItsCardAndTheDashboardFollowsWhatHappenedMeanwhile() {
        Document scan = upload();
        DashboardFragment dashboard = dashboard();
        dashboard.refresh();

        TestDashboards.click(TestDashboards.row(dashboard, "untypedBox", scan.getName()));
        DocumentDetailView card = UiTestUtils.getLastOpenedViewDialog();

        assertThat(card).isNotNull();
        assertThat(card.getEditedEntity()).isEqualTo(scan);

        // another person puts the document into the trash while its card is open
        documentService.delete(List.of(scan));
        card.closeWithDiscard();

        assertThat(TestDashboards.hasRow(dashboard, "untypedBox", scan.getName())).isFalse();
    }

    @Test
    void aDocumentDeletedSinceTheDashboardShowedIsSaidToBeUnavailable() {
        Document scan = upload();
        DashboardFragment dashboard = dashboard();
        dashboard.refresh();
        documentService.delete(List.of(scan));
        openedNotifications.closeOpenedNotifications();

        TestDashboards.click(TestDashboards.row(dashboard, "untypedBox", scan.getName()));
        NotificationInfo notice = openedNotifications.getLastNotification();

        assertThat(notice).isNotNull();

        assertThat(notice.getText())
                .isEqualTo(messages.getMessage(DocumentCards.class, "documentUnavailable"));
    }

    private static DashboardFragment dashboard() {
        return UiTestUtils.getComponent(TestMenus.mainView(), "dashboard");
    }

    // a scan under a name of its own, so that a row of another test with the same file name is never taken for it
    private Document upload() {
        return documentService.upload(sandbox, List.of(TestFiles.file(tempDir, UUID.randomUUID() + "-scan.txt",
                        "A document of the test")))
                .created()
                .getFirst();
    }
}
