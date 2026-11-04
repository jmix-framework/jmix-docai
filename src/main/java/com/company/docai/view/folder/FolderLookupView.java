package com.company.docai.view.folder;

import com.company.docai.app.service.access.AccessLevels;
import com.company.docai.app.service.folder.FolderIndex;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.app.service.folder.FolderTree;
import com.company.docai.model.access.AccessLevel;
import com.company.docai.model.folder.Folder;
import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.data.renderer.Renderer;
import io.jmix.core.LoadContext;
import io.jmix.flowui.component.grid.TreeDataGrid;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.view.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

/**
 * Picks the destination of a move. The moved folders and their subfolders are hidden: a folder cannot move
 * into itself. Only a folder the user may change can be picked; the others are greyed. The filter finds
 * the other folders by name.
 */
@ViewController(id = "Folder.lookup")
@ViewDescriptor(path = "folder-lookup-view.xml")
@LookupComponent("foldersTree")
@DialogMode(width = "32em", height = "36em")
public class FolderLookupView extends StandardListView<Folder> {

    @ViewComponent
    private CollectionContainer<Folder> foldersDc;
    @ViewComponent
    private TreeDataGrid<Folder> foldersTree;

    @Autowired
    private FolderService folderService;
    @Autowired
    private FolderRenderers folderRenderers;
    @Autowired
    private FolderFilter folderFilter;

    private Set<Folder> hiddenFolders = Set.of();
    private List<Folder> availableFolders = List.of();
    // nothing can be picked until the folders load
    private AccessLevels levels = new AccessLevels(Map.of(), false);

    /**
     * Hides the folders and their subfolders. Call it before the view opens: the tree is filtered on load.
     */
    public void setHiddenFolders(Collection<Folder> folders) {
        hiddenFolders = Set.copyOf(folders);
    }

    @Install(to = "foldersDl", target = Target.DATA_LOADER)
    private List<Folder> foldersDlLoadDelegate(final LoadContext<Folder> loadContext) {
        FolderTree tree = folderService.loadTree();
        levels = tree.levels();
        return tree.folders();
    }

    @Supply(to = "foldersTree.name", subject = "renderer")
    private Renderer<Folder> foldersTreeNameRenderer() {
        return folderRenderers.treeNameRenderer(foldersTree, folder -> !isEditable(folder));
    }

    @Install(to = "selectAction", subject = "enabledRule")
    private boolean selectActionEnabledRule() {
        Folder folder = foldersTree.getSingleSelectedItem();
        return folder != null && isEditable(folder);
    }

    @Subscribe(id = "foldersDl", target = Target.DATA_LOADER)
    public void onFoldersDlPostLoad(final CollectionLoader.PostLoadEvent<Folder> event) {
        FolderIndex index = FolderIndex.of(foldersDc.getItems());

        List<Folder> hidden = foldersDc.getItems()
                .stream()
                .filter(folder -> isHidden(folder, index))
                .toList();

        foldersDc.getMutableItems().removeAll(hidden);
        availableFolders = List.copyOf(foldersDc.getItems());

        foldersDc.getItems().stream()
                .filter(Folder::isRoot)
                .findFirst()
                .ifPresent(foldersTree::expand);
    }

    @Subscribe("folderFilterField")
    public void onFolderFilterFieldComponentValueChange(
            final AbstractField.ComponentValueChangeEvent<TypedTextField<String>, String> event) {
        folderFilter.filterTree(foldersTree, foldersDc, availableFolders, event.getValue());
    }

    private boolean isEditable(Folder folder) {
        return levels.allows(folder.getAccessSet(), AccessLevel.EDIT);
    }

    private boolean isHidden(Folder folder, FolderIndex index) {
        return index.pathOf(folder)
                .stream()
                .anyMatch(hiddenFolders::contains);
    }
}
