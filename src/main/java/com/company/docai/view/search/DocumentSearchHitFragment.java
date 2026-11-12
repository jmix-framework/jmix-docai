package com.company.docai.view.search;

import com.company.docai.model.document.Document;
import com.company.docai.search.DocumentSearchHit;
import com.company.docai.view.document.DocumentCards;
import com.company.docai.view.status.StatusBadges;
import com.company.docai.view.tag.TagBadges;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import io.jmix.core.MetadataTools;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.fragment.Fragment;
import io.jmix.flowui.fragment.FragmentDescriptor;
import io.jmix.flowui.fragment.FragmentUtils;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.view.StandardOutcome;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A found document as a row of the search page: its name, which opens the card, its type, status and tags, its
 * folder and the matched text. The page creates a row for each hit.
 */
@FragmentDescriptor("document-search-hit-fragment.xml")
public class DocumentSearchHitFragment extends Fragment<Div> {

    @ViewComponent
    private Div titleBox;
    @ViewComponent
    private JmixButton nameButton;
    @ViewComponent
    private Span pathText;

    @Autowired
    private DocumentCards documentCards;
    @Autowired
    private TagBadges tagBadges;
    @Autowired
    private StatusBadges statusBadges;
    @Autowired
    private UiComponents uiComponents;
    @Autowired
    private MetadataTools metadataTools;

    private DocumentSearchHit hit;
    private Runnable afterSaveHandler = () -> {
    };

    /**
     * Shows the found document. The page sets the hit once, before it adds the row.
     */
    public void setHit(DocumentSearchHit hit) {
        this.hit = hit;
        Document document = hit.document();
        nameButton.setText(document.getName());

        if (document.getCategory() != null) {
            Badge type = uiComponents.create(Badge.class);
            type.setText(metadataTools.getInstanceName(document.getCategory()));
            type.addClassName("v-neutral");
            titleBox.add(type);
        }

        titleBox.add(statusBadges.create(document.getStatus()));

        if (!document.getTags().isEmpty()) {
            titleBox.add(tagBadges.create(document.getTags()));
        }

        pathText.setText(String.join(" / ", hit.folderPath()));

        if (hit.snippet() != null) {
            getContent().add(createSnippet(hit.field(), hit.snippet()));
        }
    }

    /**
     * Receives a save of the document in the card that the row opened.
     */
    public void setAfterSaveHandler(Runnable afterSaveHandler) {
        this.afterSaveHandler = afterSaveHandler;
    }

    @Subscribe(id = "nameButton", subject = "clickListener")
    public void onNameButtonClick(final ClickEvent<JmixButton> event) {
        // the card fails on a document deleted or hidden from the user since the search
        documentCards.openIfAvailable(FragmentUtils.getHostView(this), hit.document(), closeEvent -> {
            if (closeEvent.closedWith(StandardOutcome.SAVE)) {
                afterSaveHandler.run();
            }
        });
    }

    /**
     * Builds the matched text from text nodes and match spans: the text comes from the users, so it is never
     * put into the page as HTML.
     */
    private Div createSnippet(@Nullable String field, String snippet) {
        Div div = uiComponents.create(Div.class);
        div.addClassName("document-search-snippet");

        if (field != null) {
            Span caption = uiComponents.create(Span.class);
            caption.addClassName("document-search-field");
            caption.setText(field);
            div.add(caption);
        }

        for (DocumentSearchHit.Part part : DocumentSearchHit.partsOf(snippet)) {
            if (part.matched()) {
                Span match = uiComponents.create(Span.class);
                match.addClassName("document-search-match");
                match.setText(part.text());
                div.add(match);
            } else {
                div.add(new Text(part.text()));
            }
        }

        return div;
    }
}
