package com.company.docai.view.status;

import com.company.docai.app.service.document.DocumentStatusService;
import com.company.docai.model.document.DocumentStatus;
import com.company.docai.view.main.MainView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.grid.dnd.GridDragEndEvent;
import com.vaadin.flow.component.grid.dnd.GridDragStartEvent;
import com.vaadin.flow.component.grid.dnd.GridDropEvent;
import com.vaadin.flow.component.grid.dnd.GridDropLocation;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.data.renderer.Renderer;
import com.vaadin.flow.router.Route;
import io.jmix.core.MetadataTools;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.icon.Icons;
import io.jmix.flowui.kit.action.ActionPerformedEvent;
import io.jmix.flowui.kit.icon.JmixFontIcon;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.view.Install;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.Supply;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * The dictionary of document statuses, which the administrator keeps: a status gets its name, color, place and rules
 * in a dialog, and a status that new documents get or a document has is not deleted.
 */
@Route(value = "document-statuses", layout = MainView.class)
@ViewController(id = "DocumentStatus.list")
@ViewDescriptor(path = "document-status-list-view.xml")
public class DocumentStatusListView extends StandardListView<DocumentStatus> {

    @ViewComponent
    private DataGrid<DocumentStatus> statusesDataGrid;

    @ViewComponent
    private CollectionContainer<DocumentStatus> statusesDc;

    @ViewComponent
    private CollectionLoader<DocumentStatus> statusesDl;

    @Autowired
    private DocumentStatusService statusService;

    @Autowired
    private StatusBadges statusBadges;

    @Autowired
    private Icons icons;

    @Autowired
    private MetadataTools metadataTools;

    @Nullable
    private DocumentStatus draggedStatus;

    // a status that takes the flag for new documents takes it from another one, so the list loads both again
    @Install(to = "statusesDataGrid.createAction", subject = "afterSaveHandler")
    private void statusesDataGridCreateActionAfterSaveHandler(final DocumentStatus status) {
        statusesDl.load();
    }

    @Install(to = "statusesDataGrid.editAction", subject = "afterSaveHandler")
    private void statusesDataGridEditActionAfterSaveHandler(final DocumentStatus status) {
        statusesDl.load();
    }

    // a status goes through the status service, which keeps the initial status and a status in use
    @Install(to = "statusesDataGrid.removeAction", subject = "delegate")
    private void statusesDataGridRemoveActionDelegate(final Collection<DocumentStatus> statuses) {
        statuses.forEach(statusService::delete);
    }

    @Subscribe("statusesDataGrid")
    public void onStatusesDataGridDragStart(final GridDragStartEvent<DocumentStatus> event) {
        draggedStatus = event.getDraggedItems().getFirst();
    }

    @Subscribe("statusesDataGrid")
    public void onStatusesDataGridDragEnd(final GridDragEndEvent<DocumentStatus> event) {
        draggedStatus = null;
    }

    // a status dropped on the upper half of a row goes above it, on the lower half below it
    @Subscribe("statusesDataGrid")
    public void onStatusesDataGridDrop(final GridDropEvent<DocumentStatus> event) {
        DocumentStatus target = event.getDropTargetItem().orElse(null);

        if (draggedStatus == null || target == null) {
            return;
        }

        statusService.move(draggedStatus, target, event.getDropLocation() == GridDropLocation.BELOW);
        statusesDl.load();
    }

    @Subscribe("statusesDataGrid.moveUpAction")
    public void onStatusesDataGridMoveUpAction(final ActionPerformedEvent event) {
        moveSelected(-1);
    }

    @Subscribe("statusesDataGrid.moveDownAction")
    public void onStatusesDataGridMoveDownAction(final ActionPerformedEvent event) {
        moveSelected(1);
    }

    @Install(to = "statusesDataGrid.moveUpAction", subject = "enabledRule")
    private boolean statusesDataGridMoveUpActionEnabledRule() {
        return neighbour(-1) != null;
    }

    @Install(to = "statusesDataGrid.moveDownAction", subject = "enabledRule")
    private boolean statusesDataGridMoveDownActionEnabledRule() {
        return neighbour(1) != null;
    }

    // the selected status changes places with its neighbour and stays selected for the next step
    private void moveSelected(int step) {
        DocumentStatus status = statusesDataGrid.getSingleSelectedItem();
        DocumentStatus neighbour = neighbour(step);

        if (status == null || neighbour == null) {
            return;
        }

        statusService.move(status, neighbour, step > 0);
        statusesDl.load();

        DocumentStatus moved = statusesDc.getItemOrNull(status.getId());

        if (moved != null) {
            statusesDataGrid.select(moved);
        }
    }

    @Nullable
    private DocumentStatus neighbour(int step) {
        DocumentStatus status = statusesDataGrid.getSingleSelectedItem();

        if (status == null) {
            return null;
        }

        List<DocumentStatus> statuses = statusesDc.getItems();
        int index = statuses.indexOf(status) + step;
        return index >= 0 && index < statuses.size() ? statuses.get(index) : null;
    }

    @Supply(to = "statusesDataGrid.name", subject = "renderer")
    private Renderer<DocumentStatus> statusesDataGridNameRenderer() {
        return new ComponentRenderer<>(statusBadges::create);
    }

    @Supply(to = "statusesDataGrid.initial", subject = "renderer")
    private Renderer<DocumentStatus> statusesDataGridInitialRenderer() {
        return flagRenderer(DocumentStatus::getInitial);
    }

    @Supply(to = "statusesDataGrid.locked", subject = "renderer")
    private Renderer<DocumentStatus> statusesDataGridLockedRenderer() {
        return flagRenderer(DocumentStatus::getLocked);
    }

    @Supply(to = "statusesDataGrid.anyTarget", subject = "renderer")
    private Renderer<DocumentStatus> statusesDataGridAnyTargetRenderer() {
        return flagRenderer(DocumentStatus::getAnyTarget);
    }

    // a flag shows as a ticked box or an empty one, and a screen reader hears the value
    private Renderer<DocumentStatus> flagRenderer(Function<DocumentStatus, Boolean> flag) {
        return new ComponentRenderer<>(status -> {
            boolean set = Boolean.TRUE.equals(flag.apply(status));
            Component icon = icons.get(set ? JmixFontIcon.CHECK_SQUARE : JmixFontIcon.SQUARE);
            icon.getElement().setAttribute("aria-label", metadataTools.format(set));
            return icon;
        });
    }
}
