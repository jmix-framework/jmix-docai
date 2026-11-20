package com.company.docai.view.tour;

import com.company.docai.DocAiApplication;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.app.ui.component.searchpalette.SearchPaletteOpener;
import com.company.docai.app.ui.component.tour.TourPanel;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.StubAiConfiguration;
import com.company.docai.test_support.TestFiles;
import com.company.docai.test_support.TestMenus;
import com.company.docai.test_support.TestTours;
import com.company.docai.view.document.DocumentDetailView;
import com.company.docai.view.main.MainView;
import com.company.docai.view.repository.RepositoryView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.internal.UIInternals.JavaScriptInvocation;
import com.vaadin.flow.component.popover.Popover;
import com.vaadin.flow.component.popover.PopoverPosition;
import com.vaadin.flow.router.QueryParameters;
import io.jmix.aitoolsflowui.view.chathub.AiChatHubView;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.Id;
import io.jmix.core.Messages;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.dynattr.DynAttrQueryHints;
import io.jmix.dynattr.model.Category;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.UiComponentUtils;
import io.jmix.flowui.fragment.FragmentUtils;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.view.View;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static com.company.docai.test_support.TestTours.message;
import static com.company.docai.test_support.TestTours.startTour;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Show of the tour's chapters with the AI on and a stub model; no demo set is loaded, so the tests make their own
 * scan and lease.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class,
        StubAiConfiguration.class})
@TestPropertySource(properties = "spring.ai.openai.api-key=test-key")
class TourShowTest {

    private static final String ADMIN = "admin";

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
    private TourScreens tourScreens;

    @Autowired
    private Messages messages;

    @TempDir
    private Path tempDir;

    private Folder sandbox;
    private Document scan;
    private Document lease;

    @BeforeEach
    void setUp() {
        systemAuthenticator.runWithUser(ADMIN, () -> {
            Folder folder = dataManager.create(Folder.class);
            folder.setParent(folderService.getRoot());
            folder.setName("tour-show-test-" + UUID.randomUUID());
            sandbox = folderService.save(folder);

            lease = typedLease(upload("lease-112.txt", "Lease agreement 112"));
            scan = upload("scan-0457.txt", "Invoice 1187 of 1 November 2026 from Harbour Logistics LLC");
        });
    }

    @AfterEach
    void deleteSandbox() {
        systemAuthenticator.runWithUser(ADMIN, () -> dataManager.load(Id.of(sandbox))
                .optional()
                .ifPresent(folderService::delete));
    }

    @Test
    void withoutAnUploadTheSecondChapterOpensTheScanInItsFolderAndExplainsTheCardInsideIt() {
        TourPanel panel = startTour(messages);

        TestTours.showButton(panel, 2).click();

        DocumentDetailView card = UiTestUtils.getLastOpenedViewDialog();
        Popover explanation = TestTours.openHint();

        assertThat(UiTestUtils.<View<?>>getCurrentView()).isInstanceOf(RepositoryView.class);
        assertThat(card).isNotNull();
        assertThat(card.getEditedEntity()).isEqualTo(scan);
        assertThat(explanation.getTarget()).isSameAs(UiTestUtils.getComponent(card, "documentTitle"));
        assertThat(explanation.findAncestor(Dialog.class)).isNotNull();
        assertThat(TestTours.text(explanation, "tour-popover-content")).contains(message(messages, "scanCard.text"));

        TestTours.button(explanation, message(messages, "nextButton")).click();

        assertThat(TestTours.openHint().getTarget()).isSameAs(UiTestUtils.getComponent(card, "fillWithAiButton"));
        assertThat(panel.getElement().getAttribute("mood")).isEqualTo("pointing");
    }

    @Test
    void theCardJoiningThePageAtTheEndOfTheRequestGetsItsExplanation() {
        TourPanel panel = startTour(messages);

        // in the browser a window opened by Show joins the page only before the response
        TestTours.inOneRequest(() -> TestTours.showButton(panel, 2).click());

        DocumentDetailView card = UiTestUtils.getLastOpenedViewDialog();

        assertThat(TestTours.openHint().getTarget()).isSameAs(UiTestUtils.getComponent(card, "documentTitle"));
    }

