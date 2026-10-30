package com.company.docai.view.folder;

import com.company.docai.app.service.access.AccessLevels;
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
import java.util.function.Function;
import java.util.stream.Collectors;

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
        Map<UUID, Folder> byId = foldersDc.getItems().stream()
                .collect(Collectors.toMap(Folder::getId, Function.identity()));

        List<Folder> hidden = foldersDc.getItems().stream()
                .filter(folder -> isHidden(folder, byId))
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
        foldersDc.setItems(folderService.filterByName(availableFolders, event.getValue()));

        if (event.getValue() != null && !event.getValue().isBlank()) {
            foldersTree.expand(foldersDc.getItems());
        }
    }

    private boolean isEditable(Folder folder) {
        return levels.allows(folder.getAccessSet(), AccessLevel.EDIT);
    }

    private boolean isHidden(Folder folder, Map<UUID, Folder> byId) {
        for (Folder current = folder; current != null;
             current = current.getParent() == null ? null : byId.get(current.getParent().getId())) {
            if (hiddenFolders.contains(current)) {
                return true;
            }
        }

        return false;
    }
}
