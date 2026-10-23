package com.company.dms.view.repository;

import com.company.dms.app.service.folder.FolderContents;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.model.folder.Folder;
import com.company.dms.view.folder.FolderDetailView;
import com.company.dms.view.folder.FolderLookupView;
import com.company.dms.view.folder.FolderRenderers;
import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.renderer.Renderer;
import com.vaadin.flow.data.selection.SelectionEvent;
import io.jmix.core.MetadataTools;
import io.jmix.flowui.DialogWindows;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.component.grid.TreeDataGrid;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.fragment.Fragment;
import io.jmix.flowui.fragment.FragmentDescriptor;
import io.jmix.flowui.fragment.FragmentUtils;
import io.jmix.flowui.kit.action.ActionPerformedEvent;
import io.jmix.flowui.kit.action.ActionVariant;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.view.DialogWindow;
import io.jmix.flowui.view.Install;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.StandardOutcome;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.Supply;
import io.jmix.flowui.view.View;
import io.jmix.flowui.view.ViewComponent;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The folder tree of the repository with the folder actions, the folder filter and the document counts.
 */
@FragmentDescriptor("folder-tree-fragment.xml")
public class FolderTreeFragment extends Fragment<VerticalLayout> {

    @ViewComponent
    private TreeDataGrid<Folder> foldersTree;
    @ViewComponent
    private TypedTextField<String> folderFilterField;
    @ViewComponent
    private CollectionContainer<Folder> foldersDc;
    @ViewComponent
    private CollectionLoader<Folder> foldersDl;

    @ViewComponent
    private MessageBundle messageBundle;

    @Autowired
    private FolderService folderService;
    @Autowired
    private DialogWindows dialogWindows;
    @Autowired
    private Dialogs dialogs;
    @Autowired
    private MetadataTools metadataTools;
    @Autowired
    private FolderRenderers folderRenderers;

    private Map<UUID, Folder> allFolders = Map.of();
    private Map<UUID, Long> documentCounts = Map.of();
    @Nullable
    private Folder selectedFolder;
    private Consumer<Folder> selectionHandler = folder -> {
    };

    /**
     * Receives the folder selected in the tree.
     */
    public void setSelectionHandler(Consumer<Folder> selectionHandler) {
        this.selectionHandler = selectionHandler;
    }

    /**
     * Reloads the tree and selects the folder, or the root if the folder is gone. The filter is cleared,
     * so the folder is in sight.
     */
    public void loadAndSelect(@Nullable UUID folderId) {
        // the folder is selected below, so clearing the filter must not select the previous one
        selectedFolder = null;
        folderFilterField.clear();

        foldersDl.load();
        allFolders = foldersDc.getItems().stream()
                .collect(Collectors.toMap(Folder::getId,
                        Function.identity(),
                        (first, second) -> first,
                        LinkedHashMap::new));

        documentCounts = folderService.countDocuments(allFolders.values());

        Folder folder = folderId == null ? null : allFolders.get(folderId);
        select(folder != null ? folder : allFolders.get(Folder.ROOT_ID));
    }

    /**
     * Counts the documents again after documents were added, moved or deleted.
     */
    public void refreshDocumentCounts() {
        documentCounts = folderService.countDocuments(allFolders.values());
        foldersTree.getDataProvider().refreshAll();
    }

    /**
     * Selects the folder and scrolls to it. The folders above it open, and the root is open even when it is
     * the selected one.
     */
    public void select(Folder folder) {
        List<Folder> path = getPath(folder);
        foldersTree.expand(path.size() == 1 ? path : path.subList(0, path.size() - 1));
        foldersTree.select(folder);
        foldersTree.scrollToItem(folder);
    }

    /**
     * The folder whose documents are shown. It stays selected while the filter hides it from the tree.
     */
    @Nullable
    public Folder getSelectedFolder() {
        return selectedFolder;
    }

    /**
     * The folders from the root down to the given one.
     */
    public List<Folder> getPath(Folder folder) {
        LinkedList<Folder> path = new LinkedList<>();
        for (Folder current = allFolders.get(folder.getId()); current != null; current = parentOf(current)) {
            path.addFirst(current);
        }

        return path;
    }

    @Subscribe("foldersTree")
    public void onFoldersTreeSelection(final SelectionEvent<TreeDataGrid<Folder>, Folder> event) {
        Folder folder = foldersTree.getSingleSelectedItem();

        if (folder != null) {
            selectedFolder = folder;
            selectionHandler.accept(folder);
        }
    }

