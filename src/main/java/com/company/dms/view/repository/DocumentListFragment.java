package com.company.dms.view.repository;

import com.company.dms.ai.AiSettings;
import com.company.dms.app.config.FilesProperties;
import com.company.dms.app.service.access.AccessLevels;
import com.company.dms.app.service.access.AccessService;
import com.company.dms.app.service.document.DocumentService;
import com.company.dms.app.service.document.UploadReport;
import com.company.dms.app.service.document.UploadedFile;
import com.company.dms.model.access.AccessDraft;
import com.company.dms.model.access.AccessLevel;
import com.company.dms.model.document.Document;
import com.company.dms.model.document.DocumentRevision;
import com.company.dms.model.document.DocumentStatus;
import com.company.dms.model.folder.Folder;
import com.company.dms.view.access.AccessDetailView;
import com.company.dms.view.document.DocumentDetailView;
import com.company.dms.view.document.FileSizeFormatter;
import com.company.dms.view.document.PreviewType;
import com.company.dms.view.document.RevisionDownloader;
import com.company.dms.view.folder.FolderLookupView;
import com.company.dms.view.folder.FolderRenderers;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.ItemClickEvent;
import com.vaadin.flow.component.grid.ItemDoubleClickEvent;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.upload.AllFinishedEvent;
import com.vaadin.flow.component.upload.FailedEvent;
import com.vaadin.flow.component.upload.FileRejectedEvent;
import com.vaadin.flow.component.upload.StartedEvent;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.data.renderer.Renderer;
import com.vaadin.flow.data.renderer.TextRenderer;
import com.vaadin.flow.data.selection.MultiSelectionEvent;
import com.vaadin.flow.data.selection.SelectionEvent;
import io.jmix.core.AccessManager;
import io.jmix.core.Messages;
import io.jmix.core.MetadataTools;
import io.jmix.flowui.DialogWindows;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.Fragments;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.accesscontext.UiShowViewContext;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.grid.DataGridColumn;
import io.jmix.flowui.component.upload.JmixUpload;
import io.jmix.flowui.component.upload.UploadSucceededEvent;
import io.jmix.flowui.facet.FragmentSettingsFacet;
import io.jmix.flowui.facet.SettingsFacet;
import io.jmix.flowui.facet.settings.FragmentSettings;
import io.jmix.flowui.fragment.Fragment;
import io.jmix.flowui.fragment.FragmentDescriptor;
import io.jmix.flowui.fragment.FragmentUtils;
import io.jmix.flowui.icon.Icons;
import io.jmix.flowui.kit.action.Action;
import io.jmix.flowui.kit.action.ActionPerformedEvent;
import io.jmix.flowui.kit.action.ActionVariant;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.kit.component.dropdownbutton.DropdownButton;
import io.jmix.flowui.kit.icon.JmixFontIcon;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.upload.TemporaryStorage;
import io.jmix.flowui.view.*;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The documents of the selected folder with the document actions and the upload. The actions follow
 * the user's level in the folder and in the selected documents.
 */
@FragmentDescriptor("document-list-fragment.xml")
public class DocumentListFragment extends Fragment<VerticalLayout> {

    private static final String ASSISTANT_CHAT_VIEW = "aitls_AiChatView";

    @ViewComponent
    private JmixUpload<TemporaryStorage.FileInfo> documentsUpload;
    @ViewComponent
    private JmixUpload<TemporaryStorage.FileInfo> versionUpload;

    @ViewComponent
    private JmixButton createDocumentButton;
    @ViewComponent
    private DropdownButton statusButton;
    @ViewComponent
    private JmixButton previewButton;

    @ViewComponent
    private DataGrid<Document> documentsGrid;
    @ViewComponent
    private CollectionLoader<Document> documentsDl;
    @ViewComponent("documentsGrid.accessAction")
    private Action accessAction;
    @ViewComponent("documentsGrid.discussAction")
    private Action discussAction;

    @ViewComponent
    private MessageBundle messageBundle;
    @ViewComponent
    private FragmentSettingsFacet settings;

