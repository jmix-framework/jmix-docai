package com.company.docai.view.tour;

import com.company.docai.ai.AiSettings;
import com.company.docai.app.service.OperationRejectedException;
import com.company.docai.app.service.access.AccessChecker;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.document.UploadedFile;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.app.service.tour.TourDocuments;
import com.company.docai.demo.TourSample;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.view.assistant.AssistantNavigation;
import com.company.docai.view.document.DocumentDetailView;
import com.company.docai.view.document.DocumentUploads;
import com.company.docai.view.repository.DocumentListFragment;
import com.company.docai.view.repository.FolderTreeFragment;
import com.company.docai.view.repository.RepositoryView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.popover.PopoverPosition;
import com.vaadin.flow.server.VaadinSession;
import io.jmix.aitoolsflowui.view.chathub.AiChatHubView;
import io.jmix.flowui.OpenedDialogWindows;
import io.jmix.flowui.UiProperties;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.app.main.StandardMainView;
import io.jmix.flowui.component.UiComponentUtils;
import io.jmix.flowui.view.View;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Opens the screens of the chapters for Show and finds the targets of their hints.
 */
@org.springframework.stereotype.Component
public class TourScreens {

    private static final Logger log = LoggerFactory.getLogger(TourScreens.class);

    private static final String ASSISTANT_CHAT_ITEM = "assistantChat";
    private static final String SAMPLE_FILE_PREFIX = "docai-tour-";
    private static final String SAMPLE_FILE_SUFFIX = ".tmp";

    private final ViewNavigators viewNavigators;
    private final ObjectProvider<OpenedDialogWindows> openedDialogWindows;
    private final TourDocuments tourDocuments;
    private final AiSettings aiSettings;
    private final AssistantNavigation assistantNavigation;
    private final AccessChecker accessChecker;
    private final UiProperties uiProperties;
    private final DocumentService documentService;
    private final FolderService folderService;
    private final DocumentUploads documentUploads;
    private final TourSample tourSample;

    public TourScreens(ViewNavigators viewNavigators, ObjectProvider<OpenedDialogWindows> openedDialogWindows,
                       TourDocuments tourDocuments, AiSettings aiSettings, AssistantNavigation assistantNavigation,
                       AccessChecker accessChecker, UiProperties uiProperties, DocumentService documentService,
                       FolderService folderService, DocumentUploads documentUploads, TourSample tourSample) {
        this.viewNavigators = viewNavigators;
        this.openedDialogWindows = openedDialogWindows;
        this.tourDocuments = tourDocuments;
        this.aiSettings = aiSettings;
        this.assistantNavigation = assistantNavigation;
        this.accessChecker = accessChecker;
        this.uiProperties = uiProperties;
        this.documentService = documentService;
        this.folderService = folderService;
        this.documentUploads = documentUploads;
        this.tourSample = tourSample;
    }

    /**
     * Returns why the chapter is unavailable whatever its documents.
     */
    public Optional<TourReason> availability(TourChapter chapter) {
        if (chapter.isAiNeeded() && !aiSettings.isAvailable()) {
            return Optional.of(TourReason.AI_OFF);
        }

        if (chapter == TourChapter.AI_ASSISTANT && !assistantNavigation.isAvailable()) {
            return Optional.of(TourReason.NO_ASSISTANT);
        }

        return Optional.empty();
    }

    /**
     * Returns why Show cannot open the chapter now.
     */
    public Optional<TourReason> check(TourChapter chapter) {
        return availability(chapter).or(() -> switch (chapter) {
            case FILL_WITH_AI -> uploadedUntyped()
                    .or(tourDocuments::scan)
                    .isEmpty()
                    ? Optional.of(TourReason.MISSING_DOCUMENT)
                    : Optional.empty();
            case DOCUMENT_ASSISTANT -> tourDocuments.lease().isEmpty()
                    ? Optional.of(TourReason.MISSING_DOCUMENT)
                    : Optional.empty();
            case UPLOAD -> accessChecker.canEdit(tourDocuments.uploadFolder())
                    ? Optional.empty()
                    : Optional.of(TourReason.NO_FOLDER_ACCESS);
            case SEARCH_BY_MEANING, AI_ASSISTANT -> Optional.empty();
        });
    }

    /**
     * Opens the dashboard, as the logo does, unless it is open.
     */
    public void openHome() {
        View<?> current = UiComponentUtils.getCurrentView();

        // by the view's id: a main view that Studio's hot deploy loaded anew is not the class literal
        if (!(current instanceof StandardMainView)) {
            viewNavigators.view(current, uiProperties.getMainViewId())
                    .navigate();
        }
    }

