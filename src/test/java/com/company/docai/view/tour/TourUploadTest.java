package com.company.docai.view.tour;

import com.company.docai.DocAiApplication;
import com.company.docai.ai.AiSettings;
import com.company.docai.app.service.OperationRejectedException;
import com.company.docai.app.service.access.AccessChecker;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.app.service.tour.TourDocuments;
import com.company.docai.app.ui.component.tour.TourPanel;
import com.company.docai.demo.TourSample;
import com.company.docai.model.access.AccessLevel;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.security.role.EmployeeRole;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.StubAiConfiguration;
import com.company.docai.test_support.TestAccess;
import com.company.docai.test_support.TestAccess.Grant;
import com.company.docai.test_support.TestFiles;
import com.company.docai.test_support.TestMenus;
import com.company.docai.test_support.TestTours;
import com.company.docai.view.assistant.AssistantNavigation;
import com.company.docai.view.document.DocumentDetailView;
import com.company.docai.view.document.DocumentUploads;
import com.company.docai.view.main.MainView;
import com.company.docai.view.notfound.PageNotFoundError;
import com.company.docai.view.repository.DocumentListFragment;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.dnd.DropEvent;
import com.vaadin.flow.component.dnd.DropTarget;
import com.vaadin.flow.component.popover.Popover;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.NavigationTrigger;
import com.vaadin.flow.server.ErrorHandler;
import com.vaadin.flow.server.HttpStatusCode;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.Id;
import io.jmix.core.Messages;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.dynattr.DynAttrQueryHints;
import io.jmix.dynattr.model.Category;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.OpenedDialogWindows;
import io.jmix.flowui.UiProperties;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.UiComponentUtils;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.data.grid.DataGridItems;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static com.company.docai.test_support.TestTours.message;
import static com.company.docai.test_support.TestTours.startTour;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The first chapter with the sample invoice and the second on the document it uploads, with the AI on and a stub
 * model; no demo set is loaded, so the tests make their own scan.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class,
        StubAiConfiguration.class})
@TestPropertySource(properties = "spring.ai.openai.api-key=test-key")
class TourUploadTest {

    private static final String ADMIN = "admin";
    private static final String SAMPLE_FILE_PREFIX = "docai-tour-";

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private ViewNavigators viewNavigators;

    @Autowired
    private Tour tour;

    @Autowired
    private TourSample tourSample;

    @Autowired
    private Messages messages;

    @Autowired
    private OpenedNotifications openedNotifications;

    @Autowired
    private UserSettingsCache userSettingsCache;

    @Autowired
    private ObjectProvider<OpenedDialogWindows> openedDialogWindows;

    @Autowired
    private TourDocuments tourDocuments;

    @Autowired
    private AiSettings aiSettings;

    @Autowired
    private AssistantNavigation assistantNavigation;

    @Autowired
    private AccessChecker accessChecker;

    @Autowired
    private UiProperties uiProperties;

    @Autowired
    private DocumentUploads documentUploads;

    @TempDir
    private Path tempDir;

    private Folder sandbox;
    private Folder viewed;
    private Document scan;
    private List<Path> leftovers;

    @BeforeEach
    void setUp() {
        systemAuthenticator.runWithUser(ADMIN, () -> {
            sandbox = folder(folderService.getRoot(), "tour-upload-test-" + UUID.randomUUID());

            viewed = TestAccess.giveOwnAccess(dataManager, folder(sandbox, "Viewed"),
                    Grant.role(EmployeeRole.CODE, AccessLevel.VIEW));

            scan = upload("scan-0457.txt");
        });

        leftovers = sampleFiles();
    }

    @AfterEach
    void deleteSandbox() {
        systemAuthenticator.runWithUser(ADMIN, () -> dataManager.load(Id.of(sandbox))
                .optional()
                .ifPresent(folderService::delete));

        // leaving the view saves the column settings of the document list for the user
        userSettingsCache.delete("RepositoryView.documentList");
    }

