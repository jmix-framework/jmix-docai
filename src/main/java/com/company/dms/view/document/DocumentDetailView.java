package com.company.dms.view.document;

import com.company.dms.ai.AiSettings;
import com.company.dms.app.config.FilesProperties;
import com.company.dms.app.service.access.AccessChecker;
import com.company.dms.app.service.access.AccessService;
import com.company.dms.app.service.document.DocumentService;
import com.company.dms.app.service.document.NewVersion;
import com.company.dms.app.service.document.UploadedFile;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.app.service.tag.TagService;
import com.company.dms.model.access.AccessDraft;
import com.company.dms.model.access.AccessLevel;
import com.company.dms.model.document.Document;
import com.company.dms.model.document.DocumentRevision;
import com.company.dms.model.document.DocumentStatus;
import com.company.dms.model.tag.Tag;
import com.company.dms.model.user.User;
import com.company.dms.view.access.AccessDetailView;
import com.company.dms.view.favorite.FavoriteCardAction;
import com.company.dms.view.main.MainView;
import com.company.dms.view.tag.TagBadges;
import com.google.common.base.Strings;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBoxBase;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.grid.ItemDoubleClickEvent;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.upload.FailedEvent;
import com.vaadin.flow.component.upload.FileRejectedEvent;
import com.vaadin.flow.data.renderer.Renderer;
import com.vaadin.flow.data.renderer.TextRenderer;
import com.vaadin.flow.router.Route;
import io.jmix.core.DataManager;
import io.jmix.core.EntityStates;
import io.jmix.core.FetchPlan;
import io.jmix.core.Id;
import io.jmix.core.Messages;
import io.jmix.core.MetadataTools;
import io.jmix.core.SaveContext;
import io.jmix.core.metamodel.datatype.DatatypeFormatter;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.flowui.DialogWindows;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.component.datepicker.TypedDatePicker;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.tabsheet.JmixTabSheet;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.component.upload.JmixUpload;
import io.jmix.flowui.component.upload.UploadSucceededEvent;
import io.jmix.flowui.component.validation.ValidationErrors;
import io.jmix.flowui.kit.action.ActionPerformedEvent;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.model.InstanceContainer;
import io.jmix.flowui.upload.TemporaryStorage;
import io.jmix.flowui.view.*;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The document card. A new version, a restored version and a status transition wait for OK
 * together with the edited fields and the tags, and are saved by one service call. A user who only views
 * the document gets the card read-only. A tag next to the status opens the page of its documents.
 */
@Route(value = "documents/:id", layout = MainView.class)
@ViewController(id = "Document.detail")
@ViewDescriptor(path = "document-detail-view.xml")
@EditedEntityContainer("documentDc")
@DialogMode(width = "86em", height = "58em", resizable = true, maximizable = true)
public class DocumentDetailView extends StandardDetailView<Document> {

    @ViewComponent
    private H3 documentTitle;
    @ViewComponent
    private Badge statusBadge;
    @ViewComponent
    private Span documentTags;
    @ViewComponent
    private Span pendingChanges;

    @ViewComponent
    private JmixUpload<TemporaryStorage.FileInfo> newVersionUpload;

    @ViewComponent
    private JmixButton downloadButton;
    @ViewComponent
    private JmixButton activateButton;
    @ViewComponent
    private JmixButton archiveButton;
    @ViewComponent
    private JmixButton unarchiveButton;
    @ViewComponent
    private JmixButton accessButton;
    @ViewComponent
    private JmixButton favoriteButton;

    @ViewComponent
    private TypedTextField<String> nameField;
    @ViewComponent
    private TypedDatePicker<LocalDate> documentDateField;
    @ViewComponent
    private TypedTextField<String> folderField;
    @ViewComponent
    private TypedTextField<String> currentVersionField;
    @ViewComponent
    private TypedTextField<String> createdField;
    @ViewComponent
    private TypedTextField<String> modifiedField;

    @ViewComponent
    private JmixTabSheet cardTabs;
    @ViewComponent
    private Tab propertiesTab;
    @ViewComponent
    private Tab versionsTab;
    @ViewComponent
    private Tab commentsTab;
    @ViewComponent
    private Tab assistantTab;