    @Test
    void aRepositoryOpenOnAnotherFolderTurnsToTheFolderOfTheScan() {
        TourPanel panel = startTour(messages);

        viewNavigators.view(TestMenus.mainView(), RepositoryView.class)
                .withQueryParameters(QueryParameters.of(RepositoryView.FOLDER_PARAMETER, Folder.ROOT_ID.toString()))
                .navigate();

        TestTours.showButton(panel, 2).click();

        assertThat(TestMenus.mainView().getFolderTree().getSelectedFolder()).isEqualTo(sandbox);
    }

    @Test
    void aCardClosedUnderItsHintTakesTheHintAwayForGood() {
        TourPanel panel = startTour(messages);
        TestTours.showButton(panel, 2).click();
        DocumentDetailView card = UiTestUtils.getLastOpenedViewDialog();
        Popover explanation = TestTours.openHint();
        assertThat(card).isNotNull();

        card.closeWithDefaultAction();

        assertThat(explanation.isOpened()).isFalse();
        assertThat(explanation.getTarget()).isNull();
        assertThat(panel.getElement().getAttribute("mood")).isNotEqualTo("pointing");
    }

    @Test
    void aHintInTheCardWaitsInTheBrowserForTheCardToOpen() {
        TourPanel panel = startTour(messages);

        TestTours.showButton(panel, 2).click();

        DocumentDetailView card = UiTestUtils.getLastOpenedViewDialog();
        Popover hint = TestTours.openHint();

        assertThat(card).isNotNull();
        assertThat(TestTours.scripts(panel))
                .filteredOn(script -> script.getExpression().contains(".revealHint("))
                .flatExtracting(JavaScriptInvocation::getParameters)
                .containsExactly(panel.getElement(), hint.getElement(), card.findAncestor(Dialog.class).getElement());
    }

    @Test
    void theHintAtTheTableWaitsForNoWindow() {
        TourPanel panel = startTour(messages);
        TestTours.showButton(panel, 1).click();

        TestTours.button(panel, message(messages, "nextButton")).click();

        assertThat(TestTours.openHint().isOpened()).isTrue();
        assertThat(TestTours.scripts(panel))
                .noneMatch(script -> script.getExpression().contains(".revealHint("));
    }

    @Test
    void theFourthChapterFindsTheLeaseByItsFieldsAndExplainsItsVersions() {
        TourPanel panel = startTour(messages);

        TestTours.showButton(panel, 4).click();

        DocumentDetailView card = UiTestUtils.getLastOpenedViewDialog();

        assertThat(card).isNotNull();
        assertThat(card.getEditedEntity()).isEqualTo(lease);
        assertThat(TestTours.openHint().getTarget()).isSameAs(UiTestUtils.getComponent(card, "versionsTab"));

        TestTours.button(TestTours.openHint(), message(messages, "nextButton")).click();

        assertThat(TestTours.openHint().getTarget()).isSameAs(UiTestUtils.getComponent(card, "assistantTab"));
    }

    @Test
    void theFirstChapterExplainsTheFolderOfTheScanAndPointsAtItsTable() {
        TourPanel panel = startTour(messages);

        TestTours.showButton(panel, 1).click();

        RepositoryView repository = UiTestUtils.getCurrentView();

        assertThat(UI.getCurrent().getActiveViewLocation().getQueryParameters()
                .getSingleParameter(RepositoryView.FOLDER_PARAMETER)).contains(sandbox.getId().toString());
        assertThat(TestTours.text(panel, "tour-bubble-text")).isEqualTo(message(messages, "repository.text"));

        TestTours.button(panel, message(messages, "nextButton")).click();

        assertThat(TestTours.openHint().getTarget())
                .isSameAs(UiComponentUtils.findComponent(repository, "documentList.documentsGrid").orElseThrow());
    }

    @Test
    void aDeletedLeaseMakesTheFourthChapterGreyWithANoteOfDoc() {
        systemAuthenticator.runWithUser(ADMIN, () -> documentService.delete(List.of(lease)));
        TourPanel panel = startTour(messages);

        TestTours.showButton(panel, 4).click();

        assertThat(panel.isExpanded()).isTrue();
        assertThat(TestTours.text(panel, "tour-line")).isEqualTo(message(messages, "unavailable.document"));
        assertThat(TestTours.rowStates(panel).get(3)).isEqualTo("unavailable");
        assertThat(TestTours.text(TestTours.withClass(panel, "tour-row").get(3), "tour-row-reason"))
                .isEqualTo(message(messages, "reason.missing"));
        assertThat(UiTestUtils.getOpenedViewDialogs()).isEmpty();
    }