    @Autowired
    private DocumentService documentService;
    @Autowired
    private AccessService accessService;
    @Autowired
    private TemporaryStorage temporaryStorage;
    @Autowired
    private FilesProperties filesProperties;
    @Autowired
    private FileSizeFormatter fileSizeFormatter;
    @Autowired
    private DialogWindows dialogWindows;
    @Autowired
    private Dialogs dialogs;
    @Autowired
    private Notifications notifications;
    @Autowired
    private RevisionDownloader revisionDownloader;
    @Autowired
    private UiComponents uiComponents;
    @Autowired
    private Icons icons;
    @Autowired
    private FolderRenderers folderRenderers;
    @Autowired
    private Messages messages;
    @Autowired
    private MetadataTools metadataTools;
    @Autowired
    private Fragments fragments;
    @Autowired
    private AiSettings aiSettings;
    @Autowired
    private AccessManager accessManager;

    @Nullable
    private Folder folder;
    @Nullable
    private Document focusedDocument;
    @Nullable
    private Folder uploadFolder;
    @Nullable
    private Document versionDocument;
    @Nullable
    private DocumentDiscussionFragment discussion;

    private AccessLevels levels = new AccessLevels(Map.of(), false);

    private final Map<UUID, UploadedFile> uploadedFiles = new LinkedHashMap<>();
    private Consumer<@Nullable Document> focusHandler = document -> {
    };
    private Runnable previewToggleHandler = () -> {
    };
    private Runnable contentsChangeHandler = () -> {
    };

    // Jmix takes a saved width other than 100px for one the user dragged and stops the column from growing,
    // so the name takes its share of the free space again unless the user has changed its width
    @Install(to = "settings", subject = "applySettingsDelegate")
    private void settingsApplySettingsDelegate(final SettingsFacet.SettingsContext<FragmentSettings> context) {
        DataGridColumn<Document> nameColumn = documentsGrid.getColumnByKey("name");
        String declaredWidth = nameColumn.getWidth();
        settings.applySettings();

        if (Objects.equals(declaredWidth, nameColumn.getWidth())) {
            nameColumn.setFlexGrow(1);
        }
    }

    @Subscribe
    public void onReady(final ReadyEvent event) {
        configureUploads();
        configureDocumentsGridContextMenu();
        configureDiscussion();
    }

    private void configureUploads() {
        int maxFileSize = (int) filesProperties.maxFileSize().toBytes();
        documentsUpload.setMaxFileSize(maxFileSize);
        versionUpload.setMaxFileSize(maxFileSize);

        // the upload creates its button itself, so the descriptor cannot give the button a variant or a name
        ((Button) documentsUpload.getUploadButton()).addThemeVariants(ButtonVariant.PRIMARY);

        String newVersion = messageBundle.getMessage("versionUpload.uploadText");
        Button versionButton = (Button) versionUpload.getUploadButton();
        versionButton.setAriaLabel(newVersion);
        versionButton.getElement().setAttribute("title", newVersion);

        // a page reload or a session timeout removes the list before the rest of the batch arrives
        addDetachListener(detachEvent -> discardUploadedFiles());
    }

    private void configureDocumentsGridContextMenu() {
        // a right click outside the selection selects the row under the pointer, so the menu acts on that row
        documentsGrid.getContextMenu().addGridContextMenuOpenedListener(menu -> {
            if (menu.isOpened()) {
                menu.getItem()
                        .filter(document -> !documentsGrid.getSelectedItems().contains(document))
                        .ifPresent(this::selectOnly);
            }
        });
    }

    // the discussion keeps its conversation in the chat of the assistant, so it needs the AI and that chat
    private void configureDiscussion() {
        UiShowViewContext assistantChat = new UiShowViewContext(ASSISTANT_CHAT_VIEW);
        accessManager.applyRegisteredConstraints(assistantChat);
        boolean available = aiSettings.isAvailable() && assistantChat.isPermitted();
        discussAction.setVisible(available);
        // the rows are dragged only into the window, so they are draggable only where it opens
        documentsGrid.setRowsDraggable(available);

        // a dialog belongs to the UI and would stay open over the next view
        addDetachListener(detachEvent -> {
            if (discussion != null) {
                discussion.close();
            }
        });
    }

    /**
     * Receives the document whose preview to show, or null when nothing is selected.
     */
    public void setFocusHandler(Consumer<@Nullable Document> focusHandler) {
        this.focusHandler = focusHandler;
    }

    /**
     * Receives a change of the documents in the folders: an upload, a new document, a move or a delete.
     */
    public void setContentsChangeHandler(Runnable contentsChangeHandler) {
        this.contentsChangeHandler = contentsChangeHandler;
    }

