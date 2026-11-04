package com.company.docai.app.ui.grid;

import com.vaadin.flow.component.grid.ItemClickEvent;
import com.vaadin.flow.data.selection.MultiSelectionEvent;
import com.vaadin.flow.data.selection.SelectionEvent;
import io.jmix.flowui.component.grid.DataGrid;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The selection of a multi-select grid as in a file manager, for the views to call from their own handlers: a click
 * selects its row, a click with Ctrl or Cmd adds or removes it, and the row that has just joined the selection takes
 * the focus.
 */
public final class GridSelectionUtils {

    private GridSelectionUtils() {
    }

    /**
     * A click selects only its row, and a click with Ctrl or Cmd adds or removes the row, as in a file manager.
     */
    public static <E> void click(DataGrid<E> grid, ItemClickEvent<E> event) {
        E item = event.getItem();

        if (event.isCtrlKey() || event.isMetaKey()) {
            if (grid.getSelectedItems().contains(item)) {
                grid.deselect(item);
            } else {
                grid.select(item);
            }

            return;
        }

        selectOnly(grid, item);
    }

    /**
     * Selects the row and deselects the others.
     */
    public static <E> void selectOnly(DataGrid<E> grid, E item) {
        Set<E> others = new HashSet<>(grid.getSelectedItems());
        others.remove(item);
        grid.asMultiSelect().updateSelection(Set.of(item), others);
    }

    /**
     * The row that has just joined the selection is the one in focus; when the focused row has left the selection,
     * the first selected row takes it, or none. The focus gets the row only in these two cases: otherwise it stays.
     */
    public static <E> void focusAfter(SelectionEvent<DataGrid<E>, E> event, @Nullable E focused,
                                      Consumer<@Nullable E> focus) {
        Set<E> selected = event.getSource().getSelectedItems();

        if (event instanceof MultiSelectionEvent<DataGrid<E>, E> change
                && change.getAddedSelection().size() == 1) {
            focus.accept(change.getAddedSelection().iterator().next());
        } else if (!selected.contains(focused)) {
            focus.accept(selected.stream()
                    .findFirst()
                    .orElse(null));
        }
    }
}
