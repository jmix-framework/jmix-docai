package com.company.dms.test_support;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.contextmenu.ContextMenuBase;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.ItemClickEvent;
import com.vaadin.flow.component.grid.ItemDoubleClickEvent;
import com.vaadin.flow.component.grid.contextmenu.GridContextMenu;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.data.renderer.Renderer;
import io.jmix.flowui.component.grid.DataGrid;

/**
 * Clicks that a browser would send to a grid, for UI tests that run without one.
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
}