    /**
     * Receives a click on the preview button, which turns the preview mode on or off.
     */
    public void setPreviewToggleHandler(Runnable previewToggleHandler) {
        this.previewToggleHandler = previewToggleHandler;
    }

    /**
     * Shows the preview button pressed while the preview mode is on.
     */
    public void setPreviewPressed(boolean pressed) {
        if (pressed) {
            previewButton.addThemeVariants(ButtonVariant.PRIMARY);
        } else {
            previewButton.removeThemeVariants(ButtonVariant.PRIMARY);
        }

        previewButton.getElement().setAttribute("aria-pressed", String.valueOf(pressed));
    }

    /**
     * Shows the documents of the folder.
     */
    public void setFolder(Folder folder) {
        this.folder = folder;
        reload();
    }

    @Nullable
    public Document getFocusedDocument() {
        return focusedDocument;
    }

    /**
     * Reloads the documents. The grid keeps the selection, and the focus stays on the same document.
     */
    public void reload() {
        UUID focusedId = focusedDocument == null ? null : focusedDocument.getId();
        levels = accessService.levels();

        if (folder != null) {
            documentsDl.setParameter("folder", folder);
            documentsDl.load();
        }

        // a folder the user only views, or only passes through, takes no new documents
        boolean folderEditable = folder != null && levels.allows(folder.getAccessSet(), AccessLevel.EDIT);
        documentsUpload.setEnabled(folderEditable);
        createDocumentButton.setEnabled(folderEditable);

        Set<Document> selected = documentsGrid.getSelectedItems();
        focus(selected.stream()
                .filter(document -> document.getId().equals(focusedId))
                .findFirst()
                .orElse(selected.stream().findFirst().orElse(null)));

        updateVersionUpload();
        updateStatusButton();
        updateAccessAction();
    }

    // a click selects only its row, and a click with Ctrl or Cmd adds or removes the row, as in a file manager
    @Subscribe("documentsGrid")
    public void onDocumentsGridItemClick(final ItemClickEvent<Document> event) {
        Document document = event.getItem();

        if (event.isCtrlKey() || event.isMetaKey()) {
            if (documentsGrid.getSelectedItems().contains(document)) {
                documentsGrid.deselect(document);
            } else {
                documentsGrid.select(document);
            }

            return;
        }

        selectOnly(document);
    }

    // the document that has just joined the selection is the one to preview
    @Subscribe("documentsGrid")
    public void onDocumentsGridSelection(final SelectionEvent<DataGrid<Document>, Document> event) {
        Set<Document> selected = documentsGrid.getSelectedItems();

        if (event instanceof MultiSelectionEvent<DataGrid<Document>, Document> change
                && change.getAddedSelection().size() == 1) {
            focus(change.getAddedSelection().iterator().next());
        } else if (!selected.contains(focusedDocument)) {
            focus(selected.stream().findFirst().orElse(null));
        }

        updateVersionUpload();
        updateStatusButton();
    }

    @Subscribe("documentsGrid")
    public void onDocumentsGridItemDoubleClick(final ItemDoubleClickEvent<Document> event) {
        openCard(event.getItem());
    }

    @Subscribe("documentsGrid.openAction")
    public void onOpenAction(final ActionPerformedEvent event) {
        openCard(documentsGrid.getSingleSelectedItem());
    }

    // an open window takes the selected documents into its selection, as it takes the rows dropped on it
    @Subscribe("documentsGrid.discussAction")
    public void onDiscussAction(final ActionPerformedEvent event) {
        if (discussion == null || !discussion.isOpened()) {
            discussion = fragments.create(this, DocumentDiscussionFragment.class);
            discussion.setCardOpener(this::openCard);
            discussion.open();
        }

        discussion.add(documentsGrid.getSelectedItems());
    }

    @Subscribe("previewButton")
    public void onPreviewButtonClick(final ClickEvent<JmixButton> event) {
        previewToggleHandler.run();
    }

    @Subscribe("createDocumentButton")
    public void onCreateDocumentButtonClick(final ClickEvent<JmixButton> event) {
        dialogWindows.detail(hostView(), Document.class)
                .withViewClass(DocumentDetailView.class)
                .newEntity()
                .withInitializer(document -> document.setFolder(folder))
                .withAfterCloseListener(closeEvent -> {
                    if (closeEvent.closedWith(StandardOutcome.SAVE)) {
                        contentsChanged();
                    }
                })
                .open();
    }

