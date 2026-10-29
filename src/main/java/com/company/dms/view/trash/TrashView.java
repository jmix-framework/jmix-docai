package com.company.dms.view.trash;

import com.company.dms.app.service.trash.TrashService;
import com.company.dms.model.trash.TrashItem;
import com.company.dms.view.main.MainView;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.ItemClickEvent;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.data.renderer.Renderer;
import com.vaadin.flow.data.selection.MultiSelectionEvent;
import com.vaadin.flow.data.selection.SelectionEvent;
import com.vaadin.flow.router.Route;
import io.jmix.core.LoadContext;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.sidepanellayout.SidePanelLayout;
import io.jmix.flowui.icon.Icons;
import io.jmix.flowui.kit.action.Action;
import io.jmix.flowui.kit.action.ActionPerformedEvent;
import io.jmix.flowui.kit.action.ActionVariant;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.kit.icon.JmixFontIcon;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.view.Install;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.Supply;
import io.jmix.flowui.view.Target;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The trash: the folders and documents deleted on their own that the user may read, with a preview of the selected
 * one. Restoring follows the access to the items; deleting forever and emptying the trash are for those with
 * {@link TrashService#PURGE_POLICY}.
 */
@Route(value = "trash", layout = MainView.class)
@ViewController(id = "TrashView")
@ViewDescriptor(path = "trash-view.xml")
public class TrashView extends StandardView {

    // more names than these make the question hard to read, so it gives the number
    private static final int NAMED_ITEMS = 3;

    @ViewComponent
    private DataGrid<TrashItem> trashItemsGrid;
    @ViewComponent
    private CollectionContainer<TrashItem> trashItemsDc;
    @ViewComponent
    private CollectionLoader<TrashItem> trashItemsDl;
    @ViewComponent
    private JmixButton previewButton;
    @ViewComponent
    private SidePanelLayout previewLayout;
    @ViewComponent
    private TrashPreviewFragment preview;
    @ViewComponent("trashItemsGrid.purgeAction")
    private Action purgeAction;
    @ViewComponent
    private Action emptyAction;

    @ViewComponent
    private MessageBundle messageBundle;

    @Autowired
    private TrashService trashService;
    @Autowired
    private Dialogs dialogs;
    @Autowired
    private Notifications notifications;
    @Autowired
    private UiComponents uiComponents;
    @Autowired
    private Icons icons;

    @Nullable
    private TrashItem focusedItem;
    private boolean previewMode;

    @Subscribe
    public void onInit(final InitEvent event) {
        boolean purge = trashService.canPurge();
        purgeAction.setVisible(purge);
        emptyAction.setVisible(purge);

        // only the panel's own close button turns the mode off
        previewLayout.addSidePanelCloseListener(closeEvent -> {
            if (closeEvent.isFromClient() && previewMode) {
                setPreviewMode(false);
            }
        });
    }

    @Install(to = "trashItemsDl", target = Target.DATA_LOADER)
    private List<TrashItem> trashItemsDlLoadDelegate(final LoadContext<TrashItem> loadContext) {
        return trashService.loadItems();
    }

    @Subscribe(id = "trashItemsDc", target = Target.DATA_CONTAINER)
    public void onTrashItemsDcCollectionChange(final CollectionContainer.CollectionChangeEvent<TrashItem> event) {
        emptyAction.setEnabled(!event.getSource().getItems().isEmpty());
    }

    // a click selects only its row, and a click with Ctrl or Cmd adds or removes the row, as in the repository
    @Subscribe("trashItemsGrid")
    public void onTrashItemsGridItemClick(final ItemClickEvent<TrashItem> event) {
        TrashItem item = event.getItem();

        if (event.isCtrlKey() || event.isMetaKey()) {
            if (trashItemsGrid.getSelectedItems().contains(item)) {
                trashItemsGrid.deselect(item);
            } else {
                trashItemsGrid.select(item);
            }

            return;
        }

        Set<TrashItem> others = new HashSet<>(trashItemsGrid.getSelectedItems());
        others.remove(item);
        trashItemsGrid.asMultiSelect().updateSelection(Set.of(item), others);
    }

    // the item that has just joined the selection is the one to preview
    @Subscribe("trashItemsGrid")
    public void onTrashItemsGridSelection(final SelectionEvent<DataGrid<TrashItem>, TrashItem> event) {
        Set<TrashItem> selected = trashItemsGrid.getSelectedItems();

        if (event instanceof MultiSelectionEvent<DataGrid<TrashItem>, TrashItem> change
                && change.getAddedSelection().size() == 1) {
            focusedItem = change.getAddedSelection().iterator().next();
        } else if (!selected.contains(focusedItem)) {
            focusedItem = selected.stream().findFirst().orElse(null);
        }

        showPreview();
    }

    @Subscribe("trashItemsGrid.restoreAction")
    public void onRestoreAction(final ActionPerformedEvent event) {
        int restored = trashService.restore(trashItemsGrid.getSelectedItems());

        notifications.create(messageBundle.formatMessage("restoredNotification", restored))
                .withType(Notifications.Type.SUCCESS)
                .show();
        reload();
    }

    @Subscribe("trashItemsGrid.purgeAction")
    public void onPurgeAction(final ActionPerformedEvent event) {
        List<TrashItem> items = List.copyOf(trashItemsGrid.getSelectedItems());
        String question = items.size() > NAMED_ITEMS
                ? messageBundle.formatMessage("purgeDialog.count", items.size())
                : messageBundle.formatMessage("purgeDialog.names", items.stream()
                .map(TrashItem::getName)
                .collect(Collectors.joining(", ")));

        confirm(messageBundle.getMessage("purgeDialog.header"), question, () -> trashService.purge(items));
    }

    @Subscribe("emptyAction")
    public void onEmptyAction(final ActionPerformedEvent event) {
        confirm(messageBundle.getMessage("emptyDialog.header"),
                messageBundle.formatMessage("emptyDialog.text", trashItemsDc.getItems().size()),
                trashService::empty);
    }

    @Subscribe("previewButton")
    public void onPreviewButtonClick(final ClickEvent<JmixButton> event) {
        setPreviewMode(!previewMode);
    }

    @Supply(to = "trashItemsGrid.name", subject = "renderer")
    private Renderer<TrashItem> trashItemsGridNameRenderer() {
        return new ComponentRenderer<>(item -> {
            Span name = uiComponents.create(Span.class);
            name.addClassName("name-with-icon");
            name.add(icons.get(item.getFolder() != null ? JmixFontIcon.FOLDER : JmixFontIcon.FILE_TEXT),
                    new Text(item.getName()));

            return name;
        });
    }

    private void confirm(String header, String question, Runnable deletion) {
        dialogs.createOptionDialog()
                .withHeader(header)
                .withText(question)
                .withActions(
                        new DialogAction(DialogAction.Type.YES).withHandler(actionEvent -> {
                            deletion.run();
                            reload();
                        }),
                        new DialogAction(DialogAction.Type.NO).withVariant(ActionVariant.PRIMARY))
                .open();
    }

    // the items that came back or were deleted forever leave the list, and the selection with them
    private void reload() {
        trashItemsGrid.deselectAll();
        trashItemsDl.load();
    }

    private void setPreviewMode(boolean previewMode) {
        this.previewMode = previewMode;

        if (previewMode) {
            previewButton.addThemeVariants(ButtonVariant.PRIMARY);
        } else {
            previewButton.removeThemeVariants(ButtonVariant.PRIMARY);
        }

        previewButton.getElement().setAttribute("aria-pressed", String.valueOf(previewMode));
        showPreview();
    }

    private void showPreview() {
        if (!previewMode) {
            previewLayout.closeSidePanel();
            return;
        }

        preview.show(focusedItem);
        previewLayout.openSidePanel();
    }
}