    @Test
    void theChapterOfTheAssistantLeadsFromTheMenuToTheFieldOfTheHub() {
        TourPanel panel = startTour(messages);

        TestTours.showButton(panel, 5).click();

        assertThat(TestTours.openHint().getTarget().getId()).contains("assistantChat");

        viewNavigators.view(UiTestUtils.getCurrentView(), AiChatHubView.class)
                .navigate();

        // inside a fragment the id of a component is the fragment's data, not the id of its element
        assertThat(FragmentUtils.getComponentId(TestTours.openHint().getTarget())).contains("messageField");
    }

    @Test
    void theHintAtTheItemOfTheMenuStandsBelowItWhenTheMenuLiesOverTheScreen() {
        TourPanel panel = startTour(messages);

        // a narrow window: the menu covers the screen
        TestMenus.mainView()
                .getContent()
                .getElement()
                .setProperty("overlay", true);

        TestTours.showButton(panel, 5).click();

        assertThat(TestTours.openHint().getPosition()).isEqualTo(PopoverPosition.BOTTOM_START);
    }

    @Test
    void aHintClosedInTheBrowserOffersToShowItAgain() {
        TourPanel panel = startTour(messages);
        TestTours.showButton(panel, 3).click();
        Popover hint = TestTours.openHint();
        Component target = hint.getTarget();

        // Esc or a click outside, as the browser reports them
        hint.getElement().setProperty("opened", false);
        ComponentUtil.fireEvent(hint, new Popover.OpenedChangeEvent(hint, true));

        assertThat(TestTours.text(panel, "tour-bubble-text")).isEqualTo(message(messages, "showAgain.text"));

        TestTours.button(panel, message(messages, "showAgainButton")).click();

        assertThat(TestTours.openHint().getTarget()).isSameAs(target);
    }

    @Test
    void theTargetsOfTheChaptersOnTheirScreensAreFoundAndShown() {
        MainView mainView = TestMenus.mainView();
        Component wand = UiTestUtils.getComponent(mainView, "searchModeButton");

        String search = "false".equals(wand.getElement().getAttribute("aria-pressed"))
                ? "searchModeButton"
                : "searchField";

        assertThat(tourScreens.action(mainView, TourChapter.SEARCH_BY_MEANING))
                .hasValueSatisfying(target -> assertThat(target.component().getId()).contains(search));
        assertThat(tourScreens.action(mainView, TourChapter.AI_ASSISTANT))
                .hasValueSatisfying(target -> assertThat(target.component().getId()).contains("assistantChat"));

        viewNavigators.view(UiTestUtils.getCurrentView(), AiChatHubView.class)
                .navigate();

        assertThat(tourScreens.action(mainView, TourChapter.AI_ASSISTANT))
                .hasValueSatisfying(target -> {
                    assertThat(FragmentUtils.getComponentId(target.component())).contains("messageField");
                    assertThat(target.component().isVisible()).isTrue();
                });
    }

    @Test
    void aFocusOnItsTargetKeepsTheHintAndAClickOnItClosesIt() {
        TourPanel panel = startTour(messages);
        TestTours.showButton(panel, 4).click();
        TestTours.button(TestTours.openHint(), message(messages, "nextButton")).click();
        Popover hint = TestTours.openHint();
        Component tab = hint.getTarget();

        // Tab from the target on the way into the hint
        TestTours.browserEvent(tab, "focusin");

        assertThat(hint.isOpened()).isTrue();

        TestTours.browserEvent(tab, "click");

        assertThat(hint.isOpened()).isFalse();
        assertThat(panel.getElement().getAttribute("mood")).isEqualTo("listening");
    }

    @Test
    void thePaletteOpeningOverTheHintOfTheSearchClosesIt() {
        TourPanel panel = startTour(messages);
        TestTours.showButton(panel, 3).click();
        Popover hint = TestTours.openHint();
        SearchPaletteOpener opener = UiTestUtils.getComponent(TestMenus.mainView(), "searchPaletteOpener");

        // the field hands its clicks and keys to the palette
        ComponentUtil.fireEvent(opener, new SearchPaletteOpener.OpenEvent(opener, true, "", 0));

        assertThat(hint.isOpened()).isFalse();
        assertThat(panel.getElement().getAttribute("mood")).isEqualTo("listening");
    }