    @Subscribe("folderFilterField")
    public void onFolderFilterFieldComponentValueChange(
            final AbstractField.ComponentValueChangeEvent<TypedTextField<String>, String> event) {
        foldersDc.setItems(folderService.filterByName(allFolders.values(), event.getValue()));

        if (event.getValue() != null && !event.getValue().isBlank()) {
            foldersTree.expand(foldersDc.getItems());
        } else if (selectedFolder != null) {
            // a cleared filter brings back the folder whose documents are shown
            select(selectedFolder);
        }
    }

    @Subscribe("expandAllButton")
    public void onExpandAllButtonClick(final ClickEvent<JmixButton> event) {
        foldersTree.expand(foldersDc.getItems());
    }

    // the root stays open, as everywhere in the tree
    @Subscribe("collapseAllButton")
    public void onCollapseAllButtonClick(final ClickEvent<JmixButton> event) {
        foldersTree.collapse(foldersDc.getItems().stream()
                .filter(folder -> !folder.isRoot())
                .toList());
    }

    @Subscribe("foldersTree.createFolderAction")
    public void onCreateFolderAction(final ActionPerformedEvent event) {
        Folder parent = getSelectedFolder() != null ? getSelectedFolder() : allFolders.get(Folder.ROOT_ID);
        dialogWindows.detail(hostView(), Folder.class)
                .withViewClass(FolderDetailView.class)
                .newEntity()
                .withInitializer(folder -> folder.setParent(parent))
                .withAfterCloseListener(closeEvent -> {
                    if (closeEvent.closedWith(StandardOutcome.SAVE)) {
                        loadAndSelect(closeEvent.getView().getEditedEntity().getId());
                    }
                })
                .open();
    }

    @Subscribe("foldersTree.renameFolderAction")
    public void onRenameFolderAction(final ActionPerformedEvent event) {
        Folder folder = getSelectedFolder();
        dialogWindows.detail(hostView(), Folder.class)
                .withViewClass(FolderDetailView.class)
                .editEntity(folder)
                .withAfterCloseListener(closeEvent -> {
                    if (closeEvent.closedWith(StandardOutcome.SAVE)) {
                        loadAndSelect(folder.getId());
                    }
                })
                .open();
    }

    @Subscribe("foldersTree.moveFolderAction")
    public void onMoveFolderAction(final ActionPerformedEvent event) {
        Folder folder = getSelectedFolder();
        DialogWindow<FolderLookupView> window = dialogWindows.lookup(hostView(), Folder.class)
                .withViewClass(FolderLookupView.class)
                .withSelectHandler(targets -> {
                    folderService.move(folder, targets.iterator().next());
                    loadAndSelect(folder.getId());
                })
                .build();

        window.getView().setHiddenFolders(List.of(folder));
        window.open();
    }

    @Subscribe("foldersTree.deleteFolderAction")
    public void onDeleteFolderAction(final ActionPerformedEvent event) {
        Folder folder = getSelectedFolder();
        FolderContents contents = folderService.countContents(folder);

        dialogs.createOptionDialog()
                .withHeader(messageBundle.getMessage("deleteFolderDialog.header"))
                .withText(messageBundle.formatMessage("deleteFolderDialog.text",
                        metadataTools.getInstanceName(folder), contents.subfolders(), contents.documents()))
                .withActions(
                        new DialogAction(DialogAction.Type.YES).withHandler(actionEvent -> {
                            folderService.delete(folder);
                            loadAndSelect(folder.getParent().getId());
                        }),
                        new DialogAction(DialogAction.Type.NO).withVariant(ActionVariant.PRIMARY))
                .open();
    }

    @Supply(to = "foldersTree.name", subject = "renderer")
    private Renderer<Folder> foldersTreeNameRenderer() {
        return folderRenderers.treeNameRenderer(foldersTree, folder -> documentCounts.getOrDefault(folder.getId(), 0L));
    }

    @Install(to = "foldersTree.renameFolderAction", subject = "enabledRule")
    private boolean renameFolderActionEnabledRule() {
        return isNotRootSelected();
    }

    @Install(to = "foldersTree.moveFolderAction", subject = "enabledRule")
    private boolean moveFolderActionEnabledRule() {
        return isNotRootSelected();
    }

    @Install(to = "foldersTree.deleteFolderAction", subject = "enabledRule")
    private boolean deleteFolderActionEnabledRule() {
        return isNotRootSelected();
    }

    // the actions refresh before onFoldersTreeSelection runs, so they read the tree's selection, not selectedFolder
    private boolean isNotRootSelected() {
        Folder folder = foldersTree.getSingleSelectedItem();
        return folder != null && !folder.isRoot();
    }

    @Nullable
    private Folder parentOf(Folder folder) {
        return folder.getParent() == null ? null : allFolders.get(folder.getParent().getId());
    }

    private View<?> hostView() {
        return FragmentUtils.getHostView(this);
    }
}