    @Subscribe("documentsGrid.downloadAction")
    public void onDownloadAction(final ActionPerformedEvent event) {
        Document document = documentsGrid.getSingleSelectedItem();
        revisionDownloader.download(document.getCurrentRevision());
    }

    @Subscribe("documentsGrid.moveAction")
    public void onMoveAction(final ActionPerformedEvent event) {
        Set<Document> documents = documentsGrid.getSelectedItems();
        dialogWindows.lookup(hostView(), Folder.class)
                .withViewClass(FolderLookupView.class)
                .withSelectHandler(targets -> move(documents, targets.iterator().next()))
                .open();
    }

    @Subscribe("documentsGrid.accessAction")
    public void onAccessAction(final ActionPerformedEvent event) {
        dialogWindows.detail(hostView(), AccessDraft.class)
                .withViewClass(AccessDetailView.class)
                .editEntity(accessService.describe(documentsGrid.getSingleSelectedItem()))
                .withAfterCloseListener(closeEvent -> {
                    if (closeEvent.closedWith(StandardOutcome.SAVE)) {
                        contentsChanged();
                    }
                })
                .open();
    }

    @Subscribe("documentsGrid.deleteAction")
    public void onDeleteAction(final ActionPerformedEvent event) {
        Set<Document> documents = documentsGrid.getSelectedItems();
        String names = documents.stream()
                .map(Document::getName)
                .collect(Collectors.joining(", "));

        dialogs.createOptionDialog()
                .withHeader(messageBundle.getMessage("deleteDocumentsDialog.header"))
                .withText(messageBundle.formatMessage("deleteDocumentsDialog.text", names))
                .withActions(
                        new DialogAction(DialogAction.Type.YES).withHandler(actionEvent -> {
                            documentService.delete(documents);
                            contentsChanged();
                        }),
                        new DialogAction(DialogAction.Type.NO).withVariant(ActionVariant.PRIMARY))
                .open();
    }

    @Subscribe("documentsGrid.activateAction")
    public void onActivateAction(final ActionPerformedEvent event) {
        changeStatus(DocumentStatus.ACTIVE);
    }

    @Subscribe("documentsGrid.archiveAction")
    public void onArchiveAction(final ActionPerformedEvent event) {
        changeStatus(DocumentStatus.ARCHIVED);
    }

    @Subscribe("documentsGrid.unarchiveAction")
    public void onUnarchiveAction(final ActionPerformedEvent event) {
        changeStatus(DocumentStatus.ACTIVE);
    }

    @Install(to = "documentsGrid.openAction", subject = "enabledRule")
    private boolean openActionEnabledRule() {
        return documentsGrid.getSelectedItems().size() == 1;
    }

    @Install(to = "documentsGrid.downloadAction", subject = "enabledRule")
    private boolean downloadActionEnabledRule() {
        return documentsGrid.getSelectedItems().size() == 1;
    }

    @Install(to = "documentsGrid.moveAction", subject = "enabledRule")
    private boolean moveActionEnabledRule() {
        return allSelectedEditable();
    }

    @Install(to = "documentsGrid.activateAction", subject = "enabledRule")
    private boolean activateActionEnabledRule() {
        return allSelectedHave(DocumentStatus.DRAFT) && allSelectedEditable();
    }

    @Install(to = "documentsGrid.archiveAction", subject = "enabledRule")
    private boolean archiveActionEnabledRule() {
        return allSelectedHave(DocumentStatus.ACTIVE) && allSelectedEditable();
    }

    @Install(to = "documentsGrid.unarchiveAction", subject = "enabledRule")
    private boolean unarchiveActionEnabledRule() {
        return allSelectedHave(DocumentStatus.ARCHIVED) && allSelectedEditable();
    }

    @Install(to = "documentsGrid.accessAction", subject = "enabledRule")
    private boolean accessActionEnabledRule() {
        return documentsGrid.getSelectedItems().size() == 1
                && levels.allows(documentsGrid.getSingleSelectedItem().getAccessSet(), AccessLevel.MANAGE);
    }

    @Install(to = "documentsGrid.deleteAction", subject = "enabledRule")
    private boolean deleteActionEnabledRule() {
        return allSelectedEditable();
    }

