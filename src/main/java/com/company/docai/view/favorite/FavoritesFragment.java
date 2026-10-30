package com.company.docai.view.favorite;

import com.company.docai.app.service.favorite.FavoriteService;
import com.company.docai.model.document.Document;
import com.company.docai.model.favorite.FavoriteItem;
import com.company.docai.model.folder.Folder;
import com.company.docai.view.document.DocumentDetailView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import io.jmix.core.Messages;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.fragment.Fragment;
import io.jmix.flowui.fragment.FragmentDescriptor;
import io.jmix.flowui.icon.Icons;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.kit.icon.JmixFontIcon;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.ViewComponent;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The favorites block above the folder tree: the folders, then the documents the user has marked, each with the
 * folder it lies in. A folder opens in the tree and a document in its card; the star of a row removes the mark, and
 * the folders and the documents dropped on the block are marked. The filter of the tree narrows the block by name.
 * The repository shows the block again whenever the marks change elsewhere.
 */
@FragmentDescriptor("favorites-fragment.xml")
public class FavoritesFragment extends Fragment<VerticalLayout> {

    @ViewComponent
    private VerticalLayout favoritesBox;
    @ViewComponent
    private Span emptyHint;
    @ViewComponent
    private MessageBundle messageBundle;

    @Autowired
    private FavoriteService favoriteService;
    @Autowired
    private UiComponents uiComponents;
    @Autowired
    private Icons icons;
    @Autowired
    private DataManager dataManager;
    @Autowired
    private Notifications notifications;
    @Autowired
    private Messages messages;

    private Consumer<Folder> folderOpenHandler = folder -> {
    };
    private Consumer<Document> documentOpenHandler = document -> {
    };
    private Runnable changeHandler = () -> {
    };
    private List<FavoriteItem> items = List.of();
    private String filter = "";

    /**
     * Receives a folder of the block to open in the tree.
     */
    public void setFolderOpenHandler(Consumer<Folder> folderOpenHandler) {
        this.folderOpenHandler = folderOpenHandler;
    }

    /**
     * Receives a document of the block to open in its card; the document is still there.
     */
    public void setDocumentOpenHandler(Consumer<Document> documentOpenHandler) {
        this.documentOpenHandler = documentOpenHandler;
    }

    /**
     * Receives a mark set or removed in the block, so that the other views showing marks follow.
     */
    public void setChangeHandler(Runnable changeHandler) {
        this.changeHandler = changeHandler;
    }

    /**
     * Marks the folders and the documents brought to the block, then shows the block again; a target marked already
     * stays as it is.
     */
    public void mark(Collection<?> targets) {
        favoriteService.mark(targets);
        reload();
        changeHandler.run();
    }

    /**
     * Shows the favorites of the user again.
     */
    public void reload() {
        items = favoriteService.loadItems();
        show();
    }

    /**
     * Shows only the favorites whose name contains the text, ignoring case, as the tree does with the folders; an
     * empty text shows them all.
     */
    public void setFilter(@Nullable String text) {
        filter = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        show();
    }

    // the hint is for a user without favorites, not for a filter that hides them
    private void show() {
        favoritesBox.removeAll();

        items.stream()
                .filter(item -> item.getName().toLowerCase(Locale.ROOT).contains(filter))
                .forEach(item -> favoritesBox.add(row(item)));
        emptyHint.setVisible(items.isEmpty());
    }

    private Component row(FavoriteItem item) {
        JmixButton name = uiComponents.create(JmixButton.class);
        name.setText(item.getName());
        name.setTitle(item.getName());
        name.setIcon(icons.get(item.getFolder() != null ? JmixFontIcon.FOLDER : JmixFontIcon.FILE_TEXT));
        name.addClassName("favorite-name");
        name.addThemeVariants(ButtonVariant.TERTIARY);
        name.addClickListener(click -> open(item));

        // the title is the aria label of the button as well
        JmixButton star = uiComponents.create(JmixButton.class);
        star.setIcon(FavoriteListAction.star(icons, true));
        star.setTitle(messageBundle.getMessage("unmarkButton.title"));
        star.addThemeVariants(ButtonVariant.TERTIARY);
        star.addClickListener(click -> unmark(item));

        Div row = uiComponents.create(Div.class);
        row.addClassName("favorite-row");
        row.add(name);

        if (item.getLocation() != null) {
            Span location = uiComponents.create(Span.class);
            location.addClassName("favorite-location");
            location.setText(item.getLocation());
            location.setTitle(item.getLocation());
            row.add(location);
        }

        row.add(star);

        return row;
    }

    // a document deleted or hidden from the user since the block was shown would end in the technical error dialog
    private void open(FavoriteItem item) {
        if (item.getFolder() != null) {
            folderOpenHandler.accept(item.getFolder());
            return;
        }

        if (dataManager.load(Id.of(item.getDocument())).optional().isEmpty()) {
            notifications.create(messages.getMessage(DocumentDetailView.class, "documentUnavailable"))
                    .withType(Notifications.Type.WARNING)
                    .show();

            reload();
            return;
        }

        documentOpenHandler.accept(item.getDocument());
    }

    private void unmark(FavoriteItem item) {
        favoriteService.unmark(List.of(item.getFolder() != null ? item.getFolder() : item.getDocument()));
        reload();
        changeHandler.run();
    }
}
