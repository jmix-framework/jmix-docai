package com.company.docai.view.folder;

import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.folder.Folder;
import io.jmix.core.EntityStates;
import io.jmix.core.SaveContext;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.Install;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.Target;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Set;

/**
 * Names a new folder or renames one; an existing folder changes its parent only by a move.
 */
@ViewController(id = "Folder.detail")
@ViewDescriptor(path = "folder-detail-view.xml")
@EditedEntityContainer("folderDc")
@DialogMode(width = "28em")
public class FolderDetailView extends StandardDetailView<Folder> {

    @ViewComponent
    private TypedTextField<String> parentField;

    @Autowired
    private FolderService folderService;
    @Autowired
    private EntityStates entityStates;
    @Autowired
    private FolderRenderers folderRenderers;

    @Subscribe
    public void onReady(final ReadyEvent event) {
        if (!entityStates.isNew(getEditedEntity())) {
            parentField.setVisible(false);
            return;
        }

        parentField.setValue(folderRenderers.pathText(folderService.getPath(getEditedEntity().getParent())));
    }

    @Install(target = Target.DATA_CONTEXT)
    private Set<Object> saveDelegate(final SaveContext saveContext) {
        return Set.of(folderService.save(getEditedEntity()));
    }
}
