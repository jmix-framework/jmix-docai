package com.company.docai.stand;

import com.company.docai.DocAiApplication;
import com.company.docai.model.document.Document;
import com.company.docai.test_support.AnonymousUiTestAuthenticator;
import com.company.docai.test_support.StubAiConfiguration;
import com.company.docai.test_support.TestDrops;
import com.company.docai.view.document.DocumentDetailView;
import com.company.docai.view.login.LoginView;
import com.company.docai.view.repository.RepositoryView;
import com.vaadin.flow.component.upload.UploadDropZone;
import com.vaadin.flow.component.upload.UploadManager;
import com.vaadin.flow.component.upload.UploadManager.FileRejectionReason;
import io.jmix.core.DataManager;
import io.jmix.core.Messages;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.DialogWindows;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.upload.JmixUpload;
import io.jmix.flowui.fragment.FragmentUtils;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.view.DialogWindow;
import io.jmix.flowui.view.View;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MimeTypes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The upload components of the stand offer only the files a visitor can upload there: the version uploads of the
 * repository and of the document card and the drop zone of the repository, whose manager the upload dialog shares,
 * take the extensions of the types of {@code docai.stand.upload-types} and no media types of the browser; a file of
 * another type dropped on the zone gets the notice the stand gives a file it refuses by its bytes. The class shares
 * the context of {@link StandLoginTest}, and the start of the stand makes the templates ready once for the class.
 */
@UiTest(authenticator = AnonymousUiTestAuthenticator.class, initialView = LoginView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class, StubAiConfiguration.class},
        properties = "docai.stand.data-dir=build/test-stand-login")
@ActiveProfiles({"test", "stand"})
class StandUploadPickersTest {

    // the names of the formats of docai.stand.upload-types, as a visitor knows them
    private static final String FORMATS = "PDF, DOCX, XLSX, TXT, CSV, PNG, JPEG";

    @Autowired
    private CopyFactory copyFactory;

    @Autowired
    private CopyRegistry registry;

    @Autowired
    private StandProperties properties;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private ViewNavigators viewNavigators;

    @Autowired
    private DialogWindows dialogWindows;

    @Autowired
    private Messages messages;

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

    // each extension names one of the types by Tika, and each type has its extensions; the types a browser gives files
    // differ between systems, so no upload judges a file by them
    @Test
    void theUploadsOfferExactlyTheFilesOfTheTypesOfTheStand() {
        List<List<String>> extensions = new ArrayList<>();
        List<List<String>> mediaTypes = new ArrayList<>();

        asAdminInACopy(() -> {
            View<?> repository = openRepository();
            JmixUpload<?> versionUpload = fragmentComponent(repository, "versionUpload");
            UploadManager dropZone = dropZone(repository).getUploadManager();
            JmixUpload<?> cardUpload = UiTestUtils.getComponent(openCard(aDocument()), "newVersionUpload");

            extensions.addAll(List.of(versionUpload.getAcceptedFileExtensions(), dropZone.getAcceptedFileExtensions(),
                    cardUpload.getAcceptedFileExtensions()));

            mediaTypes.addAll(List.of(versionUpload.getAcceptedMimeTypes(), dropZone.getAcceptedMimeTypes(),
                    cardUpload.getAcceptedMimeTypes()));
        });

        List<String> offered = extensions.getFirst();

        assertThat(offered).contains(".pdf", ".docx", ".xlsx", ".txt", ".csv", ".png", ".jpg", ".jpeg");
        assertThat(offered).extracting(StandUploadPickersTest::typeOfAFileEndingWith)
                .isSubsetOf(properties.uploadTypes());
        assertThat(extensions).allSatisfy(list -> assertThat(list).isEqualTo(offered));
        assertThat(mediaTypes).allSatisfy(list -> assertThat(list).isEmpty());
    }

    // the browser refuses the file by the extensions of the zone, before it reaches the stand
    @Test
    void aDroppedFileOfAnotherTypeGetsTheNoticeOfTheStand() {
        AtomicReference<String> notice = new AtomicReference<>();
        AtomicReference<String> refusal = new AtomicReference<>();

        asAdminInACopy(() -> {
            TestDrops.reject(dropZone(openRepository()), "Logo.svg", FileRejectionReason.INCORRECT_FILE_TYPE);

            notice.set(UiTestUtils.getLastOpenedNotification()
                    .getText());

            refusal.set(messages.formatMessage(StandUploadGuard.class, "uploadTypeNotAllowed", "Logo.svg",
                    FORMATS));
        });

        assertThat(notice.get()).isEqualTo(refusal.get());
    }

    private void asAdminInACopy(Runnable work) {
        StandScope copy = StandScope.copy(copyFactory.create("en")
                .key());

        StandScopeHolder.run(copy, () -> systemAuthenticator.runWithUser("admin", work));
    }

    private View<?> openRepository() {
        viewNavigators.view(UiTestUtils.getCurrentView(), RepositoryView.class)
                .navigate();

        return UiTestUtils.getCurrentView();
    }

    private Document aDocument() {
        return dataManager.load(Document.class)
                .all()
                .maxResults(1)
                .one();
    }

    private DocumentDetailView openCard(Document document) {
        DialogWindow<DocumentDetailView> window = dialogWindows.detail(UiTestUtils.getCurrentView(), Document.class)
                .withViewClass(DocumentDetailView.class)
                .editEntity(document)
                .build();

        window.open();

        return window.getView();
    }

    // the upload dialog and the table hand their files to the manager of this zone
    private UploadDropZone dropZone(View<?> view) {
        DataGrid<Document> grid = fragmentComponent(view, "documentsGrid");

        return (UploadDropZone) grid.getParent()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private <T> T fragmentComponent(View<?> view, String componentId) {
        return (T) FragmentUtils.getComponent(UiTestUtils.getComponent(view, "documentList"), componentId);
    }

    // the type Tika gives a file by its name alone
    private static String typeOfAFileEndingWith(String extension) {
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, "file" + extension);

        try {
            return MimeTypes.getDefaultMimeTypes()
                    .detect(null, metadata)
                    .toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