    @Test
    void theHintAtTheTableOffersTheSampleAsACardAndAButton() {
        Popover hint = openUploadHint();
        Component card = TestTours.sample(hint);
        String name = tourSample.fileName();

        assertThat(hint.getTarget()).isSameAs(table());
        assertThat(TestTours.text(hint, "tour-popover-content")).contains(message(messages, "upload.text"));
        assertThat(card.getElement().getTextRecursively()).isEqualTo(name);
        assertThat(card.getElement().getAttribute("aria-label"))
                .isEqualTo(messages.formatMessage(TourGuide.class, "sample.ariaLabel", name));
        assertThat(card.getElement().getProperty("draggable")).isEqualTo("true");
        assertThat(card.getElement().hasAttribute("tabindex")).isFalse();
        assertThat(TestTours.buttonTexts(hint, "tour-popover-actions")).containsExactly(
                message(messages, "uploadSampleButton"), message(messages, "skipTaskButton"));
    }

    @Test
    void uploadItPutsTheSampleIntoTheOpenFolderAndDoesTheChapter() {
        TourPanel panel = startTour(messages);
        Popover hint = openUploadHint(panel);
        openedNotifications.closeOpenedNotifications();

        TestTours.button(hint, message(messages, "uploadSampleButton")).click();

        Document uploaded = documentNamed(tourSample.fileName());

        assertThat(uploaded.getCurrentRevision().getContentType()).isEqualTo("application/pdf");
        assertThat(shownInTheTable()).contains(uploaded);
        assertThat(UiTestUtils.getOpenedNotifications())
                .extracting(NotificationInfo::getText, NotificationInfo::getType)
                .containsExactly(tuple(messages.formatMessage(DocumentUploads.class, "uploadDone", 1),
                        Notifications.Type.SUCCESS));
        assertThat(TestTours.state().isDone(TourChapter.UPLOAD)).isTrue();
        assertThat(TestTours.state().getUploadedDocumentId()).contains(uploaded.getId());
        assertThat(hint.isOpened()).isFalse();
        assertThat(panel.isExpanded()).isTrue();
        assertThat(newSampleFiles()).isEmpty();
    }

    @Test
    void aSecondPassPutsTheSampleUnderTheNextFreeNameAndRemembersIt() {
        String name = tourSample.fileName();
        TestTours.button(openUploadHint(), message(messages, "uploadSampleButton")).click();

        tour.restart();
        Popover hint = openUploadHint();
        String second = documentService.freeName(sandbox, name);

        assertThat(second).isNotEqualTo(name);
        assertThat(TestTours.sample(hint).getElement().getTextRecursively()).isEqualTo(second);

        TestTours.button(hint, message(messages, "uploadSampleButton")).click();

        assertThat(TestTours.state().getUploadedDocumentId()).contains(documentNamed(second).getId());
        assertThat(documentNamed(name)).isNotNull();
    }

    @Test
    void aNameTakenWhileTheHintIsOpenGivesTheNextFreeOne() {
        String name = tourSample.fileName();
        Popover hint = openUploadHint();

        assertThat(TestTours.sample(hint).getElement().getTextRecursively()).isEqualTo(name);

        // as another tab of the visitor or another person does meanwhile
        systemAuthenticator.runWithUser(ADMIN, () -> upload(name));
        String second = documentService.freeName(sandbox, name);

        TestTours.button(hint, message(messages, "uploadSampleButton")).click();

        assertThat(second).isNotEqualTo(name);
        assertThat(TestTours.state().getUploadedDocumentId()).contains(documentNamed(second).getId());
    }

    @Test
    void theTableTakesTheSampleOnlyWhileItIsDragged() {
        Popover hint = openUploadHint();
        Component card = TestTours.sample(hint);
        DropTarget<Component> zone = DropTarget.configure(dropZone());

        assertThat(zone.isActive()).isFalse();

        TestTours.startDrag(card);

        assertThat(zone.isActive()).isTrue();
        assertThat(hint.isOpened()).isTrue();

        TestTours.endDrag(card);

        assertThat(zone.isActive()).isFalse();
        assertThat(hint.isOpened()).isTrue();
        assertThat(TestTours.state().isDone(TourChapter.UPLOAD)).isFalse();
    }