    /**
     * Opens the screen of the chapter: the folder of its document with the card over it, or the assistant's menu.
     */
    public void open(StandardMainView mainView, TourChapter chapter) {
        switch (chapter) {
            case FILL_WITH_AI -> uploadedUntyped()
                    .or(tourDocuments::scan)
                    .ifPresent(document -> openCard(mainView, document));
            case DOCUMENT_ASSISTANT -> tourDocuments.lease()
                    .ifPresent(lease -> openCard(mainView, lease));
            case UPLOAD -> openRepository(mainView, tourDocuments.uploadFolder());
            case AI_ASSISTANT -> openMenu(mainView);
            case SEARCH_BY_MEANING -> {
            }
        }
    }

    /**
     * Returns the card of a document open over the screen, if it is the dialog on top.
     */
    public Optional<View<?>> card() {
        return openedDialogWindows.getObject()
                .getCurrentDialog()
                .filter(DocumentDetailView.class::isInstance);
    }

    /**
     * Returns whether the hub of the assistant is the screen.
     */
    public boolean isHubShown() {
        return UiComponentUtils.getCurrentView() instanceof AiChatHubView;
    }

    /**
     * Returns where the explanation of the chapter stands in the card.
     */
    public Optional<Target> explanation(TourChapter chapter) {
        return switch (chapter) {
            case FILL_WITH_AI -> inCard("documentTitle", PopoverPosition.BOTTOM_START,
                    isUploadedCard() ? "uploadedCard.text" : "scanCard.text");
            case DOCUMENT_ASSISTANT -> inCard("versionsTab", PopoverPosition.BOTTOM_START, "leaseCard.text");
            case SEARCH_BY_MEANING, AI_ASSISTANT, UPLOAD -> Optional.empty();
        };
    }

    /**
     * Returns where the hint of the action of the chapter stands, with its text.
     */
    public Optional<Target> action(StandardMainView mainView, TourChapter chapter) {
        return switch (chapter) {
            case FILL_WITH_AI -> inCard("fillWithAiButton", PopoverPosition.BOTTOM_END, "fillWithAi.text");
            case SEARCH_BY_MEANING -> searchTarget(mainView);
            case DOCUMENT_ASSISTANT -> inCard("assistantTab", PopoverPosition.BOTTOM, "leaseAssistant.text");
            case AI_ASSISTANT -> assistantTarget(mainView);
            case UPLOAD -> uploadTarget("documentList.documentsGrid", PopoverPosition.TOP)
                    .or(() -> uploadTarget("documentList.uploadButton", PopoverPosition.BOTTOM));
        };
    }

    /**
     * Returns the free name the sample invoice gets in the folder the repository shows.
     */
    public Optional<String> sampleName() {
        return documentList()
                .map(DocumentListFragment::getFolder)
                .map(folder -> documentService.freeName(folder, tourSample.fileName()));
    }

    /**
     * Lets the repository's table take the sample dragged from the hint and returns what ends that.
     */
    public Optional<Runnable> startSampleDrag() {
        Optional<DocumentListFragment> list = documentList();
        list.ifPresent(fragment -> fragment.setPageDragged(true));

        return list.map(fragment -> () -> fragment.setPageDragged(false));
    }

    /**
     * Uploads the sample invoice into the folder the repository shows; the callback comes right before the upload.
     *
     * @throws OperationRejectedException if the folder is unavailable or only viewed by the user
     */
    public void uploadSample(Runnable beforeUpload) {
        DocumentListFragment list = documentList()
                .orElse(null);

        Folder folder = list == null ? null : list.getFolder();

        if (folder == null) {
            return;
        }

        folderService.checkEditable(folder);

        String name = documentService.freeName(folder, tourSample.fileName());
        Optional<Path> file = makeSample();

        if (file.isEmpty()) {
            documentUploads.showFailed(name);

            return;
        }

        try {
            beforeUpload.run();
            list.upload(List.of(new UploadedFile(name, file.get())));
        } finally {
            delete(file.get());
        }
    }