    @ViewComponent
    private DocumentPreviewFragment preview;
    @ViewComponent
    private DocumentPropertiesFragment propertiesFragment;
    @ViewComponent
    private DocumentCommentsFragment comments;
    @ViewComponent
    private DocumentAssistantFragment assistant;
    @ViewComponent
    private DocumentPreviewFragment assistantPreview;
    @ViewComponent
    private DataGrid<DocumentRevision> revisionsGrid;
    @ViewComponent
    private CollectionContainer<DocumentRevision> revisionsDc;

    @ViewComponent
    private FillWithAiAction fillWithAiAction;
    @ViewComponent
    private FavoriteCardAction favoriteAction;

    @ViewComponent
    private MessageBundle messageBundle;

    @Autowired
    private DataManager dataManager;
    @Autowired
    private DocumentService documentService;
    @Autowired
    private FolderService folderService;
    @Autowired
    private TagService tagService;
    @Autowired
    private TagBadges tagBadges;
    @Autowired
    private AccessService accessService;
    @Autowired
    private AccessChecker accessChecker;
    @Autowired
    private TemporaryStorage temporaryStorage;
    @Autowired
    private FilesProperties filesProperties;
    @Autowired
    private FileSizeFormatter fileSizeFormatter;
    @Autowired
    private DatatypeFormatter datatypeFormatter;
    @Autowired
    private CurrentAuthentication currentAuthentication;
    @Autowired
    private EntityStates entityStates;
    @Autowired
    private MetadataTools metadataTools;
    @Autowired
    private Messages messages;
    @Autowired
    private Notifications notifications;
    @Autowired
    private DialogWindows dialogWindows;
    @Autowired
    private RevisionDownloader revisionDownloader;
    @Autowired
    private AiSettings aiSettings;

    private DocumentCardChanges changes;
    private Map<String, String> userNames = Map.of();

    @Subscribe
    public void onInit(final InitEvent event) {
        changes = new DocumentCardChanges(temporaryStorage);
        // a session timeout or a page reload removes the card without closing it
        addDetachListener(detachEvent -> changes.clear());
        newVersionUpload.setMaxFileSize((int) filesProperties.maxFileSize().toBytes());
    }

    // the repository passes the open folder; a new document opened by its address goes into the root
    @Subscribe
    public void onInitEntity(final InitEntityEvent<Document> event) {
        if (event.getEntity().getFolder() == null) {
            event.getEntity().setFolder(folderService.getRoot());
        }
    }

    @Subscribe
    public void onReady(final ReadyEvent event) {
        // binding the action after the init event sets the title of the star, and Jmix copies a title into the aria
        // label, an empty one too
        favoriteButton.setAriaLabel(messages.getMessage(FavoriteCardAction.class, "favoriteAction.text"));

        showDocument();
        preview.showRevision(getEditedEntity().getCurrentRevision());
        showComments();
        showAssistant();
    }

    @Subscribe
    public void onAfterClose(final AfterCloseEvent event) {
        changes.clear();
    }

    @Subscribe(id = "documentDc", target = Target.DATA_CONTAINER)
    public void onDocumentDcItemPropertyChange(final InstanceContainer.ItemPropertyChangeEvent<Document> event) {
        if ("name".equals(event.getProperty())) {
            updateTitle();
        }

        if ("tags".equals(event.getProperty())) {
            showTags();
        }
    }

    // a typed name takes the tag of the vocabulary whatever its case, or makes a new tag that OK saves
    @Subscribe("tagsField")
    public void onTagsFieldCustomValueSet(final ComboBoxBase.CustomValueSetEvent<MultiSelectComboBox<Tag>> event) {
        if (event.getDetail().isBlank()) {
            return;
        }

        Tag tag = tagService.findOrCreate(event.getDetail());
        List<Tag> tags = new ArrayList<>(Objects.requireNonNullElse(getEditedEntity().getTags(), List.of()));

        if (tags.stream().noneMatch(carried -> carried.getName().equalsIgnoreCase(tag.getName()))) {
            tags.add(tag);
            getEditedEntity().setTags(tags);
        }
    }

    @Subscribe
    public void onValidation(final ValidationEvent event) {
        if (isNew() && changes.getUploadedFile() == null) {
            event.addErrors(ValidationErrors.of(messageBundle.getMessage("fileRequired")));
        }
    }

