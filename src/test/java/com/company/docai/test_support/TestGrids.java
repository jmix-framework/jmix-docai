package com.company.docai.test_support;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.contextmenu.ContextMenuBase;
import com.vaadin.flow.component.dnd.DropEvent;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.ItemClickEvent;
import com.vaadin.flow.component.grid.ItemDoubleClickEvent;
import com.vaadin.flow.component.grid.contextmenu.GridContextMenu;
import com.vaadin.flow.component.grid.dnd.GridDragEndEvent;
import com.vaadin.flow.component.grid.dnd.GridDragStartEvent;
import com.vaadin.flow.component.grid.dnd.GridDropEvent;
import com.vaadin.flow.component.grid.dnd.GridDropLocation;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.data.renderer.LitRenderer;
import com.vaadin.flow.data.renderer.Renderer;
import com.vaadin.flow.data.renderer.TextRenderer;
import io.jmix.flowui.component.grid.DataGrid;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Clicks and drags that a browser would send to a grid, for UI tests that run without one.
 */
public final class TestGrids {

    private TestGrids() {
    }

    /**
     * A click on a row as the grid reports it, with or without the Ctrl key.
     */
    public static <T> void clickRow(Grid<T> grid, T item, boolean ctrlKey) {
        String itemKey = grid.getDataCommunicator().getKeyMapper().key(item);
        ComponentUtil.fireEvent(grid, new ItemClickEvent<>(grid, true, itemKey, null,
                0, 0, 0, 0, 1, 0, ctrlKey, false, false, false));
    }

    /**
     * A double click on a row as the grid reports it.
     */
    public static <T> void doubleClickRow(Grid<T> grid, T item) {
        String itemKey = grid.getDataCommunicator().getKeyMapper().key(item);
        ComponentUtil.fireEvent(grid, new ItemDoubleClickEvent<>(grid, true, itemKey, null,
                0, 0, 0, 0, 2, 0, false, false, false, false));
    }

    /**
     * A right click on a row: the browser names the row in a grid property and opens the context menu.
     */
    public static <T> void openContextMenu(DataGrid<T> grid, T item) {
        String itemKey = grid.getDataCommunicator().getKeyMapper().key(item);
        grid.getElement().setProperty("_contextMenuTargetItemKey", itemKey);

        GridContextMenu<T> menu = grid.getContextMenu();
        menu.getElement().setProperty("opened", true);
        ComponentUtil.fireEvent(menu, new ContextMenuBase.OpenedChangeEvent<>(menu, true));
    }

    /**
     * Rows dragged from a grid and dropped on a component. The browser names the rows it drags: all the selected
     * rows when the dragged row is one of them.
     */
    public static <T, C extends Component> void dragRows(Grid<T> grid, List<T> rows, C target) {
        startDrag(grid, rows);
        ComponentUtil.fireEvent(target, new DropEvent<>(target, true, "all", 0, 0, 0, 0));
        endDrag(grid);
    }

    /**
     * The start of a drag of rows, as the grid reports it with the rows the browser names.
     */
    public static <T> void startDrag(Grid<T> grid, List<T> rows) {
        ArrayNode draggedItems = JsonNodeFactory.instance.arrayNode();
        rows.forEach(row -> draggedItems.addObject().put("key", grid.getDataCommunicator().getKeyMapper().key(row)));
        ObjectNode details = JsonNodeFactory.instance.objectNode();
        details.set("draggedItems", draggedItems);

        ComponentUtil.fireEvent(grid, new GridDragStartEvent<>(grid, true, details));
    }

    /**
     * A drop on top of a row, as the grid that takes it reports it. The drop comes before the end of the drag.
     */
    public static <T> void dropOnRow(Grid<T> grid, T item) {
        ObjectNode target = JsonNodeFactory.instance.objectNode()
                .put("key", grid.getDataCommunicator().getKeyMapper().key(item));

        ComponentUtil.fireEvent(grid, new GridDropEvent<>(grid, true, target, GridDropLocation.ON_TOP.getClientName(),
                JsonNodeFactory.instance.arrayNode()));
    }

    /**
     * The end of a drag of rows, as the grid the rows were dragged from reports it.
     */
    public static <T> void endDrag(Grid<T> grid) {
        ComponentUtil.fireEvent(grid, new GridDragEndEvent<>(grid, true));
    }

    /**
     * A click on the component that a column renders for the item, such as a folder name in a tree.
     */
    public static <T> Component clickRendered(Grid<T> grid, String columnKey, T item) {
        Component component = render(grid, columnKey, item);
        ComponentUtil.fireEvent(component, new ClickEvent<>(component));
        return component;
    }

    /**
     * The component that a column renders for the item.
     */
    public static <T> Component render(Grid<T> grid, String columnKey, T item) {
        Renderer<T> renderer = grid.getColumnByKey(columnKey).getRenderer();
        if (!(renderer instanceof ComponentRenderer<?, T> componentRenderer)) {
            throw new AssertionError("Column '%s' does not render components".formatted(columnKey));
        }

        return componentRenderer.createComponent(item);
    }

    /**
     * The text that a column shows for the item.
     */
    public static <T> String text(Grid<T> grid, String columnKey, T item) {
        Renderer<T> renderer = grid.getColumnByKey(columnKey).getRenderer();
        if (!(renderer instanceof TextRenderer<T> textRenderer)) {
            throw new AssertionError("Column '%s' does not render text".formatted(columnKey));
        }

        return textRenderer.createComponent(item).getElement().getText();
    }

    /**
     * A property of the Lit template that renders a column, as the grid sends it to the browser for the item.
     */
    public static <T> Object property(Grid<T> grid, String columnKey, String property, T item) {
        Renderer<T> renderer = grid.getColumnByKey(columnKey).getRenderer();
        // the renderers of text and components are Lit templates too, with properties of their own
        if (!(renderer instanceof LitRenderer<T> litRenderer)
                || !litRenderer.getValueProviders().containsKey(property)) {
            throw new AssertionError("Column '%s' renders no property '%s'".formatted(columnKey, property));
        }

        return litRenderer.getValueProviders().get(property).apply(item);
    }
}