    private Optional<Path> makeSample() {
        Path file = null;

        try {
            byte[] content = tourSample.render();
            file = Files.createTempFile(SAMPLE_FILE_PREFIX, SAMPLE_FILE_SUFFIX);

            return Optional.of(Files.write(file, content));
        } catch (IOException | RuntimeException e) {
            log.error("Failed to make the sample invoice of the tour", e);

            if (file != null) {
                delete(file);
            }

            return Optional.empty();
        }
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("The sample invoice of the tour is left in the temporary directory: {}", file, e);
        }
    }

    private Optional<DocumentListFragment> documentList() {
        return currentView()
                .flatMap(view -> UiComponentUtils.findComponent(view, "documentList"))
                .filter(DocumentListFragment.class::isInstance)
                .map(DocumentListFragment.class::cast);
    }

    private static Optional<View<?>> currentView() {
        UI ui = UI.getCurrent();

        if (ui == null || ui.getInternals().getActiveRouterTargetsChain().isEmpty()) {
            return Optional.empty();
        }

        // a navigation may end on a page that is no view
        return ui.getCurrentView() instanceof View<?> view
                ? Optional.of(view)
                : Optional.empty();
    }

    private Optional<Document> uploadedUntyped() {
        VaadinSession session = VaadinSession.getCurrent();

        return session == null
                ? Optional.empty()
                : Tour.state(session)
                        .getUploadedDocumentId()
                        .flatMap(tourDocuments::untyped);
    }

    private boolean isUploadedCard() {
        Optional<UUID> uploaded = uploadedUntyped()
                .map(Document::getId);

        Optional<UUID> shown = card()
                .map(DocumentDetailView.class::cast)
                .map(view -> view.getEditedEntity().getId());

        return uploaded.isPresent() && uploaded.equals(shown);
    }

    private void openCard(StandardMainView mainView, Document document) {
        openRepository(mainView, document.getFolder());

        if (UiComponentUtils.getCurrentView() instanceof RepositoryView repository) {
            repository.openCard(document);
        }
    }

    private void openRepository(StandardMainView mainView, Folder folder) {
        // the router would keep an open repository on its folder, the tree turns it to this one
        UiComponentUtils.findComponent(mainView, "folderTree")
                .filter(FolderTreeFragment.class::isInstance)
                .map(FolderTreeFragment.class::cast)
                .ifPresent(tree -> tree.loadAndSelect(folder.getId()));
    }

    private void openMenu(StandardMainView mainView) {
        // a closed menu over a narrow window keeps its items off the screen
        if (!isHubShown() && mainView.getContent().isOverlay()) {
            mainView.getContent().setDrawerOpened(true);
        }
    }

    private Optional<Target> searchTarget(StandardMainView mainView) {
        Optional<Component> wand = UiComponentUtils.findComponent(mainView, "searchModeButton")
                .filter(Component::isVisible)
                .filter(button -> "false".equals(button.getElement().getAttribute("aria-pressed")));

        return wand.map(button -> new Target(button, PopoverPosition.BOTTOM_END, "searchByMeaning.text"))
                .or(() -> UiComponentUtils.findComponent(mainView, "searchField")
                        .map(field -> new Target(field, PopoverPosition.BOTTOM, "searchByMeaning.fieldText")));
    }

    private Optional<Target> assistantTarget(StandardMainView mainView) {
        // a path looks among the direct children only, and the composer lies deep in the hub
        if (UiComponentUtils.getCurrentView() instanceof AiChatHubView hub) {
            return UiComponentUtils.findComponent(hub, "hubFragment.composerFragment")
                    .flatMap(composer -> UiComponentUtils.findComponent(composer, "messageField"))
                    .or(() -> UiComponentUtils.findComponent(hub, "hubFragment.composerCard"))
                    .map(field -> new Target(field, PopoverPosition.BOTTOM, "assistantQuestion.text"));
        }

        PopoverPosition position = mainView.getContent().isOverlay()
                ? PopoverPosition.BOTTOM_START
                : PopoverPosition.END;

        return UiComponentUtils.findComponent(mainView, "assistantMenu")
                .flatMap(menu -> descendant(menu, ASSISTANT_CHAT_ITEM))
                .map(item -> new Target(item, position, "assistantMenu.text"));
    }

    private Optional<Target> inCard(String id, PopoverPosition position, String textKey) {
        return card()
                .flatMap(card -> UiComponentUtils.findComponent(card, id))
                .filter(Component::isVisible)
                .map(component -> new Target(component, position, textKey));
    }

    private Optional<Target> uploadTarget(String path, PopoverPosition position) {
        return UiComponentUtils.findComponent(UiComponentUtils.getCurrentView(), path)
                .filter(Component::isVisible)
                .map(component -> new Target(component, position, "upload.text"));
    }

    private static Optional<Component> descendant(Component parent, String id) {
        // the list menu draws its items outside the lookup of the view
        return parent.getChildren()
                .map(child -> child.getId().filter(id::equals).isPresent()
                        ? Optional.of(child)
                        : descendant(child, id))
                .flatMap(Optional::stream)
                .findFirst();
    }

    /**
     * Where a hint stands and the key of its text.
     */
    public record Target(Component component, PopoverPosition position, String textKey) {
    }
}
