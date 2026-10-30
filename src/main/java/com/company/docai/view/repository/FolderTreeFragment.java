package com.company.docai.view.repository;

import com.company.docai.app.service.access.AccessLevels;
import com.company.docai.app.service.access.AccessService;
import com.company.docai.app.service.folder.FolderContents;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.app.service.folder.FolderTree;
import com.company.docai.model.access.AccessDraft;
import com.company.docai.model.access.AccessLevel;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.view.access.AccessDetailView;
import com.company.docai.view.favorite.FavoritesFragment;
import com.company.docai.view.folder.FolderDetailView;
import com.company.docai.view.folder.FolderLookupView;
import com.company.docai.view.folder.FolderRenderers;
import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.dnd.DropEvent;
import com.vaadin.flow.component.dnd.DropTarget;
import com.vaadin.flow.component.grid.dnd.GridDragEndEvent;
import com.vaadin.flow.component.grid.dnd.GridDragStartEvent;
import com.vaadin.flow.component.grid.dnd.GridDropEvent;
import com.vaadin.flow.component.grid.dnd.GridDropMode;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.renderer.Renderer;
import com.vaadin.flow.data.selection.SelectionEvent;
import com.vaadin.flow.function.SerializablePredicate;
import io.jmix.core.LoadContext;
import io.jmix.core.MetadataTools;
import io.jmix.flowui.DialogWindows;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.action.DialogAction;
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
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.kit.icon.JmixFontIcon;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.view.*;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The folder tree of the repository with the folder actions, the folder filter, which narrows the favorites too, and
 * the document counts.
 * The folders on the way to what the user has access to are greyed, and the actions follow the user's level
 * in the selected folder. A folder is moved by dragging it onto another one, and documents are moved by dropping
 * them on a folder: while something is dragged, only the folders that may take it accept the drop. Folders and
 * documents dropped on the favorites, the first section of the frame, are marked as favorites.
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
    private MessageBundle messageBundle;

    @Autowired
    private FolderService folderService;
    @Autowired
    private AccessService accessService;
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
    private UiComponents uiComponents;
    @Autowired
    private Icons icons;

    private FolderTree tree = new FolderTree(List.of(), Set.of(), new AccessLevels(Map.of(), false));
    private Map<UUID, Folder> allFolders = Map.of();
    private Map<UUID, Long> documentCounts = Map.of();
    @Nullable
    private Folder selectedFolder;
    private List<Document> draggedDocuments = List.of();
    @Nullable
    private Folder draggedFolder;
    @Nullable
    private SerializablePredicate<Folder> dropTargets;

    private Consumer<Folder> selectionHandler = folder -> {
    };
    private BiConsumer<List<Document>, Folder> documentsDropHandler = (documents, target) -> {
    };
    private Runnable reloadHandler = () -> {
    };
    private Runnable favoritesChangeHandler = () -> {
    };

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
     * Offers the folders that may take the documents being dragged: those the user may change, except the shown
     * folder, which holds them. An empty list ends the offer.
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
     * Receives a reload of the tree: the folders may have new names and places since, and some may be gone.
     */
    public void setReloadHandler(Runnable reloadHandler) {
        this.reloadHandler = reloadHandler;
    }

    /**
     * Reloads the tree and selects the folder. A folder that is gone or no longer available to the user gives way
     * to the nearest folder above it that the tree still shows, or to the root, with a notification. The filter
     * is cleared, so the folder is in sight.
     */
    public void loadAndSelect(@Nullable UUID folderId) {
        // the way up as the tree showed it before the reload, the folder itself first
        List<Folder> way = folderId == null || !allFolders.containsKey(folderId)
                ? List.of()
                : getPath(allFolders.get(folderId)).reversed();

        // the folder is selected below, once allFolders is rebuilt: neither the reload nor clearing the filter
        // may select the previous one, or the breadcrumbs would show its old path
        foldersTree.deselectAll();
        selectedFolder = null;
        folderFilterField.clear();

        foldersDl.load();
        allFolders = foldersDc.getItems().stream()
                .collect(Collectors.toMap(Folder::getId,
                        Function.identity(),
                        (first, second) -> first,
                        LinkedHashMap::new));

        documentCounts = folderService.countDocuments(allFolders.values());

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
        reloadHandler.run();
    }

    /**
     * Counts the documents again after documents were added, moved or deleted.
     */
    public void refreshDocumentCounts() {
        documentCounts = folderService.countDocuments(allFolders.values());
        foldersTree.getDataProvider().refreshAll();
    }

    /**
     * Receives a mark set or removed on a folder of the tree.
     */
    public void setFavoritesChangeHandler(Runnable favoritesChangeHandler) {
        this.favoritesChangeHandler = favoritesChangeHandler;
    }

    /**
     * Shows again what the folder menu offers for the selected folder: after a mark set or removed outside the tree.
     */
    public void refreshFavorites() {
        favoriteAction.refreshState();
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
     * The favorites of the user, the first section in the frame of the tree, above the folders.
     */
    public FavoritesFragment getFavorites() {
        return favorites;
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
        LinkedList<Folder> path = new LinkedList<>();
        for (Folder current = allFolders.get(folder.getId()); current != null; current = parentOf(current)) {
            path.addFirst(current);
        }

        return path;
    }

    @Install(to = "foldersDl", target = Target.DATA_LOADER)
    private List<Folder> foldersDlLoadDelegate(final LoadContext<Folder> loadContext) {
        tree = folderService.loadTree();
        return tree.folders();
    }

    // the header of the favorites carries a star, as a folder of the tree carries its icon; the descriptor gives
    // a details a summary text only
    @Subscribe
    public void onReady(final ReadyEvent event) {
        Span summary = uiComponents.create(Span.class);
        summary.addClassName("name-with-icon");
        summary.add(icons.get(JmixFontIcon.STAR), new Text(favoritesDetails.getSummaryText()));
        favoritesDetails.setSummary(summary);

        // the whole section takes a drop, the header of the closed one too
        DropTarget.create(favoritesDetails)
                .addDropListener(this::onFavoritesDrop);
    }

    @Subscribe("foldersTree")
    public void onFoldersTreeSelection(final SelectionEvent<TreeDataGrid<Folder>, Folder> event) {
        // unlike the item tracking actions, a new folder is not refreshed by the selection
        createFolderAction.refreshState();
        // only those who manage the folder learn that its access can be changed
        accessFolderAction.setVisible(accessFolderActionEnabledRule());

        Folder folder = foldersTree.getSingleSelectedItem();

        // the filter makes the tree select the shown folder again, and its documents are on screen already
        if (folder != null && !folder.equals(selectedFolder)) {
            selectedFolder = folder;
            selectionHandler.accept(folder);
        }
    }

    // a folder goes onto a folder the user may change, but not where it already is, nor into itself or below; one
    // the user may not change goes only to the favorites
    @Subscribe("foldersTree")
    public void onFoldersTreeDragStart(final GridDragStartEvent<Folder> event) {
        Folder folder = event.getDraggedItems().getFirst();

        if (!allows(folder, AccessLevel.EDIT)) {
            return;
        }

        Folder parent = parentOf(folder);
        draggedFolder = folder;

        startDrop(target -> allows(target, AccessLevel.EDIT)
                && !target.equals(parent)
                && !getPath(target).contains(folder));
    }

    @Subscribe("foldersTree")
    public void onFoldersTreeDragEnd(final GridDragEndEvent<Folder> event) {
        draggedFolder = null;
        stopDrop();
    }

    // the drop comes before the end of the drag, which forgets what was dragged
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

    // the table and the tree put the rows they drag into the drag data: all the selected rows when one of them is
    // dragged; the closed section opens to show what was dropped on its header
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
        foldersDc.setItems(folderService.filterByName(allFolders.values(), event.getValue()));
        favorites.setFilter(event.getValue());

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
        DialogWindow<FolderLookupView> window = dialogWindows.lookup(hostView(), Folder.class)
                .withViewClass(FolderLookupView.class)
                .withSelectHandler(targets -> move(folder, targets.iterator().next()))
                .build();

        window.getView().setHiddenFolders(List.of(folder));
        window.open();
    }

    @Subscribe("foldersTree.accessFolderAction")
    public void onAccessFolderAction(final ActionPerformedEvent event) {
        Folder folder = getSelectedFolder();
        dialogWindows.detail(hostView(), AccessDraft.class)
                .withViewClass(AccessDetailView.class)
                .editEntity(accessService.describe(folder))
                .withAfterCloseListener(closeEvent -> {
                    if (closeEvent.closedWith(StandardOutcome.SAVE)) {
                        loadAndSelect(folder.getId());
                    }
                })
                .open();
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
        favoritesChangeHandler.run();
    }

    // the root is always one click away, and a greyed folder is only the way to others
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

    // the key tells a manager where access stops being inherited
    private boolean showsOwnAccess(Folder folder) {
        Folder parent = parentOf(folder);
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
        Runnable move = () -> {
            folderService.move(folder, target);
            loadAndSelect(folder.getId());

            // a folder put back into its parent stays where it was
            if (!target.equals(folder.getParent())) {
                notifications.create(messageBundle.formatMessage("folderMoved",
                                metadataTools.getInstanceName(folder), metadataTools.getInstanceName(target)))
                        .withType(Notifications.Type.SUCCESS)
                        .show();
            }
        };

        if (!accessService.changesOnMove(folder, target)) {
            move.run();
            return;
        }

        dialogs.createOptionDialog()
                .withHeader(messageBundle.getMessage("moveChangesAccessDialog.header"))
                .withText(messageBundle.formatMessage("moveFolderChangesAccessDialog.text",
                        metadataTools.getInstanceName(folder), metadataTools.getInstanceName(target)))
                .withActions(
                        new DialogAction(DialogAction.Type.YES).withVariant(ActionVariant.PRIMARY)
                                .withHandler(actionEvent -> move.run()),
                        new DialogAction(DialogAction.Type.NO))
                .open();
    }

    @Nullable
    private Folder parentOf(Folder folder) {
        return folder.getParent() == null ? null : allFolders.get(folder.getParent().getId());
    }

    private View<?> hostView() {
        return FragmentUtils.getHostView(this);
    }
}
