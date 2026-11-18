package com.company.docai.view.dashboard;

import com.company.docai.app.service.dashboard.Activity;
import com.company.docai.app.service.dashboard.RecentChat;
import com.company.docai.app.service.dashboard.Summary;
import com.company.docai.app.service.dashboard.UntypedDocuments;
import com.company.docai.app.service.dashboard.UpcomingDate;
import com.company.docai.view.document.DocumentRenderers;
import com.company.docai.view.user.UserRenderers;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.avatar.Avatar;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.spring.annotation.SpringComponent;
import io.jmix.core.Messages;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.icon.Icons;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.kit.icon.JmixFontIcon;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * The rows of the blocks of the dashboard, built from the records of the dashboard service.
 */
@SpringComponent
public class DashboardRenderers {

    private final UiComponents uiComponents;
    private final Icons icons;
    private final Messages messages;
    private final DocumentRenderers documentRenderers;
    private final UserRenderers userRenderers;
    private final CurrentAuthentication currentAuthentication;

    public DashboardRenderers(UiComponents uiComponents, Icons icons, Messages messages,
                              DocumentRenderers documentRenderers, UserRenderers userRenderers,
                              CurrentAuthentication currentAuthentication) {
        this.uiComponents = uiComponents;
        this.icons = icons;
        this.messages = messages;
        this.documentRenderers = documentRenderers;
        this.userRenderers = userRenderers;
        this.currentAuthentication = currentAuthentication;
    }

    /**
     * Returns the date with the full name of the month, with the year when it is not the current one.
     */
    public String longDate(LocalDate date, LocalDate today) {
        return dateText(date, today, messages.getMessage(getClass(), "longDate.format"),
                messages.getMessage(getClass(), "longDateWithYear.format"));
    }

    /**
     * Builds the row of an upcoming date: the date, the name of the document, the field and the folder.
     */
    public Div upcomingRow(UpcomingDate upcoming, LocalDate today, Runnable open) {
        return row(open, dateOf(upcoming, today),
                texts(upcoming.document().getName(), upcoming.field() + " · " + upcoming.folder()));
    }

    private Div texts(String name, String details) {
        Div texts = uiComponents.create(Div.class);
        texts.addClassName("dashboard-row-texts");
        texts.add(text(name, "dashboard-row-name"), text(details, "dashboard-row-details"));

        return texts;
    }

    private Component dateOf(UpcomingDate upcoming, LocalDate today) {
        String date = dateText(upcoming.date(), today, messages.getMessage(getClass(), "shortDate.format"),
                messages.getMessage(getClass(), "shortDateWithYear.format"));

        if (!upcoming.soon()) {
            return text(date, "dashboard-row-date");
        }

        Badge badge = uiComponents.create(Badge.class);
        badge.setText(date);
        badge.addClassNames("dashboard-row-date", "aura-accent-yellow");

        return badge;
    }

    /**
     * Builds the row of a document without a type, with Fill with AI after it when the dashboard gives its callback.
     */
    public Div untypedRow(UntypedDocuments.Row untyped, Runnable open, @Nullable Runnable fillWithAi) {
        String details = untyped.folder() + " · " + documentRenderers.userDate(untyped.uploaded());
        Div row = row(open, texts(untyped.document().getName(), details));

        if (fillWithAi != null) {
            JmixButton fill = uiComponents.create(JmixButton.class);
            fill.setText(messages.getMessage(getClass(), "fillWithAiButton.text"));

            fill.setAriaLabel(messages.formatMessage(getClass(), "fillWithAiButton.ariaLabel",
                    untyped.document().getName()));

            fill.setIcon(icons.get(JmixFontIcon.MAGIC));
            fill.addThemeVariants(ButtonVariant.TERTIARY, ButtonVariant.SMALL);
            fill.addClassName("aura-accent-purple");
            fill.addClickListener(click -> fillWithAi.run());
            row.add(fill);
        }

        return row;
    }

    /**
     * Builds the row of an event, without verbs that would need the gender of the person.
     */
    public Div activityRow(Activity activity, ZonedDateTime now, Runnable open) {
        String name = userRenderers.shortName(activity.author(), activity.username());
        String event = " · " + eventOf(activity) + " · " + activity.document().getName();

        Span line = uiComponents.create(Span.class);
        line.addClassName("dashboard-row-name");
        line.add(text(name, "dashboard-row-author"), text(event, "dashboard-row-event"));

        return row(open, avatarOf(activity, name), line, timeOf(activity.time(), now));
    }

    private String eventOf(Activity activity) {
        return switch (activity.kind()) {
            case UPLOAD -> messages.getMessage(getClass(), "event.upload");
            case VERSION -> messages.formatMessage(getClass(), "event.version", activity.number());
            case COMMENT -> messages.getMessage(getClass(), "event.comment");
        };
    }

