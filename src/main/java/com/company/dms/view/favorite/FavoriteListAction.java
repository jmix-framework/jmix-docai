package com.company.dms.view.favorite;

import com.company.dms.app.service.favorite.FavoriteService;
import com.vaadin.flow.component.Component;
import io.jmix.core.Messages;
import io.jmix.flowui.action.ActionType;
import io.jmix.flowui.action.list.SecuredListDataComponentAction;
import io.jmix.flowui.icon.Icons;
import io.jmix.flowui.kit.icon.JmixFontIcon;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Set;

/**
 * Marks the folders or the documents selected in a table as favorites, or removes the marks when all of them are
 * marked already. The text stays "Favorites", so a bound button keeps the text and the title of its descriptor; only
 * the star tells which of the two a click does: a yellow outline when the click marks, a filled yellow star when it
 * removes the marks. The table learns of a click through {@link #setAfterToggleHandler(Runnable)}. The other places
 * that show a favorite take the same star from {@link #star(Icons, boolean)}.
 *
 * @param <E> the folders or the documents of the table
 */
@ActionType(FavoriteListAction.ID)
public class FavoriteListAction<E> extends SecuredListDataComponentAction<FavoriteListAction<E>, E> {

    public static final String ID = "dms_favoriteList";

    /**
     * The class of the star of a marked item; the theme colors it.
     */
    public static final String MARKED_CLASS = "favorite-star";

    /**
     * The class of the star of an item that is not marked; the theme colors it.
     */
    public static final String UNMARKED_CLASS = "favorite-star-unmarked";

    private FavoriteService favoriteService;
    private Icons icons;

    private boolean allMarked;
    private Runnable afterToggleHandler = () -> {
    };

    public FavoriteListAction() {
        this(ID);
    }

    public FavoriteListAction(String id) {
        super(id);
    }

    @Autowired
    protected void setFavoriteService(FavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    @Autowired
    protected void setMessages(Messages messages) {
        this.text = messages.getMessage(getClass(), "favoriteAction.text");
    }

    @Autowired
    protected void setIcons(Icons icons) {
        this.icons = icons;
        this.icon = icons.get(JmixFontIcon.STAR);
    }

    /**
     * Returns a new star of the favorites: an outline when the item is not marked, filled when it is; a disabled
     * action shows it grey. Both are the same glyph, which the theme draws as an outline for the class of an item
     * that is not marked: the outline glyph is deprecated since Jmix 3.1.
     */
    public static Component star(Icons icons, boolean marked) {
        Component star = icons.get(JmixFontIcon.STAR);
        star.getElement().getClassList().add(marked ? MARKED_CLASS : UNMARKED_CLASS);

        return star;
    }

    /**
     * Tells whether all the selected items are marked, so that a click removes their marks.
     */
    public boolean isAllMarked() {
        return allMarked;
    }

    /**
     * Sets what runs after a click has marked or unmarked the items, so that the views showing marks follow.
     */
    public void setAfterToggleHandler(Runnable afterToggleHandler) {
        this.afterToggleHandler = afterToggleHandler;
    }

    /**
     * Follows the selection with the star: a click marks the selected items unless all of them are marked.
     */
    @Override
    public void refreshState() {
        super.refreshState();

        // the framework refreshes the action while it builds it, before the beans are in
        if (favoriteService == null || icons == null) {
            return;
        }

        Set<E> selected = target == null ? Set.of() : target.getSelectedItems();
        allMarked = !selected.isEmpty() && favoriteService.markedIds(selected).size() == selected.size();
        setIcon(star(icons, allMarked));
    }

    @Override
    public void execute() {
        checkTarget();
        Set<E> selected = target.getSelectedItems();

        if (allMarked) {
            favoriteService.unmark(selected);
        } else {
            favoriteService.mark(selected);
        }

        refreshState();
        afterToggleHandler.run();
    }
}
