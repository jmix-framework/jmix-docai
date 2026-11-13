package com.company.docai.view.document;

import com.company.docai.ai.AiSettings;
import com.company.docai.app.service.access.AccessChecker;
import com.company.docai.app.service.access.AccessService;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.document.DocumentStatusService;
import com.company.docai.app.service.document.NewVersion;
import com.company.docai.app.service.document.UploadedFile;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.app.service.tag.TagService;
import com.company.docai.app.service.user.UserService;
import com.company.docai.model.access.AccessDraft;
import com.company.docai.model.access.AccessLevel;
import com.company.docai.model.document.Document;
import com.company.docai.model.document.DocumentRevision;
import com.company.docai.model.document.DocumentStatus;
import com.company.docai.model.tag.Tag;
import com.company.docai.view.access.AccessDetailView;
import com.company.docai.view.favorite.FavoriteCardAction;
import com.company.docai.view.main.MainView;
import com.company.docai.view.status.StatusBadges;
import com.company.docai.view.tag.TagBadges;
import com.google.common.base.Strings;
import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBoxBase;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.grid.ItemDoubleClickEvent;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.upload.FailedEvent;
import com.vaadin.flow.component.upload.FileRejectedEvent;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.data.renderer.Renderer;
import com.vaadin.flow.router.Route;
import io.jmix.core.EntityStates;
import io.jmix.core.Messages;
import io.jmix.core.MetadataTools;
import io.jmix.core.SaveContext;
import io.jmix.flowui.DialogWindows;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.component.datepicker.TypedDatePicker;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.select.JmixSelect;
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
import io.jmix.uirecoveryflowui.facet.DraftRestoreContext;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
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
@DialogMode(width = "88em", height = "58em", resizable = true, maximizable = true)
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
    private JmixButton accessButton;
    @ViewComponent
    private JmixButton favoriteButton;

    @ViewComponent
    private TypedTextField<String> nameField;
    @ViewComponent
    private TypedDatePicker<LocalDate> documentDateField;
    @ViewComponent
    private JmixSelect<DocumentStatus> statusField;
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
    private DocumentService documentService;
    @Autowired
    private DocumentStatusService statusService;
    @Autowired
    private FolderService folderService;
    @Autowired
    private TagService tagService;
    @Autowired
    private TagBadges tagBadges;
    @Autowired
    private StatusBadges statusBadges;
    @Autowired
    private AccessService accessService;
    @Autowired
    private AccessChecker accessChecker;
    @Autowired
    private UserService userService;
    @Autowired
    private DocumentUploads documentUploads;
    @Autowired
    private DocumentRenderers documentRenderers;
    @Autowired
    private DocumentCards documentCards;
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
    @Autowired
    private DocumentCardChanges changes;

    private Map<String, String> userNames = Map.of();
    private boolean draftFound;

    @Subscribe
    public void onInit(final InitEvent event) {
        // a session timeout or a page reload removes the card without closing it
        addDetachListener(detachEvent -> changes.clear());
        documentUploads.limitFileSize(newVersionUpload);
    }

    // the repository passes the open folder; a new document opened by its address goes into the root
    @Subscribe
    public void onInitEntity(final InitEntityEvent<Document> event) {
        // the badge shows the status before the first save, which gives the document the initial status too
        event.getEntity().setStatus(statusService.getInitial());

        if (event.getEntity().getFolder() == null) {
            event.getEntity().setFolder(folderService.getRoot());
        }
    }

    @Subscribe
    public void onReady(final ReadyEvent event) {
        // binding the action after the init event sets the title of the star, and Jmix copies a title into the aria
        // label, an empty one too
        favoriteButton.setAriaLabel(messages.getMessage(FavoriteCardAction.class, "favoriteAction.text"));

        // the star shows the state by its look only, so the button tells a screen reader whether it is pressed
        favoriteAction.addPropertyChangeListener(change -> favoriteButton.getElement()
                .setAttribute("aria-pressed", String.valueOf(favoriteAction.isMarked())));

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

    // the drafts facet hands a draft it finds here before it asks whether to restore it, and Fill with AI started
    // from outside the card waits for that answer
    @Install(to = "draftsFacet", subject = "restoreDelegate")
    private void draftsFacetRestoreDelegate(final DraftRestoreContext context) {
        draftFound = true;
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
        documentUploads.showRejected(event);
    }

    @Subscribe("newVersionUpload")
    public void onNewVersionUploadFailed(final FailedEvent event) {
        documentUploads.showFailed(event.getFileName());
    }

    @Subscribe("downloadButton")
    public void onDownloadButtonClick(final ClickEvent<JmixButton> event) {
        revisionDownloader.download(getEditedEntity().getCurrentRevision());
    }

    // the field stages the status like a new version: the change applies on OK, last, as the service wants it
    @Subscribe("statusField")
    public void onStatusFieldComponentValueChange(
            final AbstractField.ComponentValueChangeEvent<JmixSelect<DocumentStatus>, DocumentStatus> event) {
        if (event.isFromClient()) {
            DocumentStatus target = event.getValue();
            changes.changeStatus(target == null || target.equals(getEditedEntity().getStatus()) ? null : target);
            afterStaging();
        }
    }

    @Install(to = "statusField", subject = "itemLabelGenerator")
    private String statusFieldItemLabelGenerator(final DocumentStatus status) {
        return status.getName();
    }

    @Supply(to = "statusField", subject = "renderer")
    private ComponentRenderer<Span, DocumentStatus> statusFieldRenderer() {
        return new ComponentRenderer<>(statusBadges::option);
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
                && !isLocked()
                && !isReadOnly()
                && !revision.equals(getEditedEntity().getCurrentRevision());
    }

    @Supply(to = "revisionsGrid.fileSize", subject = "renderer")
    private Renderer<DocumentRevision> revisionSizeRenderer() {
        return documentRenderers.revisionSizeRenderer();
    }

    @Supply(to = "revisionsGrid.createdDate", subject = "renderer")
    private Renderer<DocumentRevision> revisionDateRenderer() {
        return documentRenderers.userDateTimeRenderer(DocumentRevision::getCreatedDate);
    }

    @Supply(to = "revisionsGrid.createdBy", subject = "renderer")
    private Renderer<DocumentRevision> revisionAuthorRenderer() {
        return documentRenderers.revisionAuthorRenderer(username -> userNames.getOrDefault(username, username));
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
        if (!documentCards.checkAvailable(getEditedEntity())) {
            // the repository reloads its list after a card that saved
            close(StandardOutcome.SAVE);
            return;
        }

        getViewData().loadAll();
        setReadOnly(!accessChecker.canEdit(getEditedEntity()));

        showDocument();
    }

    /**
     * The staged changes are not attributes of the entity, so the data context is told explicitly:
     * otherwise it would skip the save and the unsaved-changes dialog would stay silent.
     */
    private void afterStaging() {
        getViewData().getDataContext().setModified(getEditedEntity(), true);

        pendingChanges.setText(changes.describe());
        fillWithAiAction.refreshState();
    }

    /**
     * Fills what stays the same while the card is open; what waits for OK is shown by {@link #afterStaging()}.
     */
    private void showDocument() {
        Document document = getEditedEntity();
        DocumentStatus status = document.getStatus();
        // the view turns read-only before it is shown when the user only views the document
        boolean locked = isLocked() || isReadOnly();

        updateTitle();
        StatusBadges.paint(statusBadge, status);
        showTags();

        statusField.setItems(statusService.loadChoices(status));
        statusField.setValue(status);
        statusField.setReadOnly(isReadOnly());

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

        accessButton.setVisible(!isNew() && accessChecker.getLevel(document.getAccessSet()) == AccessLevel.MANAGE);

        favoriteAction.refreshState();

        folderField.setValue(folderService.getPath(document.getFolder()).stream()
                .map(metadataTools::getInstanceName)
                .collect(Collectors.joining(" / ")));

        currentVersionField.setValue(documentRenderers.versionText(document.getCurrentRevision()));

        userNames = userService.namesOf(Stream.concat(Stream.of(document.getCreatedBy(), document.getChangedBy()),
                        revisionsDc.getItems().stream().map(DocumentRevision::getCreatedBy))
                .toList());

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

    /**
     * Starts Fill with AI as its button does, when the card offers it: the AI is on, the user may edit the document,
     * its status does not lock it and the card shows a file. A card that asks whether to restore a draft leaves the
     * start to its button, after the answer.
     */
    public void fillWithAi() {
        if (!draftFound && fillWithAiAction.isVisible() && fillWithAiAction.isEnabled()) {
            fillWithAiAction.execute();
        }
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

    private String describeChange(String user, OffsetDateTime date) {
        if (user == null || date == null) {
            return "";
        }

        return "%s, %s".formatted(userNames.getOrDefault(user, user), documentRenderers.userDateTime(date));
    }

    private boolean isNew() {
        return entityStates.isNew(getEditedEntity());
    }

    private boolean isLocked() {
        return Boolean.TRUE.equals(getEditedEntity().getStatus().getLocked());
    }
}