    @Test
    void theSampleDroppedOnTheTableUploadsItAsTheButtonDoes() {
        TourPanel panel = startTour(messages);
        Popover hint = openUploadHint(panel);
        Component card = TestTours.sample(hint);
        openedNotifications.closeOpenedNotifications();

        TestTours.startDrag(card);
        TestTours.drop(dropZone());
        TestTours.endDrag(card);

        Document uploaded = documentNamed(tourSample.fileName());

        assertThat(shownInTheTable()).contains(uploaded);
        assertThat(UiTestUtils.getOpenedNotifications())
                .extracting(NotificationInfo::getType)
                .containsExactly(Notifications.Type.SUCCESS);
        assertThat(TestTours.state().getUploadedDocumentId()).contains(uploaded.getId());
        assertThat(hint.isOpened()).isFalse();
        assertThat(DropTarget.configure(dropZone()).isActive()).isFalse();
        assertThat(newSampleFiles()).isEmpty();
    }

    @Test
    void aDropAfterTheHintIsGoneUploadsNothing() {
        Popover hint = openUploadHint();
        Component card = TestTours.sample(hint);

        TestTours.startDrag(card);

        // Esc or a click outside, as the browser reports them
        hint.getElement().setProperty("opened", false);
        ComponentUtil.fireEvent(hint, new Popover.OpenedChangeEvent(hint, true));

        assertThat(DropTarget.configure(dropZone()).isActive()).isFalse();

        // a drop already on its way when the hint closed
        tour.dropped(card);

        assertThat(documentsOf(sandbox)).containsExactly(scan);
    }

    @Test
    void aDropOnTheTableAfterTheDragEndedUploadsNothing() {
        Popover hint = openUploadHint();
        Component card = TestTours.sample(hint);
        Component zone = dropZone();

        TestTours.startDrag(card);
        TestTours.endDrag(card);
        TestTours.drop(zone);

        assertThat(ComponentUtil.hasEventListener(zone, DropEvent.class)).isFalse();
        assertThat(documentsOf(sandbox)).containsExactly(scan);
        assertThat(TestTours.state().isDone(TourChapter.UPLOAD)).isFalse();
        assertThat(hint.isOpened()).isTrue();
    }

    @Test
    void aNavigationToAPageThatIsNoViewClosesTheHintOfTheUploadWithoutAnError() {
        Popover hint = openUploadHint();
        UI ui = UI.getCurrent();
        ErrorHandler errorHandler = ui.getSession().getErrorHandler();
        List<Throwable> errors = new ArrayList<>();
        int status;

        // Flow hands a failure of a detach listener to the error handler of the session, which shows it to the person
        ui.getSession().setErrorHandler(error -> errors.add(error.getThrowable()));

        try {
            // as a link inside the page does: UI.navigate leaves an address that no route takes to the browser
            status = ui.getInternals()
                    .getRouter()
                    .navigate(ui, new Location("no-such-page"), NavigationTrigger.ROUTER_LINK);
        } finally {
            ui.getSession().setErrorHandler(errorHandler);
        }

        assertThat(errors).isEmpty();
        assertThat(status).isEqualTo(HttpStatusCode.NOT_FOUND.getCode());
        assertThat(ui.getCurrentView()).isInstanceOf(PageNotFoundError.class);
        assertThat(hint.isOpened()).isFalse();
        assertThat(Tour.guide()).isEmpty();
    }

    @Test
    void aFolderTheVisitorOnlyViewsTakesNoSample() {
        Popover hint = openUploadHint();

        // the tree is loaded anew, as it was built before the test made the folder
        TestMenus.mainView()
                .getFolderTree()
                .loadAndSelect(viewed.getId());

        TestTours.startDrag(TestTours.sample(hint));

        assertThat(shownFolder()).isEqualTo(viewed);
        assertThat(hint.isOpened()).isTrue();
        assertThat(DropTarget.configure(dropZone()).isActive()).isFalse();
    }

    @Test
    void uploadItInAFolderTheVisitorOnlyViewsIsRefusedAndKeepsTheHint() {
        Popover hint = openUploadHint();

        TestMenus.mainView()
                .getFolderTree()
                .loadAndSelect(viewed.getId());

        assertThatThrownBy(() -> TestTours.button(hint, message(messages, "uploadSampleButton")).click())
                .isInstanceOf(OperationRejectedException.class)
                .hasMessage(messages.formatMessage(FolderService.class, "folderReadOnly", viewed.getName()));
        assertThat(hint.isOpened()).isTrue();
        assertThat(documentsOf(viewed)).isEmpty();
        assertThat(newSampleFiles()).isEmpty();
    }

