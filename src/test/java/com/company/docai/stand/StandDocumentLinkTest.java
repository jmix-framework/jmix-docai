package com.company.docai.stand;

import com.company.docai.DocAiApplication;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.document.Document;
import com.company.docai.test_support.AnonymousUiTestAuthenticator;
import com.company.docai.test_support.StubAiConfiguration;
import com.company.docai.test_support.TestFiles;
import com.company.docai.view.document.DocumentDetailView;
import com.company.docai.view.login.LoginView;
import com.company.docai.view.repository.RepositoryView;
import com.vaadin.flow.component.UI;
import io.jmix.core.DataManager;
import io.jmix.core.Messages;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.testassist.notification.NotificationInfo;
import io.jmix.flowui.view.View;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A link to a card on the stand opens the document of the visitor's own copy. The class shares the context of
 * {@link StandLoginTest}, and the start of the stand makes the templates ready once for the class.
 */
@UiTest(authenticator = AnonymousUiTestAuthenticator.class, initialView = LoginView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class, StubAiConfiguration.class},
        properties = "docai.stand.data-dir=build/test-stand-login")
@ActiveProfiles({"test", "stand"})
class StandDocumentLinkTest {

    @Autowired
    private CopyFactory copyFactory;

    @Autowired
    private CopyRegistry registry;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private Messages messages;

    @TempDir
    private Path tempDir;

    @BeforeAll
    static void startTheStand(@Autowired StandStartup startup) throws Exception {
        startup.start()
                .get(3, TimeUnit.MINUTES);
    }

    @AfterEach
    void deleteTheCopies() {
        registry.all()
                .forEach(copy -> copyFactory.delete(copy.key()));
    }

    // the copies share the ids of their template, so a link to a document of the template opens it in any copy
    @Test
    void aLinkToADocumentOfTheTemplateOpensItInTheVisitorsCopy() throws Exception {
        UUID shared = StandScopeHolder.call(newCopy(), () -> systemAuthenticator.withUser("admin", () ->
                dataManager.load(Document.class)
                        .all()
                        .maxResults(1)
                        .one()
                        .getId()));

        StandScopeHolder.run(newCopy(), () -> systemAuthenticator.runWithUser("admin", () -> {
            UI.getCurrent().navigate("documents/" + shared);

            DocumentDetailView card = UiTestUtils.getCurrentView();
            assertThat(card.getEditedEntity().getId()).isEqualTo(shared);
        }));
    }

    @Test
    void aLinkToADocumentAnotherVisitorUploadedLeadsToTheRepositoryWithANotice() throws Exception {
        UUID foreign = StandScopeHolder.call(newCopy(), () -> systemAuthenticator.withUser("admin", () ->
                documentService.upload(folderService.getRoot(),
                                List.of(TestFiles.file(tempDir, "note-" + UUID.randomUUID() + ".txt", "A note")))
                        .created()
                        .getFirst()
                        .getId()));

        StandScopeHolder.run(newCopy(), () -> systemAuthenticator.runWithUser("admin", () -> {
            UI.getCurrent().navigate("documents/" + foreign);

            NotificationInfo notice = UiTestUtils.getLastOpenedNotification();
            assertThat(UiTestUtils.<View<?>>getCurrentView()).isInstanceOf(RepositoryView.class);
            assertThat(notice).isNotNull();
            assertThat(notice.getText())
                    .isEqualTo(messages.getMessage(DocumentDetailView.class, "documentUnavailable"));
        }));
    }

    private StandScope newCopy() {
        return StandScope.copy(copyFactory.create("en")
                .key());
    }
}
