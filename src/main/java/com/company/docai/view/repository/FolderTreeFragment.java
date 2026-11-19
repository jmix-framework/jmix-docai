package com.company.docai.view.repository;

import com.company.docai.app.service.access.AccessLevels;
import com.company.docai.app.service.folder.FolderContents;
import com.company.docai.app.service.folder.FolderIndex;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.app.service.folder.FolderTree;
import com.company.docai.model.access.AccessLevel;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.view.favorite.FavoritesFragment;
import com.company.docai.view.folder.FolderDetailView;
import com.company.docai.view.folder.FolderFilter;
import com.company.docai.view.folder.FolderRenderers;
import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.dnd.DropEvent;
import com.vaadin.flow.component.dnd.DropTarget;
import com.vaadin.flow.component.grid.CellFocusEvent;
import com.vaadin.flow.component.grid.dnd.GridDragEndEvent;
import com.vaadin.flow.component.grid.dnd.GridDragStartEvent;
import com.vaadin.flow.component.grid.dnd.GridDropEvent;
import com.vaadin.flow.component.grid.dnd.GridDropMode;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.renderer.Renderer;
import com.vaadin.flow.data.selection.SelectionEvent;
import com.vaadin.flow.function.SerializablePredicate;
import com.vaadin.flow.shared.Registration;
import io.jmix.core.LoadContext;
import io.jmix.core.MetadataTools;
import io.jmix.flowui.DialogWindows;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.component.SupportsEnterPress;
import io.jmix.flowui.component.details.JmixDetails;
import io.jmix.flowui.component.grid.TreeDataGrid;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.fragment.Fragment;
import io.jmix.flowui.fragment.FragmentDescriptor;
import io.jmix.flowui.fragment.FragmentUtils;
import io.jmix.flowui.icon.Icons;
import io.jmix.flowui.kit.action.Action;
import io.jmix.flowui.kit.action.ActionPerformedEvent;
import io.jmix.flowui.kit.action.ActionVariant;
import io.jmix.flowui.kit.icon.JmixFontIcon;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.view.*;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The folder tree of the repository with the folder actions, the folder filter and the document counts. It stands in
 * the main menu, so it is the same on every view; the repository plugs its own handlers in while it is shown.
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
    @ViewComponent("foldersTree.createFolderAction")
    private Action createFolderAction;
    @ViewComponent("foldersTree.accessFolderAction")
    private Action accessFolderAction;
    @ViewComponent("foldersTree.favoriteAction")
    private Action favoriteAction;
    @ViewComponent
    private FavoritesFragment favorites;
    @ViewComponent
    private JmixDetails favoritesDetails;
    @ViewComponent
    private VerticalLayout treeFrame;

    @ViewComponent
    private MessageBundle messageBundle;

    @Autowired
    private FolderService folderService;
    @Autowired
    private RepositoryMoves repositoryMoves;
    @Autowired
    private DialogWindows dialogWindows;
    @Autowired
    private Dialogs dialogs;
    @Autowired
    private Notifications notifications;
    @Autowired
    private MetadataTools metadataTools;
    @Autowired
    private FolderRenderers folderRenderers;
    @Autowired
    private FolderFilter folderFilter;
    @Autowired
    private UiComponents uiComponents;
    @Autowired
    private Icons icons;

    private FolderTree tree = new FolderTree(List.of(), Set.of(), new AccessLevels(Map.of(), false));
    private FolderIndex allFolders = FolderIndex.of(List.of());
    private Map<UUID, Long> documentCounts = Map.of();
    @Nullable
    private Folder selectedFolder;
    @Nullable
    private Folder focusedFolder;
    private List<Document> draggedDocuments = List.of();
    @Nullable
    private Folder draggedFolder;
    @Nullable
    private SerializablePredicate<Folder> dropTargets;

    private Consumer<Folder> selectionHandler = folder -> {
    };
    private BiConsumer<List<Document>, Folder> documentsDropHandler = (documents, target) -> {
    };
    private final List<Consumer<Folder>> selectionListeners = new ArrayList<>();

    /**
     * Receives the folder selected in the tree.
     */
    public void setSelectionHandler(Consumer<Folder> selectionHandler) {
        this.selectionHandler = selectionHandler;
    }

    /**
     * Receives the documents dropped on a folder of the tree, together with the folder.
     */
    public void setDocumentsDropHandler(BiConsumer<List<Document>, Folder> documentsDropHandler) {
        this.documentsDropHandler = documentsDropHandler;
    }

    /**
     * Offers the folders that may take the dragged documents; an empty list ends the offer.
     */
    public void setDraggedDocuments(List<Document> documents) {
        draggedDocuments = List.copyOf(documents);

        if (documents.isEmpty()) {
            stopDrop();
        } else {
            startDrop(target -> allows(target, AccessLevel.EDIT) && !target.equals(selectedFolder));
        }
    }

    /**
     * Tells about every folder chosen in the tree, after the selection handler, whoever plugged it in.
     */
    public Registration addSelectionListener(Consumer<Folder> listener) {
        selectionListeners.add(listener);
        return () -> selectionListeners.remove(listener);
    }

    /**
     * Reloads the tree and selects the folder, or, when it is gone, the nearest one above it the tree still shows.
     */
    public void loadAndSelect(@Nullable UUID folderId) {
        List<Folder> way = folderId == null || !allFolders.contains(folderId)
                ? List.of()
                : getPath(allFolders.get(folderId)).reversed();

        // the folder is selected below, once allFolders is rebuilt: neither the reload nor clearing the filter
        // may select the previous one, or the breadcrumbs would show its old path
        clearSelection();
        folderFilterField.clear();
        load();

        Folder folder = allFolders.get(folderId == null ? Folder.ROOT_ID : folderId);
        if (folder == null) {
            notifications.create(messageBundle.getMessage("folderUnavailable"))
                    .withType(Notifications.Type.WARNING)
                    .show();

            folder = way.stream()
                    .map(step -> allFolders.get(step.getId()))
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(allFolders.get(Folder.ROOT_ID));
        }

        select(folder);
        favorites.reload();
    }

    /**
     * Loads the folders and their document counts without choosing one, the root open.
     */
    public void load() {
        foldersDl.load();
        allFolders = FolderIndex.of(foldersDc.getItems());

        documentCounts = folderService.countDocuments(allFolders.folders());

        String filter = folderFilterField.getValue();

        if (filter != null && !filter.isBlank()) {
            applyFilter(filter);
        }

        foldersTree.expand(allFolders.get(Folder.ROOT_ID));
    }

    /**
     * Forgets the shown folder: outside the repository no folder is the current one.
     */
    public void clearSelection() {
        foldersTree.deselectAll();
        selectedFolder = null;
    }

    /**
     * Counts the documents again after documents were added, moved or deleted.
     */
    public void refreshDocumentCounts() {
        documentCounts = folderService.countDocuments(allFolders.folders());
        foldersTree.getDataProvider().refreshAll();
    }

    /**
     * Shows again what the folder menu offers for the selected folder: after a mark set or removed outside the tree.
     */
    public void refreshFavorites() {
        favoriteAction.refreshState();
    }

    /**
     * Selects the folder and scrolls to it, the folders above it open.
     */
    public void select(Folder folder) {
        List<Folder> path = getPath(folder);
        foldersTree.expand(path.size() == 1 ? path : path.subList(0, path.size() - 1));
        foldersTree.select(folder);
        foldersTree.scrollToItem(folder);
    }

    /**
     * The folder whose documents are shown, also while the filter hides it.
     */
    @Nullable
    public Folder getSelectedFolder() {
        return selectedFolder;
    }

    /**
     * The favorites of the user, the first section in the frame of the tree, above the folders.
     */
    public FavoritesFragment getFavorites() {
        return favorites;
    }

    /**
     * Puts a component under the folders, where it scrolls with them.
     */
    public void addUnderFolders(Component component) {
        treeFrame.add(component);
    }

    /**
     * Tells whether the folder is shown only as the way to folders and documents the user has access to.
     */
    public boolean isGreyed(Folder folder) {
        return tree.greyed().contains(folder.getId());
    }

    /**
     * The folders from the root down to the given one.
     */
    public List<Folder> getPath(Folder folder) {
        return allFolders.pathOf(folder);
    }

    @Install(to = "foldersDl", target = Target.DATA_LOADER)
    private List<Folder> foldersDlLoadDelegate(final LoadContext<Folder> loadContext) {
        tree = folderService.loadTree();
        return tree.folders();
    }

    @Subscribe
    public void onReady(final ReadyEvent event) {
        Span summary = uiComponents.create(Span.class);
        summary.addClassName("name-with-icon");
        summary.add(icons.get(JmixFontIcon.STAR), new Text(favoritesDetails.getSummaryText()));
        favoritesDetails.setSummary(summary);

        DropTarget.create(favoritesDetails)
                .addDropListener(this::onFavoritesDrop);

        load();
        favorites.reload();

        foldersTree.getContextMenu().addGridContextMenuOpenedListener(menu -> {
            if (menu.isOpened()) {
                menu.getItem().ifPresent(foldersTree::select);
            }
        });
    }

    @Subscribe("foldersTree")
    public void onFoldersTreeSelection(final SelectionEvent<TreeDataGrid<Folder>, Folder> event) {
        createFolderAction.refreshState();
        accessFolderAction.setVisible(accessFolderActionEnabledRule());

        Folder folder = foldersTree.getSingleSelectedItem();

        // the filter makes the tree select the shown folder again, and its documents are on screen already
        if (folder != null && !folder.equals(selectedFolder)) {
            selectedFolder = folder;
            selectionHandler.accept(folder);
            selectionListeners.forEach(listener -> listener.accept(folder));
        }
    }

    @Subscribe("foldersTree")
    public void onFoldersTreeCellFocus(final CellFocusEvent<Folder> event) {
        focusedFolder = event.getItem().orElse(null);
    }

    @Install(to = "foldersTree", subject = "enterPressHandler")
    private void foldersTreeEnterPressHandler(final SupportsEnterPress.EnterPressEvent<TreeDataGrid<Folder>> event) {
        // the keys move only the focus, and a folder opens as it is selected, so Enter selects it as Space does
        if (focusedFolder != null && allFolders.contains(focusedFolder.getId())) {
            foldersTree.select(allFolders.get(focusedFolder.getId()));
        }
    }

    @Subscribe("foldersTree")
    public void onFoldersTreeDragStart(final GridDragStartEvent<Folder> event) {
        Folder folder = event.getDraggedItems().getFirst();

        if (!allows(folder, AccessLevel.EDIT)) {
            return;
        }

        Folder parent = allFolders.parentOf(folder);
        draggedFolder = folder;

        startDrop(target -> allows(target, AccessLevel.EDIT)
                && !target.equals(parent)
                && !target.equals(folder)
                && !allFolders.isBelow(target, folder));
    }

    @Subscribe("foldersTree")
    public void onFoldersTreeDragEnd(final GridDragEndEvent<Folder> event) {
        draggedFolder = null;
        stopDrop();
    }

    @Subscribe("foldersTree")
    public void onFoldersTreeDrop(final GridDropEvent<Folder> event) {
        Folder target = event.getDropTargetItem().orElse(null);

        // the browser takes a drop only on the folders that light up, and the tree checks again
        if (target == null || dropTargets == null || !dropTargets.test(target)) {
            return;
        }

        if (draggedFolder != null) {
            move(draggedFolder, target);
        } else {
            documentsDropHandler.accept(draggedDocuments, target);
        }
    }

    private void onFavoritesDrop(DropEvent<JmixDetails> event) {
        if (event.getDragData().orElse(null) instanceof Collection<?> rows) {
            List<?> targets = rows.stream()
                    .filter(row -> row instanceof Folder || row instanceof Document)
                    .toList();

            if (!targets.isEmpty()) {
                favorites.mark(targets);
                favoritesDetails.setOpened(true);
            }
        }
    }

    @Install(to = "foldersTree", subject = "dragFilter")
    private boolean foldersTreeDragFilter(final Folder folder) {
        return !folder.isRoot();
    }

    @Subscribe("folderFilterField")
    public void onFolderFilterFieldComponentValueChange(
            final AbstractField.ComponentValueChangeEvent<TypedTextField<String>, String> event) {
        boolean filtered = applyFilter(event.getValue());

        if (!filtered && selectedFolder != null) {
            select(selectedFolder);
        }
    }

    private boolean applyFilter(@Nullable String filter) {
        boolean filtered = folderFilter.filterTree(foldersTree, foldersDc, allFolders.folders(), filter);
        favorites.setFilter(filter);

        return filtered;
    }

    @Subscribe("foldersTree.createFolderAction")
    public void onCreateFolderAction(final ActionPerformedEvent event) {
        Folder parent = parentForNew();
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
        repositoryMoves.chooseTarget(hostView(), List.of(folder), target -> move(folder, target));
    }

    @Install(to = "foldersTree.accessFolderAction", subject = "afterSaveHandler")
    private void foldersTreeAccessFolderActionAfterSaveHandler(final Folder folder) {
        loadAndSelect(folder.getId());
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
                        new DialogAction(DialogAction.Type.YES)
                                .withHandler(actionEvent -> repositoryMoves.moveToTrash(folder,
                                        () -> loadAndSelect(folder.getParent().getId()),
                                        () -> loadAndSelect(folder.getId()))),
                        new DialogAction(DialogAction.Type.NO).withVariant(ActionVariant.PRIMARY))
                .open();
    }

    @Supply(to = "foldersTree.name", subject = "renderer")
    private Renderer<Folder> foldersTreeNameRenderer() {
        return folderRenderers.treeNameRenderer(foldersTree, this::isGreyed, this::showsOwnAccess,
                folder -> documentCounts.getOrDefault(folder.getId(), 0L));
    }

    @Install(to = "foldersTree.createFolderAction", subject = "enabledRule")
    private boolean createFolderActionEnabledRule() {
        return allows(parentForNew(), AccessLevel.EDIT);
    }

    @Install(to = "foldersTree.renameFolderAction", subject = "enabledRule")
    private boolean renameFolderActionEnabledRule() {
        return isNotRootSelected() && allows(foldersTree.getSingleSelectedItem(), AccessLevel.EDIT);
    }

    @Install(to = "foldersTree.moveFolderAction", subject = "enabledRule")
    private boolean moveFolderActionEnabledRule() {
        return isNotRootSelected() && allows(foldersTree.getSingleSelectedItem(), AccessLevel.EDIT);
    }

    @Install(to = "foldersTree.accessFolderAction", subject = "enabledRule")
    private boolean accessFolderActionEnabledRule() {
        return allows(foldersTree.getSingleSelectedItem(), AccessLevel.MANAGE);
    }

    @Install(to = "foldersTree.favoriteAction", subject = "afterToggleHandler")
    private void foldersTreeFavoriteActionAfterToggleHandler() {
        favorites.reload();
    }

    @Install(to = "foldersTree.favoriteAction", subject = "enabledRule")
    private boolean favoriteActionEnabledRule() {
        Folder folder = foldersTree.getSingleSelectedItem();
        return folder != null && !folder.isRoot() && !isGreyed(folder);
    }

    @Install(to = "foldersTree.deleteFolderAction", subject = "enabledRule")
    private boolean deleteFolderActionEnabledRule() {
        return isNotRootSelected() && allows(foldersTree.getSingleSelectedItem(), AccessLevel.EDIT);
    }

    // the actions refresh before onFoldersTreeSelection runs, so they read the tree's selection, not selectedFolder
    private boolean isNotRootSelected() {
        Folder folder = foldersTree.getSingleSelectedItem();
        return folder != null && !folder.isRoot();
    }

    // a new folder goes into the selected folder; while the filter hides it, into the shown one
    @Nullable
    private Folder parentForNew() {
        Folder selected = foldersTree.getSingleSelectedItem();
        Folder folder = selected != null ? selected : selectedFolder;
        return folder != null ? folder : allFolders.get(Folder.ROOT_ID);
    }

    private boolean allows(@Nullable Folder folder, AccessLevel level) {
        return folder != null && tree.levels().allows(folder.getAccessSet(), level);
    }

    private boolean showsOwnAccess(Folder folder) {
        Folder parent = allFolders.parentOf(folder);

        return parent != null
                && !folder.getAccessSet().equals(parent.getAccessSet())
                && allows(folder, AccessLevel.MANAGE);
    }

    // the filter of the drop depends on what is dragged, so the tree takes drops only while something is dragged
    private void startDrop(SerializablePredicate<Folder> targets) {
        dropTargets = targets;
        foldersTree.setDropFilter(targets);
        foldersTree.setDropMode(GridDropMode.ON_TOP);
    }

    private void stopDrop() {
        dropTargets = null;
        foldersTree.setDropMode(null);
    }

    private void move(Folder folder, Folder target) {
        repositoryMoves.move(folder, target, () -> loadAndSelect(folder.getId()));
    }

    private View<?> hostView() {
        return FragmentUtils.getHostView(this);
    }
}