    @Test
    void aSampleThatCannotBeMadeTellsOfAFailedUploadAndStartsNone() {
        TourSample broken = mock(TourSample.class);
        when(broken.fileName()).thenReturn("Invoice 2231.pdf");
        when(broken.render()).thenThrow(new IllegalStateException("A line wider than the page"));

        TourScreens screens = new TourScreens(viewNavigators, openedDialogWindows, tourDocuments, aiSettings,
                assistantNavigation, accessChecker, uiProperties, documentService, folderService, documentUploads,
                broken);

        openUploadHint();
        openedNotifications.closeOpenedNotifications();
        Runnable beforeUpload = mock(Runnable.class);

        screens.uploadSample(beforeUpload);

        assertThat(UiTestUtils.getOpenedNotifications())
                .extracting(NotificationInfo::getText, NotificationInfo::getType)
                .containsExactly(tuple(messages.formatMessage(DocumentUploads.class, "uploadFailed",
                        "Invoice 2231.pdf"), Notifications.Type.ERROR));
        verifyNoInteractions(beforeUpload);
        assertThat(documentsOf(sandbox)).containsExactly(scan);
        assertThat(TestTours.openHint().isOpened()).isTrue();
        assertThat(newSampleFiles()).isEmpty();
    }

    @Test
    void afterTheUploadTheSecondChapterOpensTheUploadedDocumentAndExplainsItAsTheVisitorsOwn() {
        TourPanel panel = startTour(messages);
        TestTours.button(openUploadHint(panel), message(messages, "uploadSampleButton")).click();
        Document uploaded = documentNamed(tourSample.fileName());

        TestTours.showButton(panel, 2).click();

        DocumentDetailView card = UiTestUtils.getLastOpenedViewDialog();
        Popover explanation = TestTours.openHint();

        assertThat(card).isNotNull();
        assertThat(card.getEditedEntity()).isEqualTo(uploaded);
        assertThat(explanation.getTarget()).isSameAs(UiTestUtils.getComponent(card, "documentTitle"));
        assertThat(TestTours.text(explanation, "tour-popover-content"))
                .contains(message(messages, "uploadedCard.text"));

        TestTours.button(explanation, message(messages, "nextButton")).click();

        assertThat(TestTours.openHint().getTarget()).isSameAs(UiTestUtils.getComponent(card, "fillWithAiButton"));
    }

    @Test
    void anUploadedDocumentThatIsDeletedSendsTheSecondChapterBackToTheScan() {
        TourPanel panel = startTour(messages);
        TestTours.button(openUploadHint(panel), message(messages, "uploadSampleButton")).click();
        Document uploaded = documentNamed(tourSample.fileName());
        systemAuthenticator.runWithUser(ADMIN, () -> documentService.delete(List.of(uploaded)));

        TestTours.showButton(panel, 2).click();

        DocumentDetailView card = UiTestUtils.getLastOpenedViewDialog();

        assertThat(card).isNotNull();
        assertThat(card.getEditedEntity()).isEqualTo(scan);
        assertThat(TestTours.text(TestTours.openHint(), "tour-popover-content"))
                .contains(message(messages, "scanCard.text"));
    }

    @Test
    void anUploadedDocumentThatGotATypeSendsTheSecondChapterBackToTheScan() {
        TourPanel panel = startTour(messages);
        TestTours.button(openUploadHint(panel), message(messages, "uploadSampleButton")).click();
        systemAuthenticator.runWithUser(ADMIN, () -> asInvoice(documentNamed(tourSample.fileName())));

        TestTours.showButton(panel, 2).click();

        DocumentDetailView card = UiTestUtils.getLastOpenedViewDialog();

        assertThat(card).isNotNull();
        assertThat(card.getEditedEntity()).isEqualTo(scan);
        assertThat(TestTours.text(TestTours.openHint(), "tour-popover-content"))
                .contains(message(messages, "scanCard.text"));
    }