    @Supply(to = "documentsGrid.name", subject = "renderer")
    private Renderer<Document> nameRenderer() {
        return new ComponentRenderer<>(document -> {
            Span name = uiComponents.create(Span.class);
            name.addClassName("name-with-icon");
            name.add(icons.get(fileIcon(document)), new Text(document.getName()));

            if (showsOwnAccess(document)) {
                folderRenderers.markOwnAccess(name);
            }

            return name;
        });
    }

    @Supply(to = "documentsGrid.status", subject = "renderer")
    private Renderer<Document> statusRenderer() {
        return new ComponentRenderer<>(document -> {
            Badge badge = uiComponents.create(Badge.class);
            badge.setText(messages.getMessage(document.getStatus()));
            badge.addClassName(DocumentDetailView.statusColor(document.getStatus()));
            return badge;
        });
    }

    @Supply(to = "documentsGrid.fileSize", subject = "renderer")
    private Renderer<Document> fileSizeRenderer() {
        return new TextRenderer<>(document -> document.getCurrentRevision() == null
                ? ""
                : fileSizeFormatter.format(document.getCurrentRevision().getFileSize()));
    }

    // ---- upload of new documents and of a new version

    // the files go into the folder shown when the upload began, even if another folder is opened meanwhile
    @Subscribe("documentsUpload")
    public void onDocumentsUploadStarted(final StartedEvent event) {
        if (uploadFolder == null) {
            uploadFolder = folder;
        }
    }

    @Subscribe("documentsUpload")
    public void onDocumentsUploadSucceeded(final UploadSucceededEvent<TemporaryStorage.FileInfo> event) {
        uploadedFiles.put(event.getData().getId(),
                new UploadedFile(event.getFileName(), event.getData().getFile().toPath()));
    }

    @Subscribe("documentsUpload")
    public void onDocumentsUploadAllFinished(final AllFinishedEvent event) {
        Folder target = uploadFolder;
        uploadFolder = null;

        if (target == null || uploadedFiles.isEmpty()) {
            discardUploadedFiles();
            return;
        }

        try {
            showUploadReport(documentService.upload(target, new ArrayList<>(uploadedFiles.values())));
        } finally {
            discardUploadedFiles();
            contentsChanged();
        }
    }

    // the version goes to the document selected when the upload began, even if the selection changes meanwhile
    @Subscribe("versionUpload")
    public void onVersionUploadStarted(final StartedEvent event) {
        versionDocument = documentsGrid.getSingleSelectedItem();
    }

    @Subscribe("versionUpload")
    public void onVersionUploadSucceeded(final UploadSucceededEvent<TemporaryStorage.FileInfo> event) {
        Document document = versionDocument;
        versionDocument = null;

        try {
            if (document != null) {
                documentService.uploadRevision(document,
                        new UploadedFile(event.getFileName(), event.getData().getFile().toPath()));
            }
        } finally {
            temporaryStorage.deleteFile(event.getData().getId());
            versionUpload.clearFileList();
            reload();
        }
    }

    @Subscribe("documentsUpload")
    public void onDocumentsUploadFileRejected(final FileRejectedEvent event) {
        notifications.create(event.getFileName() + ": " + event.getErrorMessage())
                .withType(Notifications.Type.WARNING)
                .show();
    }

    @Subscribe("versionUpload")
    public void onVersionUploadFileRejected(final FileRejectedEvent event) {
        onDocumentsUploadFileRejected(event);
    }

    @Subscribe("documentsUpload")
    public void onDocumentsUploadFailed(final FailedEvent event) {
        notifications.create(messageBundle.formatMessage("uploadFailed", event.getFileName()))
                .withType(Notifications.Type.ERROR)
                .show();
    }

    @Subscribe("versionUpload")
    public void onVersionUploadFailed(final FailedEvent event) {
        versionDocument = null;
        onDocumentsUploadFailed(event);
    }

    private void discardUploadedFiles() {
        uploadedFiles.keySet().forEach(temporaryStorage::deleteFile);
        uploadedFiles.clear();
        documentsUpload.clearFileList();
    }

