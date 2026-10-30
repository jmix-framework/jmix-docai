package com.company.dms.view.repository;

import com.company.dms.model.document.Document;
import com.company.dms.model.folder.Folder;
import com.company.dms.view.document.DocumentPreviewFragment;
import com.company.dms.view.favorite.FavoritesFragment;
import com.company.dms.view.main.MainView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;
import io.jmix.core.MetadataTools;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.component.sidepanellayout.SidePanelLayout;
import io.jmix.flowui.facet.UrlQueryParametersFacet;
import io.jmix.flowui.facet.UrlQueryParametersFacet.UrlQueryParametersChangeEvent;
import io.jmix.flowui.facet.urlqueryparameters.AbstractUrlQueryParametersBinder;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The document repository: the folder tree, the documents of the selected folder and
 * the preview panel. The address keeps the open folder and the preview mode. Documents dragged from
 * the list move into the folder of the tree they are dropped on.
 */
@Route(value = "repository", layout = MainView.class)
@ViewController(id = "RepositoryView")
@ViewDescriptor(path = "repository-view.xml")
public class RepositoryView extends StandardView {

    private static final String FOLDER_PARAMETER = "folder";
    private static final String PREVIEW_PARAMETER = "preview";

    @ViewComponent
    private FolderTreeFragment folderTree;
    @ViewComponent
    private DocumentListFragment documentList;
    @ViewComponent
    private DocumentPreviewFragment preview;
    @ViewComponent
    private SidePanelLayout previewLayout;
    @ViewComponent
    private HorizontalLayout breadcrumbs;
    @ViewComponent
    private UrlQueryParametersFacet urlQueryParameters;

    @Autowired
    private UiComponents uiComponents;
    @Autowired
    private MetadataTools metadataTools;

    private final AddressBinder address = new AddressBinder();
    @Nullable
    private UUID requestedFolderId;
    private boolean previewMode;

    @Subscribe
    public void onInit(final InitEvent event) {
        urlQueryParameters.registerBinder(address);

        configureFragments();

        // only the panel's own close button turns the mode off; the view closes the panel when nothing is selected
        previewLayout.addSidePanelCloseListener(closeEvent -> {
            if (closeEvent.isFromClient() && previewMode) {
                setPreviewMode(false);
                address.update();
            }
        });
    }

    private void configureFragments() {
        FavoritesFragment favorites = folderTree.getFavorites();
        folderTree.setSelectionHandler(this::showFolder);
        folderTree.setReloadHandler(favorites::reload);
        favorites.setFolderOpenHandler(folder -> folderTree.loadAndSelect(folder.getId()));
        favorites.setDocumentOpenHandler(documentList::openCard);
        documentList.setFocusHandler(this::showPreview);
        documentList.setFavoritesChangeHandler(() -> {
            favorites.reload();
            folderTree.refreshFavorites();
        });
        folderTree.setFavoritesChangeHandler(favorites::reload);
        favorites.setChangeHandler(() -> {
            documentList.refreshFavorites();
            folderTree.refreshFavorites();
        });
        documentList.setPreviewToggleHandler(this::togglePreviewMode);
        documentList.setContentsChangeHandler(() -> {
            folderTree.refreshDocumentCounts();
            favorites.reload();
        });
        documentList.setDragHandler(folderTree::setDraggedDocuments);
        folderTree.setDocumentsDropHandler(documentList::move);
    }

    @Subscribe
    public void onBeforeShow(final BeforeShowEvent event) {
        folderTree.loadAndSelect(requestedFolderId);
    }

    private void showFolder(Folder folder) {
        showBreadcrumbs(folderTree.getPath(folder));
        documentList.setFolder(folder);
        address.update();
    }

    private void showBreadcrumbs(List<Folder> path) {
        breadcrumbs.removeAll();
        for (int i = 0; i < path.size(); i++) {
            if (i > 0) {
                breadcrumbs.add(text("/"));
            }

            Folder step = path.get(i);

            if (i == path.size() - 1) {
                Span current = text(metadataTools.getInstanceName(step));
                current.addClassName("breadcrumb-current");
                breadcrumbs.add(current);
            } else if (folderTree.isGreyed(step)) {
                // a greyed folder is only the way here, so it is named but not opened
                breadcrumbs.add(text(metadataTools.getInstanceName(step)));
            } else {
                JmixButton link = uiComponents.create(JmixButton.class);
                link.setText(metadataTools.getInstanceName(step));
                link.addThemeVariants(ButtonVariant.TERTIARY);
                link.addClickListener(click -> folderTree.select(step));
                breadcrumbs.add(link);
            }
        }
    }

    private Span text(String value) {
        Span span = uiComponents.create(Span.class);
        span.setText(value);
        return span;
    }

    private void togglePreviewMode() {
        setPreviewMode(!previewMode);
        address.update();
    }

    private void setPreviewMode(boolean previewMode) {
        this.previewMode = previewMode;
        documentList.setPreviewPressed(previewMode);
        showPreview(documentList.getFocusedDocument());
    }

    private void showPreview(@Nullable Document document) {
        if (!previewMode || document == null) {
            previewLayout.closeSidePanel();
            return;
        }

        preview.showRevision(document.getCurrentRevision());
        previewLayout.openSidePanel();
    }

    @Nullable
    private static UUID parseId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Keeps the open folder and the preview mode in the address. The facet writes the address of a view
     * that is being opened right before the response, when the navigation has already set the view's own
     * path; written earlier, the parameters would land on the previous address, such as the login.
     */
    private class AddressBinder extends AbstractUrlQueryParametersBinder {

        @Override
        public void updateState(QueryParameters queryParameters) {
            Map<String, List<String>> parameters = queryParameters.getParameters();
            requestedFolderId = parameters.getOrDefault(FOLDER_PARAMETER, List.of()).stream()
                    .findFirst()
                    .map(RepositoryView::parseId)
                    .orElse(null);

            setPreviewMode(parameters.containsKey(PREVIEW_PARAMETER));
        }

        void update() {
            Folder folder = folderTree.getSelectedFolder();
            Map<String, List<String>> parameters = new HashMap<>();
            parameters.put(FOLDER_PARAMETER, folder == null ? List.of() : List.of(folder.getId().toString()));
            // an empty list removes the parameter from the address
            parameters.put(PREVIEW_PARAMETER, previewMode ? List.of("") : List.of());

            fireQueryParametersChanged(new UrlQueryParametersChangeEvent(this, new QueryParameters(parameters)));
        }

        @Nullable
        @Override
        public Component getComponent() {
            return null;
        }
    }
}
