package com.company.docai.view.repository;

import com.company.docai.model.folder.Folder;
import com.company.docai.view.document.FileSizeFormatter;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.upload.UploadButton;
import com.vaadin.flow.component.upload.UploadManager;
import io.jmix.core.MetadataTools;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.view.DialogModalityMode;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.NavigateCloseAction;
import io.jmix.flowui.view.StandardOutcome;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The dialog of the upload button of the document list: a large zone for the files dragged from the computer and a
 * button that picks them. Both hand the files to the upload manager of the list, so the files travel as the files
 * dropped on the table do, and the list closes the dialog as soon as they start to arrive. The dialog dims the page
 * only for the eye: a strictly modal dialog takes in the notices opened over it, so the notice of a file the manager
 * refuses would leave with the dialog as the other files of the drop start to arrive.
 */
@ViewController(id = "DocumentUploadView")
@ViewDescriptor(path = "document-upload-view.xml")
@DialogMode(width = "36em", modality = DialogModalityMode.VISUAL, closeOnEsc = true)
public class DocumentUploadView extends StandardView {

    @ViewComponent
    private Span folderText;
    @ViewComponent
    private VerticalLayout dropContent;
    @ViewComponent
    private Span limitsText;
    @ViewComponent
    private MessageBundle messageBundle;

    @Autowired
    private UiComponents uiComponents;
    @Autowired
    private MetadataTools metadataTools;
    @Autowired
    private FileSizeFormatter fileSizeFormatter;

    private FileDropZone dropZone;
    private UploadButton chooseFilesButton;

    @Subscribe
    public void onInit(final InitEvent event) {
        configureChooseFilesButton();
        configureDropZone();

        // the manager keeps every component linked to it and outlives the dialog, so the dialog lets go of it when
        // it leaves the page; on its close the zone and the button would grey out while the dialog fades away
        addDetachListener(detachEvent -> {
            dropZone.setUploadManager(null);
            chooseFilesButton.setUploadManager(null);
        });
    }

    private void configureChooseFilesButton() {
        chooseFilesButton = uiComponents.create(UploadButton.class);
        chooseFilesButton.setId("chooseFilesButton");
        chooseFilesButton.setText(messageBundle.getMessage("documentUploadView.chooseFiles"));
        chooseFilesButton.addThemeVariants(ButtonVariant.PRIMARY);
        dropContent.add(chooseFilesButton);
    }

    private void configureDropZone() {
        dropZone = uiComponents.create(FileDropZone.class);
        dropZone.setId("dropZone");
        dropZone.addClassName("document-upload-zone");
        dropZone.setWidthFull();
        getContent().replace(dropContent, dropZone);
        dropZone.setContent(dropContent);
    }

    // a navigation, such as the back button of the browser, closes the view, but the dialog it lives in would stay
    @Subscribe
    public void onAfterClose(final AfterCloseEvent event) {
        if (event.getCloseAction() instanceof NavigateCloseAction) {
            close(StandardOutcome.CLOSE);
        }
    }

    /**
     * Names the folder the files go to.
     */
    public void setFolder(Folder folder) {
        String name = metadataTools.getInstanceName(folder);
        folderText.setText(messageBundle.formatMessage("documentUploadView.folder", name));
    }

    /**
     * Hands the files dropped into the zone or picked with the button to the manager, and shows its limits: how many
     * files it takes at a time and how large each of them may be.
     */
    public void setUploadManager(UploadManager manager) {
        dropZone.setUploadManager(manager);
        chooseFilesButton.setUploadManager(manager);

        int filesAtATime = manager.getMaxFiles();
        String largestFile = fileSizeFormatter.format(manager.getMaxFileSize());
        limitsText.setText(messageBundle.formatMessage("documentUploadView.limits", filesAtATime, largestFile));
    }
}