    @Test
    void theHubFocusingItsFieldKeepsTheHintAndTheFirstInputClosesIt() {
        TourPanel panel = startTour(messages);
        TestTours.showButton(panel, 5).click();

        viewNavigators.view(UiTestUtils.getCurrentView(), AiChatHubView.class)
                .navigate();

        Popover hint = TestTours.openHint();
        Component field = hint.getTarget();

        // the hub puts the caret into its field by itself as it opens
        TestTours.browserEvent(field, "focusin");

        assertThat(hint.isOpened()).isTrue();

        TestTours.browserEvent(field, "input");

        assertThat(hint.isOpened()).isFalse();
        assertThat(panel.getElement().getAttribute("mood")).isEqualTo("listening");
    }

    @Test
    void theChapterOfTheAssistantSkippedAtTheMenuLeavesTheHubWithoutAHint() {
        TourPanel panel = startTour(messages);
        TestTours.showButton(panel, 5).click();

        TestTours.button(TestTours.openHint(), message(messages, "skipTaskButton")).click();

        viewNavigators.view(UiTestUtils.getCurrentView(), AiChatHubView.class)
                .navigate();

        assertThatIllegalStateException().isThrownBy(TestTours::openHint);
    }

    @Test
    void aTourEndedInAnotherTabLeavesTheHubWithoutAHint() {
        TourPanel panel = startTour(messages);
        TestTours.showButton(panel, 5).click();

        // End tour in another tab of the same session
        TestTours.state().end();

        viewNavigators.view(UiTestUtils.getCurrentView(), AiChatHubView.class)
                .navigate();

        assertThatIllegalStateException().isThrownBy(TestTours::openHint);
    }

    @Test
    void uploadAtTheHintOfTheTableLetsDocWaitWithoutOfferingTheHintAgain() {
        TourPanel panel = startTour(messages);
        TestTours.showButton(panel, 1).click();
        TestTours.button(panel, message(messages, "nextButton")).click();
        Popover hint = TestTours.openHint();

        // the button lies outside the hint, so the browser closes the hint before the click reaches the server
        hint.getElement().setProperty("opened", false);
        ComponentUtil.fireEvent(hint, new Popover.OpenedChangeEvent(hint, true));

        UiComponentUtils.findComponent(UiTestUtils.getCurrentView(), "documentList.uploadButton")
                .map(JmixButton.class::cast)
                .orElseThrow()
                .click();

        assertThat(TestTours.withClass(panel, "tour-bubble").getFirst().isVisible()).isFalse();
        assertThat(panel.getElement().getAttribute("mood")).isEqualTo("listening");
    }

    @Test
    void anotherFolderOpenedInTheRepositoryKeepsTheHintAtItsTable() {
        TourPanel panel = startTour(messages);
        TestTours.showButton(panel, 1).click();
        TestTours.button(panel, message(messages, "nextButton")).click();
        RepositoryView repository = UiTestUtils.getCurrentView();
        Component table = TestTours.openHint().getTarget();

        // as the main view opens a folder of its tree or its favorites: the open repository stays, with another folder
        viewNavigators.view(TestMenus.mainView(), RepositoryView.class)
                .withQueryParameters(QueryParameters.of(RepositoryView.FOLDER_PARAMETER, Folder.ROOT_ID.toString()))
                .navigate();

        assertThat(UiTestUtils.<View<?>>getCurrentView()).isSameAs(repository);
        assertThat(TestTours.openHint().getTarget()).isSameAs(table);
    }

    private Document upload(String fileName, String text) {
        return documentService.upload(sandbox, List.of(TestFiles.file(tempDir, fileName, text)))
                .created()
                .getFirst();
    }

    private Document typedLease(Document document) {
        Document edited = dataManager.load(Document.class)
                .id(document.getId())
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("folder", FetchPlan.BASE)
                        .add("category", FetchPlan.INSTANCE_NAME))
                .hint(DynAttrQueryHints.LOAD_DYN_ATTR, true)
                .one();

        edited.setCategory(dataManager.load(Category.class)
                .query("select c from dynat_Category c where c.name = 'Contract' and c.entityType = 'Document'")
                .one());

        EntityValues.setValue(edited, "+contractNumber", "112");
        // an enumeration value, which the tour's search finds as a string
        EntityValues.setValue(edited, "+contractKind", "lease");

        return documentService.save(edited, null, null);
    }
}