    private void showUploadReport(UploadReport report) {
        if (report.skipped().isEmpty()) {
            notifications.create(messageBundle.formatMessage("uploadDone", report.created().size()))
                    .withType(Notifications.Type.SUCCESS)
                    .show();

            return;
        }

        VerticalLayout content = uiComponents.create(VerticalLayout.class);
        content.setPadding(false);
        content.add(textLine(messageBundle.formatMessage("uploadReport.summary",
                report.created().size(), report.skipped().size())));

        for (UploadReport.SkippedFile skipped : report.skipped()) {
            content.add(textLine(skipped.fileName() + " — " + skipped.reason()));
        }

        dialogs.createMessageDialog()
                .withHeader(messageBundle.getMessage("uploadReport.header"))
                .withContent(content)
                .open();
    }

    private Span textLine(String text) {
        Span span = uiComponents.create(Span.class);
        span.setText(text);
        return span;
    }

    private void openCard(@Nullable Document document) {
        if (document == null) {
            return;
        }

        dialogWindows.detail(hostView(), Document.class)
                .withViewClass(DocumentDetailView.class)
                .editEntity(document)
                .withAfterCloseListener(closeEvent -> {
                    if (closeEvent.closedWith(StandardOutcome.SAVE)) {
                        reload();
                    }
                })
                .open();
    }

    private void selectOnly(Document document) {
        Set<Document> others = new HashSet<>(documentsGrid.getSelectedItems());
        others.remove(document);
        documentsGrid.asMultiSelect().updateSelection(Set.of(document), others);
    }

    private void focus(@Nullable Document document) {
        focusedDocument = document;
        focusHandler.accept(document);
    }

    private void changeStatus(DocumentStatus target) {
        documentService.changeStatus(documentsGrid.getSelectedItems(), target);
        reload();
    }

    private void contentsChanged() {
        reload();
        contentsChangeHandler.run();
    }

    private boolean allSelectedHave(DocumentStatus status) {
        Set<Document> selected = documentsGrid.getSelectedItems();
        return !selected.isEmpty() && selected.stream().allMatch(document -> document.getStatus() == status);
    }

    private boolean allSelectedEditable() {
        Set<Document> selected = documentsGrid.getSelectedItems();
        return !selected.isEmpty() && selected.stream()
                .allMatch(document -> levels.allows(document.getAccessSet(), AccessLevel.EDIT));
    }

    private void updateVersionUpload() {
        Document document = documentsGrid.getSelectedItems().size() == 1 ? documentsGrid.getSingleSelectedItem() : null;
        versionUpload.setEnabled(document != null
                && document.getStatus() != DocumentStatus.ARCHIVED
                && levels.allows(document.getAccessSet(), AccessLevel.EDIT));
    }

    // the key tells a manager that the document does not inherit the access of its folder
    private boolean showsOwnAccess(Document document) {
        return folder != null
                && !document.getAccessSet().equals(folder.getAccessSet())
                && levels.allows(document.getAccessSet(), AccessLevel.MANAGE);
    }

    // the key is only for those who manage anything, and stays put as the folder and the selection change
    private void updateAccessAction() {
        accessAction.setVisible(accessService.managesAnything());
    }

    private void move(Set<Document> documents, Folder target) {
        Runnable move = () -> {
            documentService.move(documents, target);
            contentsChanged();
        };

        if (!accessService.changesOnMove(documents, target)) {
            move.run();
            return;
        }

        dialogs.createOptionDialog()
                .withHeader(messageBundle.getMessage("moveChangesAccessDialog.header"))
                .withText(messageBundle.formatMessage("moveDocumentsChangeAccessDialog.text",
                        metadataTools.getInstanceName(target)))
                .withActions(
                        new DialogAction(DialogAction.Type.YES).withVariant(ActionVariant.PRIMARY)
                                .withHandler(actionEvent -> move.run()),
                        new DialogAction(DialogAction.Type.NO))
                .open();
    }

    // with no transition that fits every selected document, the menu would hold only disabled items
    private void updateStatusButton() {
        statusButton.setEnabled(activateActionEnabledRule()
                || archiveActionEnabledRule()
                || unarchiveActionEnabledRule());
    }

    private static JmixFontIcon fileIcon(Document document) {
        DocumentRevision revision = document.getCurrentRevision();
        return switch (PreviewType.of(revision == null ? null : revision.getContentType())) {
            case IMAGE -> JmixFontIcon.FILE_PICTURE;
            case PDF, TEXT -> JmixFontIcon.FILE_TEXT;
            case NONE -> JmixFontIcon.FILE;
        };
    }

    private View<?> hostView() {
        return FragmentUtils.getHostView(this);
    }
}