    @Install(target = Target.DATA_CONTEXT)
    private Set<Object> saveDelegate(final SaveContext saveContext) {
        Document document = getEditedEntity();
        Document saved = isNew()
                ? documentService.create(document, changes.getUploadedFile(), changes.getTargetStatus())
                : documentService.save(document, changes.getNewVersion(), changes.getTargetStatus());

        return Set.of(saved);
    }

    @Subscribe("newVersionUpload")
    public void onNewVersionUploadSucceeded(final UploadSucceededEvent<TemporaryStorage.FileInfo> event) {
        UploadedFile file = new UploadedFile(event.getFileName(), event.getData().getFile().toPath());
        changes.uploadVersion(event.getData().getId(), file);

        if (isNew() && Strings.isNullOrEmpty(nameField.getValue())) {
            nameField.setValue(event.getFileName());
        }

        newVersionUpload.clearFileList();
        preview.showFile(file);
        afterStaging();
    }

    @Subscribe("newVersionUpload")
    public void onNewVersionUploadFileRejected(final FileRejectedEvent event) {
        notifications.create(event.getFileName() + ": " + event.getErrorMessage())
                .withType(Notifications.Type.WARNING)
                .show();
    }

    @Subscribe("newVersionUpload")
    public void onNewVersionUploadFailed(final FailedEvent event) {
        notifications.create(messageBundle.formatMessage("uploadFailed", event.getFileName()))
                .withType(Notifications.Type.ERROR)
                .show();
    }

    @Subscribe("downloadButton")
    public void onDownloadButtonClick(final ClickEvent<JmixButton> event) {
        revisionDownloader.download(getEditedEntity().getCurrentRevision());
    }

    @Subscribe("activateButton")
    public void onActivateButtonClick(final ClickEvent<JmixButton> event) {
        toggleTransition(DocumentStatus.ACTIVE);
    }

    @Subscribe("archiveButton")
    public void onArchiveButtonClick(final ClickEvent<JmixButton> event) {
        toggleTransition(DocumentStatus.ARCHIVED);
    }

    @Subscribe("unarchiveButton")
    public void onUnarchiveButtonClick(final ClickEvent<JmixButton> event) {
        toggleTransition(DocumentStatus.ACTIVE);
    }

    @Subscribe("accessButton")
    public void onAccessButtonClick(final ClickEvent<JmixButton> event) {
        // the access dialog saves the document at once and the card takes it again, so its own changes go first
        if (hasUnsavedChanges()) {
            notifications.create(messageBundle.getMessage("saveBeforeAccess"))
                    .withType(Notifications.Type.WARNING)
                    .show();
            return;
        }

        dialogWindows.detail(this, AccessDraft.class)
                .withViewClass(AccessDetailView.class)
                .editEntity(accessService.describe(getEditedEntity()))
                .withAfterCloseListener(closeEvent -> {
                    if (closeEvent.closedWith(StandardOutcome.SAVE)) {
                        takeDocumentAgain();
                    }
                })
                .open();
    }

    @Subscribe("revisionsGrid.downloadRevisionAction")
    public void onDownloadRevisionAction(final ActionPerformedEvent event) {
        revisionDownloader.download(revisionsGrid.getSingleSelectedItem());
    }

    @Subscribe("revisionsGrid.showRevisionAction")
    public void onShowRevisionAction(final ActionPerformedEvent event) {
        openRevision(revisionsGrid.getSingleSelectedItem());
    }

    @Subscribe("revisionsGrid")
    public void onRevisionsGridItemDoubleClick(final ItemDoubleClickEvent<DocumentRevision> event) {
        openRevision(event.getItem());
    }

    @Subscribe("revisionsGrid.restoreRevisionAction")
    public void onRestoreRevisionAction(final ActionPerformedEvent event) {
        DocumentRevision revision = revisionsGrid.getSingleSelectedItem();
        changes.restoreVersion(revision);
        preview.showRevision(revision);
        afterStaging();
    }

    @Install(to = "revisionsGrid.restoreRevisionAction", subject = "enabledRule")
    private boolean restoreRevisionActionEnabledRule() {
        DocumentRevision revision = revisionsGrid.getSingleSelectedItem();
        return revision != null
                && !isArchived()
                && !isReadOnly()
                && !revision.equals(getEditedEntity().getCurrentRevision());
    }

