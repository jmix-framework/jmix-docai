package com.company.docai.view.repository;

import com.company.docai.ai.AiSettings;
import com.company.docai.app.config.FilesProperties;
import com.company.docai.app.service.access.AccessLevels;
import com.company.docai.app.service.access.AccessService;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.document.DocumentStatusService;
import com.company.docai.app.service.document.UploadReport;
import com.company.docai.app.service.document.UploadedFile;
import com.company.docai.app.service.favorite.FavoriteService;
import com.company.docai.model.access.AccessDraft;
import com.company.docai.model.access.AccessLevel;
import com.company.docai.model.document.Document;
import com.company.docai.model.document.DocumentRevision;
import com.company.docai.model.document.DocumentStatus;
import com.company.docai.model.folder.Folder;
import com.company.docai.model.user.User;
import com.company.docai.view.access.AccessDetailView;
import com.company.docai.view.document.DocumentDetailView;
import com.company.docai.view.document.FileSizeFormatter;
import com.company.docai.view.document.PreviewType;
import com.company.docai.view.document.RevisionDownloader;
import com.company.docai.view.favorite.FavoriteListAction;
import com.company.docai.view.folder.FolderLookupView;
import com.company.docai.view.folder.FolderRenderers;
import com.company.docai.view.status.StatusBadges;
import com.company.docai.view.tag.TagBadges;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.ColumnResizeEvent;
import com.vaadin.flow.component.grid.ItemClickEvent;
import com.vaadin.flow.component.grid.ItemDoubleClickEvent;
import com.vaadin.flow.component.grid.contextmenu.GridMenuItem;
import com.vaadin.flow.component.grid.dnd.GridDragEndEvent;
import com.vaadin.flow.component.grid.dnd.GridDragStartEvent;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.upload.AllFinishedEvent;
import com.vaadin.flow.component.upload.FailedEvent;
import com.vaadin.flow.component.upload.FileRejectedEvent;
import com.vaadin.flow.component.upload.StartedEvent;
import com.vaadin.flow.component.upload.UploadManager;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.data.renderer.LitRenderer;
import com.vaadin.flow.data.renderer.Renderer;
import com.vaadin.flow.data.renderer.TextRenderer;
import com.vaadin.flow.data.selection.MultiSelectionEvent;
import com.vaadin.flow.data.selection.SelectionEvent;
import io.jmix.core.AccessManager;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.Messages;
import io.jmix.core.MetadataTools;
import io.jmix.core.metamodel.datatype.DatatypeFormatter;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.flowui.DialogWindows;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.Fragments;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.accesscontext.UiShowViewContext;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.grid.DataGridColumn;
import io.jmix.flowui.component.gridcolumnvisibility.JmixGridColumnVisibility;
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
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.upload.TemporaryStorage;
import io.jmix.flowui.view.*;
import com.vaadin.flow.server.streams.UploadHandler;
import com.vaadin.flow.server.streams.UploadMetadata;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The documents of the selected folder with the document actions and the upload. The actions follow
 * the user's level in the folder and in the selected documents.
 */
@FragmentDescriptor("document-list-fragment.xml")
public class DocumentListFragment extends Fragment<VerticalLayout> {

    private static final Logger log = LoggerFactory.getLogger(DocumentListFragment.class);

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
    private JmixButton favoriteButton;
    @ViewComponent
    private JmixGridColumnVisibility columnsButton;

    @ViewComponent
    private DataGrid<Document> documentsGrid;
    @ViewComponent
    private CollectionLoader<Document> documentsDl;
    @ViewComponent("documentsGrid.accessAction")
    private Action accessAction;
    @ViewComponent("documentsGrid.discussAction")
    private Action discussAction;
    @ViewComponent("documentsGrid.favoriteAction")
    private FavoriteListAction<Document> favoriteAction;

    @ViewComponent
    private MessageBundle messageBundle;
    @ViewComponent
    private FragmentSettingsFacet settings;

