package com.company.dms.view.document;

import com.company.dms.app.config.FilesProperties;
import com.company.dms.app.service.document.DocumentService;
import com.company.dms.app.service.document.NewVersion;
import com.company.dms.app.service.document.UploadedFile;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.model.document.Document;
import com.company.dms.model.document.DocumentRevision;
import com.company.dms.model.document.DocumentStatus;
import com.company.dms.view.main.MainView;
import com.google.common.base.Strings;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.ItemDoubleClickEvent;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.upload.FileRejectedEvent;
import com.vaadin.flow.data.renderer.Renderer;
import com.vaadin.flow.data.renderer.TextRenderer;
import com.vaadin.flow.router.Route;
import io.jmix.core.EntityStates;
import io.jmix.core.Messages;
import io.jmix.core.MetadataTools;
import io.jmix.core.SaveContext;
import io.jmix.core.metamodel.datatype.DatatypeFormatter;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.Fragments;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.component.datepicker.TypedDatePicker;
import io.jmix.flowui.component.grid.DataGrid;
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
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The document card. A new version, a restored version and a status transition wait for OK
 * together with the edited fields, and are saved by one service call.
 */
@Route(value = "documents/:id", layout = MainView.class)
@ViewController(id = "Document.detail")
@ViewDescriptor(path = "document-detail-view.xml")
@EditedEntityContainer("documentDc")
@DialogMode(width = "76em", height = "52em", resizable = true)
public class DocumentDetailView extends StandardDetailView<Document> {

    @ViewComponent
    private H3 documentTitle;
    @ViewComponent
    private Badge statusBadge;
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
    private Tab versionsTab;

    @ViewComponent
    private DocumentPreviewFragment preview;
    @ViewComponent
    private DataGrid<DocumentRevision> revisionsGrid;
    @ViewComponent
    private CollectionContainer<DocumentRevision> revisionsDc;

    @ViewComponent
    private MessageBundle messageBundle;

    @Autowired
    private DocumentService documentService;
    @Autowired
    private FolderService folderService;
    @Autowired
    private TemporaryStorage temporaryStorage;
    @Autowired
    private FilesProperties filesProperties;
    @Autowired
    private FileSizeFormatter fileSizeFormatter;
    @Autowired
    private DatatypeFormatter datatypeFormatter;
    @Autowired
    private EntityStates entityStates;
    @Autowired
    private MetadataTools metadataTools;
    @Autowired
    private Messages messages;
    @Autowired
    private Notifications notifications;
    @Autowired
    private Dialogs dialogs;
    @Autowired
    private Fragments fragments;
    @Autowired
    private RevisionDownloader revisionDownloader;

    private DocumentCardChanges changes;

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
        showDocument();
        preview.showRevision(getEditedEntity().getCurrentRevision());
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
        return revision != null && !isArchived() && !revision.equals(getEditedEntity().getCurrentRevision());
    }

    @Supply(to = "revisionsGrid.fileSize", subject = "renderer")
    private Renderer<DocumentRevision> revisionSizeRenderer() {
        return new TextRenderer<>(revision -> fileSizeFormatter.format(revision.getFileSize()));
    }

    // a version opens over the card, so showing it does not replace the card's own preview
    private void openRevision(DocumentRevision revision) {
        DocumentPreviewFragment revisionPreview = fragments.create(this, DocumentPreviewFragment.class);
        revisionPreview.showRevision(revision);

        dialogs.createMessageDialog()
                .withHeader(messageBundle.formatMessage("revisionDialog.header",
                        revision.getNumber(), revision.getFileName()))
                .withContent(revisionPreview)
                .withClassName("revision-dialog")
                .withWidth("56em")
                .withHeight("40em")
                .withResizable(true)
                .withDraggable(true)
                .open();
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
    }

    /**
     * Fills what stays the same while the card is open; what waits for OK is shown by {@link #afterStaging()}.
     */
    private void showDocument() {
        Document document = getEditedEntity();
        DocumentStatus status = document.getStatus();
        boolean archived = isArchived();

        updateTitle();
        statusBadge.addClassName(statusColor(status));

        nameField.setReadOnly(archived);
        documentDateField.setReadOnly(archived);
        newVersionUpload.setVisible(!archived);

        if (isNew() && newVersionUpload.getUploadButton() instanceof Button uploadButton) {
            uploadButton.setText(messageBundle.getMessage("uploadFile"));
        }

        downloadButton.setVisible(document.getCurrentRevision() != null);

        activateButton.setVisible(status == DocumentStatus.DRAFT);
        archiveButton.setVisible(status == DocumentStatus.ACTIVE);
        unarchiveButton.setVisible(archived);

        folderField.setValue(folderService.getPath(document.getFolder()).stream()
                .map(metadataTools::getInstanceName)
                .collect(Collectors.joining(" / ")));

        DocumentRevision current = document.getCurrentRevision();
        currentVersionField.setValue(current == null
                ? ""
                : "%d · %s · %s".formatted(current.getNumber(), current.getFile().getFileName(),
                fileSizeFormatter.format(current.getFileSize())));

        createdField.setValue(describeChange(document.getCreatedBy(), document.getCreatedDate()));
        modifiedField.setValue(describeChange(document.getLastModifiedBy(), document.getLastModifiedDate()));
        versionsTab.setLabel(messageBundle.formatMessage("versionsTab.label", revisionsDc.getItems().size()));
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
        return user == null ? "" : "%s, %s".formatted(user, datatypeFormatter.formatOffsetDateTime(date));
    }

    /**
     * The Aura color class of a status. Aura has no neutral badge variant, so all three use its color classes.
     */
    private static String statusColor(DocumentStatus status) {
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