    @Supply(to = "revisionsGrid.fileSize", subject = "renderer")
    private Renderer<DocumentRevision> revisionSizeRenderer() {
        return new TextRenderer<>(revision -> fileSizeFormatter.format(revision.getFileSize()));
    }

    @Supply(to = "revisionsGrid.createdBy", subject = "renderer")
    private Renderer<DocumentRevision> revisionAuthorRenderer() {
        return new TextRenderer<>(revision -> revision.getCreatedBy() == null
                ? ""
                : userNames.getOrDefault(revision.getCreatedBy(), revision.getCreatedBy()));
    }

    // a version opens over the card, so showing it does not replace the card's own preview
    private void openRevision(DocumentRevision revision) {
        dialogWindows.read(this, DocumentRevision.class)
                .withViewClass(RevisionReadView.class)
                .readEntity(revision)
                .open();
    }

    /**
     * The access dialog saved the document, so the card takes it again: OK then does not collide with that save,
     * and the card follows the new level of the user. A user who gave the document up sees the card close.
     */
    private void takeDocumentAgain() {
        if (dataManager.load(Id.of(getEditedEntity())).optional().isEmpty()) {
            notifications.create(messageBundle.getMessage("documentUnavailable"))
                    .withType(Notifications.Type.WARNING)
                    .show();
            // the repository reloads its list after a card that saved
            close(StandardOutcome.SAVE);
            return;
        }

        getViewData().loadAll();
        setReadOnly(!accessChecker.canEdit(getEditedEntity()));

        showDocument();
    }

    private void toggleTransition(DocumentStatus target) {
        changes.changeStatus(changes.getTargetStatus() == target ? null : target);
        afterStaging();
    }

    /**
     * The staged changes are not attributes of the entity, so the data context is told explicitly:
     * otherwise it would skip the save and the unsaved-changes dialog would stay silent.
     */
    private void afterStaging() {
        getViewData().getDataContext().setModified(getEditedEntity(), true);

        pendingChanges.setText(describePendingChanges());
        highlightTransition(activateButton, DocumentStatus.ACTIVE);
        highlightTransition(archiveButton, DocumentStatus.ARCHIVED);
        highlightTransition(unarchiveButton, DocumentStatus.ACTIVE);
        fillWithAiAction.refreshState();
    }

    /**
     * Fills what stays the same while the card is open; what waits for OK is shown by {@link #afterStaging()}.
     */
    private void showDocument() {
        Document document = getEditedEntity();
        DocumentStatus status = document.getStatus();
        boolean archived = isArchived();
        // the view turns read-only before it is shown when the user only views the document
        boolean locked = archived || isReadOnly();

        updateTitle();
        statusBadge.addClassName(statusColor(status));
        showTags();

        nameField.setReadOnly(locked);
        documentDateField.setReadOnly(locked);
        newVersionUpload.setVisible(!locked);

        if (locked) {
            propertiesFragment.lock();
        }

        if (isNew() && newVersionUpload.getUploadButton() instanceof Button uploadButton) {
            uploadButton.setText(messageBundle.getMessage("uploadFile"));
        }

        downloadButton.setVisible(document.getCurrentRevision() != null);

        activateButton.setVisible(status == DocumentStatus.DRAFT && !isReadOnly());
        archiveButton.setVisible(status == DocumentStatus.ACTIVE && !isReadOnly());
        unarchiveButton.setVisible(archived && !isReadOnly());
        accessButton.setVisible(!isNew() && accessChecker.getLevel(document.getAccessSet()) == AccessLevel.MANAGE);

        favoriteAction.refreshState();

        folderField.setValue(folderService.getPath(document.getFolder()).stream()
                .map(metadataTools::getInstanceName)
                .collect(Collectors.joining(" / ")));

        DocumentRevision current = document.getCurrentRevision();
        currentVersionField.setValue(current == null
                ? ""
                : "%d · %s · %s".formatted(current.getNumber(), current.getFile().getFileName(),
                fileSizeFormatter.format(current.getFileSize())));

        userNames = userNames(Stream.concat(Stream.of(document.getCreatedBy(), document.getChangedBy()),
                        revisionsDc.getItems().stream().map(DocumentRevision::getCreatedBy))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));

