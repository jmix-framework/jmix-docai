package com.company.docai.test_support;

import com.company.docai.view.main.MainView;
import com.company.docai.view.repository.FolderTreeFragment;
import com.vaadin.flow.component.Component;
import io.jmix.flowui.component.main.JmixListMenu;
import io.jmix.flowui.fragment.FragmentUtils;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.view.View;

import java.util.Optional;

/**
 * The parts of the main menu of the current UI for UI tests: the folder tree and the list menus live in the main view,
 * around the view that is open.
 */
public final class TestMenus {

    private TestMenus() {
    }

    /**
     * The main view of the current UI: the layout around the current view, or the current view itself.
     */
    public static MainView mainView() {
        View<?> view = UiTestUtils.getCurrentView();
        return view instanceof MainView mainView ? mainView : view.findAncestor(MainView.class);
    }

    /**
     * The folder tree of the main menu.
     */
    public static FolderTreeFragment folderTree() {
        return mainView().getFolderTree();
    }

    /**
     * A component of the folder tree of the main menu by its id in the fragment.
     */
    @SuppressWarnings("unchecked")
    public static <T> T folderTreeComponent(String id) {
        return (T) FragmentUtils.getComponent(folderTree(), id);
    }

    /**
     * A list menu of the main view: {@code menu} with the views under the folders, or {@code assistantMenu} above
     * them. The views scroll with the folders inside the folder tree, where a lookup of the view does not reach, so
     * the menu is looked up through the whole layout.
     */
    public static JmixListMenu listMenu(String id) {
        return find(mainView(), id)
                .map(JmixListMenu.class::cast)
                .orElseThrow(() -> new IllegalArgumentException("No list menu '%s' in the main view".formatted(id)));
    }

    private static Optional<Component> find(Component component, String id) {
        if (component.getId().filter(id::equals).isPresent()) {
            return Optional.of(component);
        }

        return component.getChildren()
                .map(child -> find(child, id))
                .flatMap(Optional::stream)
                .findFirst();
    }
}
