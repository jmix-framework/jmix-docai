package com.company.dms.view.trash;

import com.company.dms.app.service.trash.TrashService;
import com.company.dms.model.document.Document;
import com.company.dms.model.trash.TrashItem;
import com.company.dms.view.document.DocumentPreviewFragment;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.grid.ItemClickEvent;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.data.renderer.Renderer;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.fragment.Fragment;
import io.jmix.flowui.fragment.FragmentDescriptor;
import io.jmix.flowui.icon.Icons;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.kit.icon.JmixFontIcon;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.Supply;
import io.jmix.flowui.view.ViewComponent;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

/**
 * The preview of an item of the trash: the current version of a document, or what comes back together with
 * a folder. A document of the folder opens its own preview with a way back to the folder.
 */
@FragmentDescriptor("trash-preview-fragment.xml")
public class TrashPreviewFragment extends Fragment<VerticalLayout> {

    @ViewComponent
    private Span placeholder;
    @ViewComponent
    private VerticalLayout contentsPanel;
    @ViewComponent
    private Span contentsSummary;
    @ViewComponent
    private DataGrid<TrashItem> contentsGrid;
    @ViewComponent
    private CollectionContainer<TrashItem> contentsDc;
    @ViewComponent
    private VerticalLayout documentPanel;
    @ViewComponent
    private JmixButton backButton;
    @ViewComponent
    private DocumentPreviewFragment documentPreview;

    @ViewComponent
    private MessageBundle messageBundle;

    @Autowired
    private TrashService trashService;
    @Autowired
    private UiComponents uiComponents;
    @Autowired
    private Icons icons;

    /**
     * Shows the item, or a hint to select one when there is none.
     */
    public void show(@Nullable TrashItem item) {
        placeholder.setVisible(item == null);
        contentsPanel.setVisible(item != null && item.getFolder() != null);
        documentPanel.setVisible(false);

        if (item == null) {
            return;
        }

        if (item.getFolder() != null) {
            List<TrashItem> contents = trashService.loadContents(item.getFolder());
            long folders = contents.stream()
                    .filter(content -> content.getFolder() != null)
                    .count();

            contentsDc.setItems(contents);
            // the location says something only when the folder has subfolders
            contentsGrid.getColumnByKey("location").setVisible(contents.stream()
                    .anyMatch(content -> !content.getLocation().isEmpty()));
            contentsSummary.setText(messageBundle.formatMessage("contentsSummary", folders,
                    contents.size() - folders));
        } else {
            showDocument(item.getDocument(), false);
        }
    }

    @Subscribe("contentsGrid")
    public void onContentsGridItemClick(final ItemClickEvent<TrashItem> event) {
        if (event.getItem().getDocument() != null) {
            contentsPanel.setVisible(false);
            showDocument(event.getItem().getDocument(), true);
        }
    }

    @Subscribe("backButton")
    public void onBackButtonClick(final ClickEvent<JmixButton> event) {
        documentPanel.setVisible(false);
        contentsPanel.setVisible(true);
    }

    @Supply(to = "contentsGrid.name", subject = "renderer")
    private Renderer<TrashItem> contentsGridNameRenderer() {
        return new ComponentRenderer<>(item -> {
            Span name = uiComponents.create(Span.class);
            name.addClassName("name-with-icon");
            name.add(icons.get(item.getFolder() != null ? JmixFontIcon.FOLDER : JmixFontIcon.FILE_TEXT),
                    new Text(item.getName()));

            return name;
        });
    }

    private void showDocument(Document document, boolean inFolder) {
        backButton.setVisible(inFolder);
        documentPreview.showRevision(document.getCurrentRevision());
        documentPanel.setVisible(true);
    }
}