    @Autowired
    private DocumentService documentService;
    @Autowired
    private DocumentStatusService statusService;
    @Autowired
    private AccessService accessService;
    @Autowired
    private DataManager dataManager;
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
    private TagBadges tagBadges;
    @Autowired
    private StatusBadges statusBadges;
    @Autowired
    private FavoriteService favoriteService;
    @Autowired
    private Icons icons;
    @Autowired
    private FolderRenderers folderRenderers;
    @Autowired
    private Messages messages;
    @Autowired
    private MetadataTools metadataTools;
    @Autowired
    private DatatypeFormatter datatypeFormatter;
    @Autowired
    private CurrentAuthentication currentAuthentication;
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
    private Folder dropFolder;
    @Nullable
    private Document versionDocument;
    @Nullable
    private DocumentDiscussionFragment discussion;

    private AccessLevels levels = new AccessLevels(Map.of(), false);
    private Map<String, String> userNames = Map.of();
    private Set<UUID> markedIds = Set.of();
    private final Map<UUID, UploadedFile> uploadedFiles = new LinkedHashMap<>();
    private final List<UploadedFile> droppedFiles = new ArrayList<>();
    private DocumentsDropZone documentsDropZone;
    private UploadManager dropUploads;

    private Consumer<@Nullable Document> focusHandler = document -> {
    };
    private Runnable previewToggleHandler = () -> {
    };
    private Runnable contentsChangeHandler = () -> {
    };
    private Consumer<List<Document>> dragHandler = documents -> {
    };
    private Runnable favoritesChangeHandler = () -> {
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
        configureDropZone();
        configureDocumentsGridContextMenu();
        configureDiscussion();
        // the descriptor gives the column chooser no title, and an icon alone needs one
        columnsButton.setTitle(messageBundle.getMessage("columnsButton.text"));
        // the star shows the state by its look only, so the button tells a screen reader whether it is pressed
        favoriteAction.addPropertyChangeListener(change -> showFavoritePressed());
        showFavoritePressed();
    }

