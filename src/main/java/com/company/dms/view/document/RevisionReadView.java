package com.company.dms.view.document;

import com.company.dms.model.document.DocumentRevision;
import io.jmix.flowui.model.InstanceContainer;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.ReadEntityContainer;
import io.jmix.flowui.view.StandardReadView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.Target;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

/**
 * A version of a document, opened from the versions of the document card in a dialog that can be maximized.
 */
@ViewController(id = "DocumentRevision.read")
@ViewDescriptor(path = "revision-read-view.xml")
@ReadEntityContainer("revisionDc")
@DialogMode(width = "56em", height = "40em", resizable = true, maximizable = true, closeOnEsc = true)
public class RevisionReadView extends StandardReadView<DocumentRevision> {

    @ViewComponent
    private DocumentPreviewFragment preview;

    @ViewComponent
    private MessageBundle messageBundle;

    @Subscribe(id = "revisionDc", target = Target.DATA_CONTAINER)
    public void onRevisionDcItemChange(final InstanceContainer.ItemChangeEvent<DocumentRevision> event) {
        DocumentRevision revision = event.getItem();
        preview.showRevision(revision);

        if (revision != null) {
            setPageTitle(messageBundle.formatMessage("revisionReadView.title", revision.getNumber(),
                    revision.getFileName()));
        }
    }
}