    private Avatar avatarOf(Activity activity, String name) {
        Avatar avatar = uiComponents.create(Avatar.class);
        userRenderers.fillAvatar(avatar, name, activity.author(), activity.username());
        avatar.addClassName("dashboard-row-avatar");
        avatar.getElement().setAttribute("tabindex", "-1");
        avatar.getElement().setAttribute("aria-hidden", "true");

        return avatar;
    }

    /**
     * Builds the row of an own recent chat: its icon, its title and how long ago its last message came.
     */
    public Div chatRow(RecentChat chat, ZonedDateTime now, Runnable open) {
        return row(open, chatIcon(chat), text(chat.title(), "dashboard-row-name"), timeOf(chat.time(), now));
    }

    private Component chatIcon(RecentChat chat) {
        Component icon = icons.get(chat.discussion() ? JmixFontIcon.FILE_TEXT : JmixFontIcon.COMMENTS);
        icon.addClassNames("aura-accent-purple", "dashboard-ai-icon");

        if (chat.discussion()) {
            icon.getElement().setAttribute("role", "img");
            icon.getElement().setAttribute("aria-label", messages.getMessage(getClass(), "discussionIcon.ariaLabel"));
        }

        return icon;
    }

    /**
     * Builds a group of the summary, or nothing for a group without rows.
     */
    public Optional<Div> summaryGroup(String title, List<Summary.Row> rows) {
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        Div group = uiComponents.create(Div.class);
        group.addClassName("dashboard-summary-group");

        H4 heading = uiComponents.create(H4.class);
        heading.addClassName("dashboard-summary-heading");
        heading.setText(title);
        group.add(heading);

        long largest = rows.stream()
                .mapToLong(Summary.Row::documents)
                .max()
                .orElse(1);

        for (Summary.Row row : rows) {
            Div fill = uiComponents.create(Div.class);
            fill.addClassName("dashboard-bar-fill");
            fill.getStyle().set("width", 100 * row.documents() / largest + "%");

            if (row.color() != null) {
                fill.getStyle().set("background-color", row.color());
            }

            Div bar = uiComponents.create(Div.class);
            bar.addClassName("dashboard-bar");
            bar.getElement().setAttribute("aria-hidden", "true");
            bar.add(fill);

            String name = row.name() == null ? messages.getMessage(getClass(), "untypedRow.text") : row.name();
            String count = String.valueOf(row.documents());

            Div line = uiComponents.create(Div.class);
            line.addClassName("dashboard-summary-row");
            line.add(text(name, "dashboard-summary-name"), bar, text(count, "dashboard-summary-count"));
            group.add(line);
        }

        return Optional.of(group);
    }

    /**
     * Builds the line that a block without rows shows instead of them.
     */
    public Span emptyLine(String text) {
        return text(text, "dashboard-empty");
    }

    private Div row(Runnable open, Component... content) {
        NativeButton button = uiComponents.create(NativeButton.class);
        button.addClassName("dashboard-row-open");
        button.add(content);
        button.addClickListener(click -> open.run());

        Div row = uiComponents.create(Div.class);
        row.addClassName("dashboard-row");
        row.add(button);

        return row;
    }

    private Span timeOf(OffsetDateTime time, ZonedDateTime now) {
        Span span = text(ago(time, now), "dashboard-row-time");
        span.setTitle(documentRenderers.userDateTime(time));

        return span;
    }

    private String ago(OffsetDateTime time, ZonedDateTime now) {
        ZonedDateTime at = time.atZoneSameInstant(now.getZone());
        long minutes = Duration.between(at, now).toMinutes();
        long days = ChronoUnit.DAYS.between(at.toLocalDate(), now.toLocalDate());

        if (minutes < 1) {
            return messages.getMessage(getClass(), "justNow");
        }

        if (minutes < 60) {
            return messages.formatMessage(getClass(), "minutesAgo", minutes);
        }

        if (days == 0) {
            return messages.formatMessage(getClass(), "hoursAgo", minutes / 60);
        }

        return days == 1
                ? messages.getMessage(getClass(), "yesterday")
                : documentRenderers.userDate(time);
    }

    private String dateText(LocalDate date, LocalDate today, String pattern, String patternWithYear) {
        String format = date.getYear() == today.getYear() ? pattern : patternWithYear;

        return DateTimeFormatter.ofPattern(format, currentAuthentication.getLocale())
                .format(date);
    }

    private Span text(String text, String className) {
        Span span = uiComponents.create(Span.class);
        span.addClassName(className);
        span.setText(text);

        return span;
    }
}
