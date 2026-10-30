package com.company.dms.view.favorite;

import com.company.dms.app.service.favorite.FavoriteService;
import com.company.dms.model.document.Document;
import com.company.dms.view.document.DocumentDetailView;
import io.jmix.core.EntityStates;
import io.jmix.core.Messages;
import io.jmix.flowui.action.ActionType;
import io.jmix.flowui.action.view.ViewAction;
import io.jmix.flowui.icon.Icons;
import io.jmix.flowui.kit.icon.JmixFontIcon;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

/**
 * The star of the document card: marks the document as a favorite of the user or removes the mark. The text stays
 * "Favorites"; only the star tells which of the two a click does: empty when the click marks, yellow when it removes
 * the mark. The service saves the mark, so the card does not count it as a change, and the star works in a card the
 * user only views and in the card of an archived document. A new document gets the star after its first save. The
 * repository shows the new mark when the card closes.
 */
@ActionType(FavoriteCardAction.ID)
public class FavoriteCardAction extends ViewAction<FavoriteCardAction, DocumentDetailView> {

    public static final String ID = "dms_favoriteCard";

    private FavoriteService favoriteService;
    private EntityStates entityStates;
    private Icons icons;

    private boolean marked;

    public FavoriteCardAction() {
        this(ID);
    }

    public FavoriteCardAction(String id) {
        super(id);
    }

    @Autowired
    protected void setFavoriteService(FavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    @Autowired
    protected void setEntityStates(EntityStates entityStates) {
        this.entityStates = entityStates;
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
     * Shows the star for a saved document, yellow when the user has marked it.
     */
    @Override
    public void refreshState() {
        super.refreshState();

        Document document = savedDocument();
        setVisibleInternal(visibleExplicitly && document != null);

        if (document != null) {
            marked = !favoriteService.markedIds(List.of(document)).isEmpty();
            setIcon(FavoriteStars.star(icons, marked));
        }
    }

    @Override
    protected boolean isApplicable() {
        return super.isApplicable() && savedDocument() != null;
    }

    @Override
    public void execute() {
        checkTarget();
        List<Document> document = List.of(target.getEditedEntity());

        if (marked) {
            favoriteService.unmark(document);
        } else {
            favoriteService.mark(document);
        }

        refreshState();
    }

    // the view sets the target while it loads, before it has a document; the beans may not be in yet either
    @Nullable
    private Document savedDocument() {
        Document document = target == null || entityStates == null ? null : target.getEditedEntityOrNull();
        return document == null || entityStates.isNew(document) ? null : document;
    }
}
