package com.company.docai.view.search;

import com.company.docai.ai.AiSettings;
import com.company.docai.app.service.tag.TagCount;
import com.company.docai.model.document.Document;
import com.company.docai.model.favorite.FavoriteItem;
import com.company.docai.model.folder.Folder;
import com.company.docai.search.DocumentSearchHit;
import com.company.docai.search.DocumentSearchHit.Part;
import com.company.docai.view.document.DocumentRenderers;
import com.company.docai.view.folder.FolderRenderers;
import com.company.docai.view.search.RecentQueries.RecentQuery;
import com.company.docai.view.status.StatusBadges;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.HasComponents;
import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.dom.DomListenerRegistration;
import com.vaadin.flow.spring.annotation.SpringComponent;
import io.jmix.core.Messages;
import io.jmix.core.MetadataTools;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.icon.Icons;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.kit.icon.JmixFontIcon;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The rows of the search palette and the groups that hold them.
 */
@SpringComponent
public class SearchRenderers {

    private static final String REMOVE_SHORTCUT = "Shift+Delete";

    private final UiComponents uiComponents;
    private final Icons icons;
    private final Messages messages;
    private final MetadataTools metadataTools;
    private final StatusBadges statusBadges;
    private final FolderRenderers folderRenderers;
    private final AiSettings aiSettings;
    private final CurrentAuthentication currentAuthentication;

    public SearchRenderers(UiComponents uiComponents, Icons icons, Messages messages, MetadataTools metadataTools,
                           StatusBadges statusBadges, FolderRenderers folderRenderers, AiSettings aiSettings,
                           CurrentAuthentication currentAuthentication) {
        this.uiComponents = uiComponents;
        this.icons = icons;
        this.messages = messages;
        this.metadataTools = metadataTools;
        this.statusBadges = statusBadges;
        this.folderRenderers = folderRenderers;
        this.aiSettings = aiSettings;
        this.currentAuthentication = currentAuthentication;
    }

    /**
     * Returns the plural form of the number for the language: {@code one}, {@code few} or {@code many}. Russian picks
     * it by the last digits of the number; the other languages take {@code one} for 1 only.
     */
    public static String pluralForm(long count, Locale locale) {
        if (!"ru".equals(locale.getLanguage())) {
            return count == 1 ? "one" : "many";
        }

        long lastDigit = count % 10;
        long lastTwoDigits = count % 100;

        if (lastDigit == 1 && lastTwoDigits != 11) {
            return "one";
        }

        if (lastDigit >= 2 && lastDigit <= 4 && (lastTwoDigits < 12 || lastTwoDigits > 14)) {
            return "few";
        }

        return "many";
    }

    /**
     * Builds the row of a found document: its icon, its name with the matched text marked, its folder and its status.
     */
    public Div documentRow(DocumentSearchHit hit, Runnable choice) {
        Document document = hit.document();

        Div row = choiceRow(icons.get(DocumentRenderers.fileIcon(document)), documentName(hit),
                String.join(" / ", hit.folderPath()), choice);

        row.add(statusBadges.create(document.getStatus()));

        return row;
    }

    private Span documentName(DocumentSearchHit hit) {
        String name = hit.document().getName();
        List<Part> parts = hit.snippet() == null ? List.of() : DocumentSearchHit.partsOf(hit.snippet());

        String shown = parts.stream()
                .map(Part::text)
                .collect(Collectors.joining());

        if (!shown.equals(name)) {
            return name(name);
        }

        Span marked = uiComponents.create(Span.class);
        marked.addClassName("search-palette-name");
        addMarked(marked, parts);

        return marked;
    }

    /**
     * Adds the parts of a marked text to the component in their order: a matched part in a span that marks it, the
     * rest as text. The text comes from the users, so it is never put into the page as HTML.
     */
    public void addMarked(HasComponents target, List<Part> parts) {
        for (Part part : parts) {
            if (part.matched()) {
                Span match = uiComponents.create(Span.class);
                match.addClassName("document-search-match");
                match.setText(part.text());
                target.add(match);
            } else {
                target.add(new Text(part.text()));
            }
        }
    }

    /**
     * Builds the row of a folder from its path, greyed as in the tree when the user only passes through it.
     */
    public Div folderRow(Folder folder, List<Folder> path, boolean greyed, Runnable choice) {
        String place = folderRenderers.pathText(path.subList(0, Math.max(0, path.size() - 1)));

        Span name = name(metadataTools.getInstanceName(folder));
        name.setClassName("folder-greyed", greyed);

        return choiceRow(icons.get(JmixFontIcon.FOLDER), name, place, choice);
    }

    /**
     * Builds the row of a tag: its name and the number of its documents.
     */
    public Div tagRow(TagCount tag, Runnable choice) {
        String key = "tagDocuments." + pluralForm(tag.documents(), currentAuthentication.getLocale());

        return choiceRow(icons.get(JmixFontIcon.TAG), name(tag.name()),
                messages.formatMessage(getClass(), key, tag.documents()), choice);
    }