    @Test
    void takeTheTourAgainForgetsTheUploadedDocument() {
        TourPanel panel = startTour(messages);
        TestTours.button(openUploadHint(panel), message(messages, "uploadSampleButton")).click();

        tour.restart();

        assertThat(TestTours.state().getUploadedDocumentId()).isEmpty();

        TestTours.button(panel, message(messages, "startButton")).click();
        TestTours.button(panel, message(messages, "nextButton")).click();
        TestTours.showButton(panel, 2).click();

        // the document is the latest one without a type now, and the tour shows it as any scan
        assertThat(TestTours.text(TestTours.openHint(), "tour-popover-content"))
                .contains(message(messages, "scanCard.text"));
    }

    @Test
    void aSecondChapterFoundWithoutADocumentGetsTheOneTheFirstChapterUploads() {
        systemAuthenticator.runWithUser(ADMIN, () -> documentService.delete(List.of(scan)));
        TourPanel panel = startTour(messages);

        TestTours.showButton(panel, 2).click();

        assertThat(TestTours.rowStates(panel).get(1)).isEqualTo("unavailable");

        Popover hint = openUploadHint(panel);

        // without a scan the first chapter opens the root folder, so the sample goes into the sandbox instead
        TestMenus.mainView()
                .getFolderTree()
                .loadAndSelect(sandbox.getId());

        TestTours.button(hint, message(messages, "uploadSampleButton")).click();

        assertThat(TestTours.rowStates(panel).get(1)).isEqualTo("next");
        assertThat(TestTours.text(panel, "tour-line")).contains(messages.formatMessage(TourGuide.class,
                "taskDone.next", message(messages, TourChapter.FILL_WITH_AI.getTitleKey())));
    }

    private Popover openUploadHint() {
        return openUploadHint(startTour(messages));
    }

    private Popover openUploadHint(TourPanel panel) {
        TestTours.showButton(panel, 1).click();
        TestTours.button(panel, message(messages, "nextButton")).click();

        return TestTours.openHint();
    }

    private Folder shownFolder() {
        return UiComponentUtils.findComponent(UiTestUtils.getCurrentView(), "documentList")
                .map(DocumentListFragment.class::cast)
                .map(DocumentListFragment::getFolder)
                .orElseThrow();
    }

    private Component table() {
        return UiComponentUtils.findComponent(UiTestUtils.getCurrentView(), "documentList.documentsGrid")
                .orElseThrow();
    }

    private Component dropZone() {
        return table().getParent()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private List<Document> shownInTheTable() {
        DataGridItems<Document> items = ((DataGrid<Document>) table()).getItems();
        assertThat(items).isNotNull();

        return items.getItems()
                .stream()
                .toList();
    }

    private Document documentNamed(String name) {
        return dataManager.load(Document.class)
                .query("select d from Document d where d.folder = :folder and d.name = :name")
                .parameter("folder", sandbox)
                .parameter("name", name)
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("currentRevision", FetchPlan.BASE))
                .one();
    }

    private void asInvoice(Document document) {
        Document edited = dataManager.load(Document.class)
                .id(document.getId())
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("folder", FetchPlan.BASE)
                        .add("category", FetchPlan.INSTANCE_NAME))
                .hint(DynAttrQueryHints.LOAD_DYN_ATTR, true)
                .one();

        edited.setCategory(dataManager.load(Category.class)
                .query("select c from dynat_Category c where c.name = 'Invoice' and c.entityType = 'Document'")
                .one());

        documentService.save(edited, null, null);
    }

    private List<Document> documentsOf(Folder folder) {
        return dataManager.load(Document.class)
                .query("select d from Document d where d.folder = :folder order by d.name")
                .parameter("folder", folder)
                .list();
    }

    private Document upload(String fileName) {
        return documentService.upload(sandbox, List.of(TestFiles.file(tempDir, fileName, "Invoice 1187")))
                .created()
                .getFirst();
    }

    private Folder folder(Folder parent, String name) {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(parent);
        folder.setName(name);

        return folderService.save(folder);
    }

    private List<Path> newSampleFiles() {
        return sampleFiles().stream()
                .filter(file -> !leftovers.contains(file))
                .toList();
    }

    private static List<Path> sampleFiles() {
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files
                    .filter(file -> file.getFileName().toString().startsWith(SAMPLE_FILE_PREFIX))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