    private void showFavoritePressed() {
        favoriteButton.getElement().setAttribute("aria-pressed", String.valueOf(favoriteAction.isAllMarked()));
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

    // files dropped from the computer onto the table go the way of the upload button; Jmix has no element for the
    // zone, so it wraps the table here
    private void configureDropZone() {
        dropUploads = new UploadManager(getContent(), UploadHandler.toTempFile(this::onFileDropped));
        dropUploads.setMaxFiles(documentsUpload.getMaxFiles());
        dropUploads.setMaxFileSize(filesProperties.maxFileSize().toBytes());
        dropUploads.addFileRejectedListener(this::onDroppedFileRejected);
        dropUploads.addAllFinishedListener(event -> onDroppedFilesFinished());

        documentsDropZone = new DocumentsDropZone(dropUploads);
        documentsDropZone.addClassName("documents-drop-zone");
        documentsDropZone.setSizeFull();

        getContent().replace(documentsGrid, documentsDropZone);
        documentsDropZone.setContent(documentsGrid);
        getContent().setFlexGrow(1, documentsDropZone);

        documentsDropZone.addAttachListener(event -> configureDragEvents());

        if (documentsDropZone.isAttached()) {
            configureDragEvents();
        }

        // a page reload or a session timeout leaves the files of an unfinished drop in the temporary directory
        addDetachListener(detachEvent -> discardDroppedFiles());
    }

    // The zone answers any drag, so the table passes on to it only the drags of files: its rows still go to the
    // folders and the favorites. The zone forgets the files only on a dragleave of its own, which never comes while
    // the table covers it, so it is told so when the files leave the table or the drag stops without a drop: the
    // browser repeats dragover while the files are over the table.
    private void configureDragEvents() {
        documentsDropZone.getElement().executeJs("""
                const zone = this;
                if (zone.docaiDragEvents) {
                    return;
                }
                zone.docaiDragEvents = true;
                for (const type of ['dragenter', 'dragover']) {
                    zone.querySelector('vaadin-grid').addEventListener(type, event => {
                        if (!event.dataTransfer || !Array.from(event.dataTransfer.types).includes('Files')) {
                            event.stopPropagation();
                        }
                    });
                }
                let silence;
                const forget = () => {
                    clearTimeout(silence);
                    zone.dispatchEvent(new DragEvent('dragleave'));
                };
                zone.addEventListener('dragover', () => {
                    clearTimeout(silence);
                    silence = setTimeout(forget, 700);
                });
                zone.addEventListener('dragleave', event => {
                    if (event.target !== zone && !zone.contains(event.relatedTarget)) {
                        forget();
                    }
                });""");
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
     * Receives the documents the user starts to drag when the user may move every one of them, and an empty list
     * when the drag ends.
     */
    public void setDragHandler(Consumer<List<Document>> dragHandler) {
        this.dragHandler = dragHandler;
    }

    /**
     * Receives a mark set or removed in the list or in a card the list opened.
     */
    public void setFavoritesChangeHandler(Runnable favoritesChangeHandler) {
        this.favoritesChangeHandler = favoritesChangeHandler;
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
        // a disabled zone would disable the table inside it, so only the uploads of the zone go off
        dropUploads.setEnabled(folderEditable);

        if (folder != null) {
            documentsDropZone.getElement().setAttribute("data-hint",
                    messageBundle.formatMessage("dropZoneHint", metadataTools.getInstanceName(folder)));
        }

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

    // the grid fits its columns to the first rows it shows, so they fit again whenever the documents change
    @Subscribe(id = "documentsDc", target = Target.DATA_CONTAINER)
    public void onDocumentsDcCollectionChange(final CollectionContainer.CollectionChangeEvent<Document> event) {
        markedIds = favoriteService.markedIds(event.getSource().getItems());
        userNames = userNames(event.getSource().getItems().stream()
                .map(Document::getChangedBy)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));

        documentsGrid.recalculateColumnWidths();
    }

    // a column the user has dragged to another width keeps it when the other columns fit again
    @Subscribe("documentsGrid")
    public void onDocumentsGridColumnResize(final ColumnResizeEvent<Document> event) {
        event.getResizedColumn().setAutoWidth(false);
    }

    // the folder tree takes the rows only when the user may move all of them; the discussion window takes them anyway,
    // and its selection calls for them until the drag ends
    @Subscribe("documentsGrid")
    public void onDocumentsGridDragStart(final GridDragStartEvent<Document> event) {
        List<Document> documents = event.getDraggedItems();

        if (documents.stream().allMatch(document -> levels.allows(document.getAccessSet(), AccessLevel.EDIT))) {
            dragHandler.accept(documents);
        }

        if (discussion != null && discussion.isOpened()) {
            discussion.setDocumentsDragged(true);
        }
    }

    @Subscribe("documentsGrid")
    public void onDocumentsGridDragEnd(final GridDragEndEvent<Document> event) {
        dragHandler.accept(List.of());

        if (discussion != null && discussion.isOpened()) {
            discussion.setDocumentsDragged(false);
        }
    }

    /**
     * Shows again which documents the user has marked: after a mark set or removed outside the list.
     */
    public void refreshFavorites() {
        markedIds = favoriteService.markedIds(documentsDl.getContainer().getItems());
        documentsGrid.getDataProvider().refreshAll();
        favoriteAction.refreshState();
    }

    @Install(to = "documentsGrid.favoriteAction", subject = "afterToggleHandler")
    private void documentsGridFavoriteActionAfterToggleHandler() {
        refreshFavorites();
        favoritesChangeHandler.run();
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
        String marked = messages.getMessage(FavoriteListAction.class, "markedStar.label");

        return new ComponentRenderer<>(document -> {
            Span name = uiComponents.create(Span.class);
            name.addClassName("name-with-icon");
            Span text = uiComponents.create(Span.class);
            text.addClassName("name-text");
            text.setText(document.getName());
            name.add(icons.get(fileIcon(document)), text);

            // after the name: a long name ends with an ellipsis, and the star stays in sight; a screen reader names
            // it, as the key of an own access
            if (markedIds.contains(document.getId())) {
                Component star = FavoriteListAction.star(icons, true);
                star.getElement().setAttribute("role", "img");
                star.getElement().setAttribute("aria-label", marked);
                name.add(star);
            }

            if (showsOwnAccess(document)) {
                folderRenderers.markOwnAccess(name);
            }

            return name;
        });
    }

    @Supply(to = "documentsGrid.tags", subject = "renderer")
    private Renderer<Document> tagsRenderer() {
        return new ComponentRenderer<>(document -> tagBadges.create(document.getTags()));
    }

    @Supply(to = "documentsGrid.status", subject = "renderer")
    private Renderer<Document> statusRenderer() {
        return new ComponentRenderer<>(document -> statusBadges.create(document.getStatus()));
    }

    @Supply(to = "documentsGrid.fileSize", subject = "renderer")
    private Renderer<Document> fileSizeRenderer() {
        return new TextRenderer<>(document -> document.getCurrentRevision() == null
                ? ""
                : fileSizeFormatter.format(document.getCurrentRevision().getFileSize()));
    }

    // who and when share one column, as in the card, in the user's time zone: the date in the cell, the time in its
    // title; a tooltip generator of a grid column throws in the browser of Vaadin 25.3 when a tooltip has no row
    @Supply(to = "documentsGrid.changedDate", subject = "renderer")
    private Renderer<Document> changedDateRenderer() {
        return LitRenderer.<Document>of("<span title=\"${item.time}\">${item.text}</span>")
                .withProperty("text", document -> {
                    if (document.getChangedDate() == null) {
                        return "";
                    }

                    String date = datatypeFormatter.formatLocalDate(changedTime(document).toLocalDate());

                    return document.getChangedBy() == null
                            ? date
                            : userNames.getOrDefault(document.getChangedBy(), document.getChangedBy()) + " · " + date;
                })
                .withProperty("time", document -> document.getChangedDate() == null
                        ? ""
                        : datatypeFormatter.formatLocalDateTime(changedTime(document)));
    }

    private LocalDateTime changedTime(Document document) {
        return document.getChangedDate()
                .atZoneSameInstant(currentAuthentication.getTimeZone().toZoneId())
                .toLocalDateTime();
    }

    // the names of the users as the application shows them; a user deleted since keeps the username
    private Map<String, String> userNames(Set<String> usernames) {
        if (usernames.isEmpty()) {
            return Map.of();
        }

        return dataManager.load(User.class)
                .query("select u from User u where u.username in :usernames")
                .parameter("usernames", usernames)
                .fetchPlan(FetchPlan.INSTANCE_NAME)
                .list()
                .stream()
                .collect(Collectors.toMap(User::getUsername, metadataTools::getInstanceName));
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
            uploadInto(target, new ArrayList<>(uploadedFiles.values()));
        } finally {
            discardUploadedFiles();
        }
    }

    // the dropped files go into the folder shown when the first of them arrived
    private void onFileDropped(UploadMetadata metadata, File file) {
        if (dropFolder == null) {
            dropFolder = folder;
        }

        droppedFiles.add(new UploadedFile(metadata.fileName(), file.toPath()));
    }

    private void onDroppedFilesFinished() {
        Folder target = dropFolder;
        dropFolder = null;
        dropUploads.clearFileList();

        try {
            if (target != null && !droppedFiles.isEmpty()) {
                uploadInto(target, List.copyOf(droppedFiles));
            }
        } finally {
            discardDroppedFiles();
        }
    }

    private void onDroppedFileRejected(UploadManager.FileRejectedEvent event) {
        String key = switch (event.getReason()) {
            case TOO_MANY_FILES -> "dropRejected.tooManyFiles";
            case FILE_TOO_LARGE -> "dropRejected.fileTooLarge";
            default -> "dropRejected.other";
        };

        notifications.create(messageBundle.formatMessage(key, event.getFileName()))
                .withType(Notifications.Type.WARNING)
                .show();
    }

    // the upload button and the drop zone both end here
    private void uploadInto(Folder target, List<UploadedFile> files) {
        try {
            showUploadReport(documentService.upload(target, files));
        } finally {
            contentsChanged();
        }
    }

    // the zone keeps its files in the temporary directory of the system, not in the temporary storage of Jmix
    private void discardDroppedFiles() {
        for (UploadedFile file : droppedFiles) {
            try {
                Files.deleteIfExists(file.content());
            } catch (IOException e) {
                log.warn("A dropped file is left in the temporary directory: {}", file.content(), e);
            }
        }

        droppedFiles.clear();
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

    /**
     * Opens the card of the document; after a save the list shows its documents again.
     */
    public void openCard(@Nullable Document document) {
        if (document == null) {
            return;
        }

        dialogWindows.detail(hostView(), Document.class)
                .withViewClass(DocumentDetailView.class)
                .editEntity(document)
                .withAfterCloseListener(closeEvent -> {
                    // a saved card may have renamed the document, and any card may have marked it with its star
                    if (closeEvent.closedWith(StandardOutcome.SAVE)) {
                        contentsChanged();
                    } else {
                        refreshFavorites();
                        favoritesChangeHandler.run();
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

    private boolean allSelectedEditable() {
        Set<Document> selected = documentsGrid.getSelectedItems();
        return !selected.isEmpty() && selected.stream()
                .allMatch(document -> levels.allows(document.getAccessSet(), AccessLevel.EDIT));
    }

    private void updateVersionUpload() {
        Document document = documentsGrid.getSelectedItems().size() == 1 ? documentsGrid.getSingleSelectedItem() : null;
        versionUpload.setEnabled(document != null
                && !Boolean.TRUE.equals(document.getStatus().getLocked())
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

    /**
     * Moves the documents into the folder, as the move action does: a move that changes the access of the documents
     * asks first. The documents and the counts of the folder tree are reloaded after the move.
     */
    public void move(Collection<Document> documents, Folder target) {
        Runnable move = () -> {
            documentService.move(documents, target);
            contentsChanged();

            // the documents of the shown folder stay where they are when it is the target
            if (!target.equals(folder)) {
                notifications.create(messageBundle.formatMessage("documentsMoved",
                                metadataTools.getInstanceName(target), documents.size()))
                        .withType(Notifications.Type.SUCCESS)
                        .show();
            }
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

    // the statuses that fit every selected document, in the toolbar and in the submenu of the context menu, which
    // the dictionary can make long
    private void updateStatusButton() {
        Set<Document> selected = documentsGrid.getSelectedItems();
        List<DocumentStatus> targets = allSelectedEditable()
                ? statusService.loadCommonTargets(selected.stream()
                        .map(Document::getStatus)
                        .collect(Collectors.toSet()))
                : List.of();

        // a fragment keeps the id of a menu item in the component data, where @ViewComponent does not look
        GridMenuItem<Document> statusItem = documentsGrid.getContextMenu().getItems().stream()
                .filter(item -> FragmentUtils.sameId(item, "statusDocumentItem"))
                .findFirst()
                .orElseThrow();

        statusButton.removeAll();
        statusItem.getSubMenu().removeAll();

        for (DocumentStatus target : targets) {
            statusButton.addItem("status-" + target.getId(), statusBadges.option(target),
                    click -> changeStatus(target));
            statusItem.getSubMenu().addItem(statusBadges.option(target), click -> changeStatus(target));
        }

        statusButton.setEnabled(!targets.isEmpty());
        statusItem.setEnabled(!targets.isEmpty());
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