        createdField.setValue(describeChange(document.getCreatedBy(), document.getCreatedDate()));
        modifiedField.setValue(describeChange(document.getChangedBy(), document.getChangedDate()));
        versionsTab.setLabel(messageBundle.formatMessage("versionsTab.label", revisionsDc.getItems().size()));
        fillWithAiAction.refreshState();
    }

    // a comment refers to a saved document, so a new one gets the tab after the first save
    private void showComments() {
        commentsTab.setVisible(!isNew());

        if (!isNew()) {
            comments.show(getEditedEntity(),
                    count -> commentsTab.setLabel(messageBundle.formatMessage("commentsTab.label", count)));
        }
    }

    // the assistant reads the saved document, so a new one gets it after the first save
    private void showAssistant() {
        boolean shown = aiSettings.isAvailable() && !isNew();
        assistantTab.setVisible(shown);

        if (shown) {
            assistant.show(getEditedEntity());
            assistantPreview.showRevision(getEditedEntity().getCurrentRevision());
        }
    }

    /**
     * Returns the version the card shows in its preview: a version waiting for OK or the current one; null
     * for a new document without a file.
     */
    public @Nullable NewVersion getShownVersion() {
        NewVersion pending = changes.getNewVersion();
        DocumentRevision current = getEditedEntity().getCurrentRevision();

        if (pending != null || current == null) {
            return pending;
        }

        return new NewVersion.FromRevision(current);
    }

    /**
     * Opens the tab with the type and the fields of the document.
     */
    public void showProperties() {
        cardTabs.setSelectedTab(propertiesTab);
    }

    private void showTags() {
        documentTags.removeAll();
        documentTags.add(tagBadges.create(getEditedEntity().getTags(), this::openTag));
    }

    // the card closes as from its close button, asking about unsaved changes, and then the page of the tag opens
    private void openTag(Tag tag) {
        closeWithDefaultAction().then(() -> tagBadges.open(tag));
    }

    private void updateTitle() {
        String name = Strings.nullToEmpty(getEditedEntity().getName());
        documentTitle.setText(name.isEmpty() && isNew() ? messageBundle.getMessage("newDocument") : name);
    }

    private void highlightTransition(JmixButton button, DocumentStatus target) {
        if (changes.getTargetStatus() == target) {
            button.addThemeVariants(ButtonVariant.PRIMARY);
        } else {
            button.removeThemeVariants(ButtonVariant.PRIMARY);
        }
    }

    private String describePendingChanges() {
        List<String> parts = new ArrayList<>();
        switch (changes.getNewVersion()) {
            case NewVersion.FromFile(UploadedFile file) ->
                    parts.add(messageBundle.formatMessage("pendingNewVersion", file.fileName()));
            case NewVersion.FromRevision(DocumentRevision revision) ->
                    parts.add(messageBundle.formatMessage("pendingRestoredVersion", revision.getNumber()));
            case null -> {
                // do nothing
            }
        }

        if (changes.getTargetStatus() != null) {
            parts.add("→ " + messages.getMessage(changes.getTargetStatus()));
        }

        return String.join(" · ", parts);
    }

    private String describeChange(String user, OffsetDateTime date) {
        if (user == null || date == null) {
            return "";
        }

        LocalDateTime time = date.atZoneSameInstant(currentAuthentication.getTimeZone().toZoneId()).toLocalDateTime();

        return "%s, %s".formatted(userNames.getOrDefault(user, user), datatypeFormatter.formatLocalDateTime(time));
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

    /**
     * Returns the Aura color class of a status badge. Aura has no neutral badge variant, so all three use its
     * color classes.
     */
    public static String statusColor(DocumentStatus status) {
        return switch (status) {
            case DRAFT -> "v-neutral";
            case ACTIVE -> "v-success";
            case ARCHIVED -> "v-warning";
        };
    }

    private boolean isNew() {
        return entityStates.isNew(getEditedEntity());
    }

    private boolean isArchived() {
        return getEditedEntity().getStatus() == DocumentStatus.ARCHIVED;
    }
}