    /**
     * Builds the row of a recent search with the cross that removes it; a search by meaning keeps its wand while the AI
     * is on.
     */
    public Div recentRow(RecentQuery query, Runnable choice, Runnable remove) {
        Component icon = query.ai() && aiSettings.isAvailable()
                ? aiIcon(JmixFontIcon.MAGIC)
                : icons.get(JmixFontIcon.TIME_BACKWARD);

        Div row = choiceRow(icon, name(query.text()), null, choice);
        row.add(removeButton(remove));

        row.getElement().setAttribute("aria-label", query.text());
        row.getElement().setAttribute("aria-keyshortcuts", REMOVE_SHORTCUT);

        return row;
    }

    // a click on the cross is its own, as the row under it would run the search
    private JmixButton removeButton(Runnable remove) {
        JmixButton button = uiComponents.create(JmixButton.class);
        button.addClassName("search-palette-remove");
        button.setIcon(icons.get(JmixFontIcon.CLOSE));
        button.addThemeVariants(ButtonVariant.TERTIARY, ButtonVariant.SMALL);
        button.setTabIndex(-1);
        button.setAriaLabel(messages.getMessage(getClass(), "removeRecentButton.ariaLabel"));
        button.setTooltipText(messages.getMessage(getClass(), "removeRecentButton.tooltip"));

        @SuppressWarnings("unchecked")
        Class<ClickEvent<JmixButton>> click = (Class<ClickEvent<JmixButton>>) (Class<?>) ClickEvent.class;

        // the second click of a double click lands on the cross of the search that moved up under the pointer
        ComponentUtil.addListener(button, click, event -> {
            if (event.getClickCount() <= 1) {
                remove.run();
            }
        }, DomListenerRegistration::stopPropagation);

        return button;
    }

    /**
     * Builds the row of a favorite folder or document: its icon, its name and its place.
     */
    public Div favoriteRow(FavoriteItem item, Runnable choice) {
        if (item.getFolder() != null) {
            return choiceRow(icons.get(JmixFontIcon.FOLDER), name(item.getName()), item.getLocation(), choice);
        }

        return choiceRow(icons.get(JmixFontIcon.FILE_TEXT), name(item.getName()), item.getLocation(), choice);
    }

    /**
     * Builds the row of a question for the assistant, which shows the text.
     */
    public Div assistantRow(String text, Runnable choice) {
        return choiceRow(aiIcon(JmixFontIcon.COMMENTS), name(text), null, choice);
    }

    /**
     * Builds the row that opens the search page with the text, in the mode of the search: by meaning or by words.
     */
    public Div allResultsRow(String text, boolean ai, Runnable choice) {
        Component searchIcon = ai ? aiIcon(JmixFontIcon.MAGIC) : icons.get(JmixFontIcon.SEARCH);

        return choiceRow(searchIcon, name(messages.formatMessage(getClass(), "allResultsRow.text", text)), null,
                choice);
    }

    /**
     * Builds the notice that a group shows instead of its rows.
     */
    public Span notice(String text) {
        Span notice = uiComponents.create(Span.class);
        notice.addClassName("search-palette-message");
        notice.getElement().setAttribute("aria-hidden", "true");
        notice.setText(text);

        return notice;
    }

    /**
     * Builds a group of the list: the heading whose id names the group to a screen reader, and the content under it.
     */
    public Div group(String headingId, String title, List<? extends Component> content) {
        Span heading = uiComponents.create(Span.class);
        heading.setId(headingId);
        heading.addClassName("search-palette-heading");
        heading.setText(title);

        Div group = uiComponents.create(Div.class);
        group.addClassName("search-palette-group");
        group.getElement().setAttribute("role", "group");
        group.getElement().setAttribute("aria-labelledby", headingId);
        group.add(heading);
        content.forEach(group::add);

        return group;
    }

    private Div choiceRow(Component icon, Component name, @Nullable String meta, Runnable choice) {
        Div row = uiComponents.create(Div.class);
        row.addClassName("search-palette-row");
        row.getElement().setAttribute("role", "option");
        row.getElement().setAttribute("aria-selected", "false");
        row.add(icon, name);

        if (meta != null && !meta.isEmpty()) {
            Span place = uiComponents.create(Span.class);
            place.addClassName("search-palette-meta");
            place.setText(meta);
            row.add(place);
        }

        // the second click of a double click lands on what the first one left under the pointer
        row.addClickListener(click -> {
            if (click.getClickCount() <= 1) {
                choice.run();
            }
        });

        return row;
    }

    private Span name(String text) {
        Span name = uiComponents.create(Span.class);
        name.addClassName("search-palette-name");
        name.setText(text);

        return name;
    }

    private Component aiIcon(JmixFontIcon icon) {
        Component component = icons.get(icon);
        component.addClassNames("aura-accent-purple", "search-palette-ai-icon");

        return component;
    }
}
