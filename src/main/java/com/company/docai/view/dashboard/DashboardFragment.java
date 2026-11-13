package com.company.docai.view.dashboard;

import com.company.docai.ai.AiSettings;
import com.company.docai.app.service.dashboard.Activity;
import com.company.docai.app.service.dashboard.DashboardService;
import com.company.docai.app.service.dashboard.RecentChat;
import com.company.docai.app.service.dashboard.Summary;
import com.company.docai.app.service.dashboard.UntypedDocuments;
import com.company.docai.app.service.dashboard.UpcomingDate;
import com.company.docai.model.document.Document;
import com.company.docai.model.user.User;
import com.company.docai.view.document.DocumentRenderers;
import com.google.common.base.Strings;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.avatar.Avatar;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.card.Card;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import io.jmix.aitoolsflowui.view.chat.AiChatView;
import io.jmix.aitoolsflowui.view.chathub.AiChatHubView;
import io.jmix.core.AccessManager;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.core.usersubstitution.CurrentUserSubstitution;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.accesscontext.UiShowViewContext;
import io.jmix.flowui.component.UiComponentUtils;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.fragment.Fragment;
import io.jmix.flowui.fragment.FragmentDescriptor;
import io.jmix.flowui.icon.Icons;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.kit.icon.JmixFontIcon;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.navigation.RouteSupport;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The home page in place of a view at the root of the main view: a greeting with a field and ready questions for
 * the AI assistant, the nearest due dates of the fields of the document types, the documents without a type, what
 * other people did lately, the own recent chats with the assistant and a summary by types and statuses. It loads
 * nothing by itself: the main view calls {@link #refresh()} whenever it shows it. The parts of the AI show only
 * while the AI is on and the user may open its chat.
 */
@FragmentDescriptor("dashboard-fragment.xml")
public class DashboardFragment extends Fragment<VerticalLayout> {

    private static final String ASSISTANT_CHAT_VIEW = "aitls_AiChatView";

    // the number of the user colors of an avatar
    private static final int AVATAR_COLORS = 7;

    @ViewComponent
    private H2 greeting;
    @ViewComponent
    private Span summaryLine;
    @ViewComponent
    private VerticalLayout askBox;
    @ViewComponent
    private TypedTextField<String> questionField;
    @ViewComponent
    private JmixButton askButton;

    @ViewComponent
    private VerticalLayout upcomingBox;
    @ViewComponent
    private H3 untypedTitle;
    @ViewComponent
    private VerticalLayout untypedBox;
    @ViewComponent
    private VerticalLayout activityBox;
    @ViewComponent
    private Card chatsCard;
    @ViewComponent
    private VerticalLayout chatsBox;
    @ViewComponent
    private VerticalLayout summaryBox;
    @ViewComponent
    private MessageBundle messageBundle;

    @Autowired
    private DashboardService dashboardService;
    @Autowired
    private AiSettings aiSettings;
    @Autowired
    private AccessManager accessManager;
    @Autowired
    private CurrentAuthentication currentAuthentication;
    @Autowired
    private CurrentUserSubstitution currentUserSubstitution;

    @Autowired
    private UiComponents uiComponents;
    @Autowired
    private Icons icons;
    @Autowired
    private DocumentRenderers documentRenderers;

    @Autowired
    private ViewNavigators viewNavigators;
    @Autowired
    private RouteSupport routeSupport;

    private Consumer<Document> documentOpenHandler = document -> {
    };
    private Consumer<Document> fillWithAiHandler = document -> {
    };
    private Consumer<String> assistantHandler = question -> {
    };
    private boolean assistantAvailable;

    @Subscribe
    public void onReady(final ReadyEvent event) {
        // the questions and the chats belong to the chat of the assistant, so they need the AI and that chat
        UiShowViewContext assistantChat = new UiShowViewContext(ASSISTANT_CHAT_VIEW);
        accessManager.applyRegisteredConstraints(assistantChat);
        assistantAvailable = aiSettings.isAvailable() && assistantChat.isPermitted();

        askBox.setVisible(assistantAvailable);
        chatsCard.setVisible(assistantAvailable);

        // Enter in the field asks as the button does
        askButton.addClickShortcut(Key.ENTER)
                .listenOn(questionField);
    }

    @Subscribe(id = "askButton", subject = "clickListener")
    public void onAskButtonClick(final ClickEvent<JmixButton> event) {
        ask(questionField.getValue());
    }

    @Subscribe(id = "question1Button", subject = "clickListener")
    public void onQuestion1ButtonClick(final ClickEvent<JmixButton> event) {
        ask(event.getSource().getText());
    }

    @Subscribe(id = "question2Button", subject = "clickListener")
    public void onQuestion2ButtonClick(final ClickEvent<JmixButton> event) {
        ask(event.getSource().getText());
    }

    @Subscribe(id = "question3Button", subject = "clickListener")
    public void onQuestion3ButtonClick(final ClickEvent<JmixButton> event) {
        ask(event.getSource().getText());
    }

    @Subscribe(id = "allChatsButton", subject = "clickListener")
    public void onAllChatsButtonClick(final ClickEvent<JmixButton> event) {
        viewNavigators.view(UiComponentUtils.getCurrentView(), AiChatHubView.class)
                .navigate();
    }

    /**
     * Receives a document of a block to open in its card.
     */
    public void setDocumentOpenHandler(Consumer<Document> documentOpenHandler) {
        this.documentOpenHandler = documentOpenHandler;
    }

    /**
     * Receives a document without a type to open in its card with Fill with AI started.
     */
    public void setFillWithAiHandler(Consumer<Document> fillWithAiHandler) {
        this.fillWithAiHandler = fillWithAiHandler;
    }

    /**
     * Receives a question for the assistant, to open its chat with the question as the first message.
     */
    public void setAssistantHandler(Consumer<String> assistantHandler) {
        this.assistantHandler = assistantHandler;
    }

    /**
     * Returns the title of the dashboard, which the header of the main view shows over it.
     */
    public String getTitle() {
        return messageBundle.getMessage("dashboardFragment.title");
    }

    /**
     * Reads every block again. The main view calls it whenever it shows the dashboard and after a card opened from
     * the dashboard closes.
     */
    public void refresh() {
        ZonedDateTime now = dashboardService.now();
        List<UpcomingDate> dates = dashboardService.upcomingDates();
        UntypedDocuments untyped = dashboardService.untypedDocuments();

        showWelcome(now, dates, untyped);
        showUpcomingDates(dates, now.toLocalDate());
        showUntyped(untyped);
        showActivities(dashboardService.activities(), now);
        showSummary(dashboardService.summary());

        // the chats come from the add-on of the assistant
        if (assistantAvailable) {
            showChats(dashboardService.recentChats(), now);
        }
    }

    // an empty question asks nothing; the field is emptied for the next visit
    private void ask(@Nullable String question) {
        String text = Strings.nullToEmpty(question).strip();

        if (!text.isEmpty()) {
            questionField.clear();
            assistantHandler.accept(text);
        }
    }

    // the greeting by the part of the day in the time zone of the user, and one line on what needs attention
    private void showWelcome(ZonedDateTime now, List<UpcomingDate> dates, UntypedDocuments untyped) {
        UserDetails user = currentUserSubstitution.getEffectiveUser();

        String name = user instanceof User person && !Strings.isNullOrEmpty(person.getFirstName())
                ? person.getFirstName()
                : user.getUsername();

        greeting.setText(greetingOf(now.getHour(), name));

        List<String> parts = new ArrayList<>();

        if (!dates.isEmpty()) {
            String deadline = dateText(dates.getFirst().date(), now.toLocalDate(),
                    messageBundle.getMessage("longDate.format"), messageBundle.getMessage("longDateWithYear.format"));

            parts.add(messageBundle.formatMessage("summaryLine.deadline", deadline));
        }

        if (untyped.count() > 0) {
            parts.add(messageBundle.formatMessage("summaryLine.untyped", untyped.count()));
        }

        summaryLine.setText(String.join(" · ", parts));
        summaryLine.setVisible(!parts.isEmpty());
    }

    // morning from 5, afternoon from 12, evening from 17, night from 23
    private String greetingOf(int hour, String name) {
        if (hour < 5 || hour >= 23) {
            return messageBundle.formatMessage("greeting.night", name);
        }

        if (hour < 12) {
            return messageBundle.formatMessage("greeting.morning", name);
        }

        return hour < 17
                ? messageBundle.formatMessage("greeting.afternoon", name)
                : messageBundle.formatMessage("greeting.evening", name);
    }

    private void showUpcomingDates(List<UpcomingDate> dates, LocalDate today) {
        List<Div> rows = dates.stream()
                .map(upcoming -> row(() -> documentOpenHandler.accept(upcoming.document()),
                        dateOf(upcoming, today),
                        textsOf(upcoming)))
                .toList();

        show(upcomingBox, rows, messageBundle.getMessage("upcomingEmpty.text"));
    }

    // the field and the folder follow the name, and on a narrow screen they go under it, so the name keeps the row
    private Div textsOf(UpcomingDate upcoming) {
        Div texts = uiComponents.create(Div.class);
        texts.addClassName("dashboard-row-texts");

        texts.add(text(upcoming.document().getName(), "dashboard-row-name"),
                text(upcoming.field() + " · " + upcoming.folder(), "dashboard-row-details"));

        return texts;
    }

    // a date within a week stands out in a yellow badge
    private Component dateOf(UpcomingDate upcoming, LocalDate today) {
        String date = dateText(upcoming.date(), today, messageBundle.getMessage("shortDate.format"),
                messageBundle.getMessage("shortDateWithYear.format"));

        if (!upcoming.soon()) {
            return text(date, "dashboard-row-date");
        }

        Badge badge = uiComponents.create(Badge.class);
        badge.setText(date);
        badge.addClassNames("dashboard-row-date", "aura-accent-yellow");

        return badge;
    }

    // the count follows the title in the grey of a count
    private void showUntyped(UntypedDocuments untyped) {
        untypedTitle.getElement().setAttribute("data-count", String.valueOf(untyped.count()));

        List<Div> rows = untyped.latest()
                .stream()
                .map(this::untypedRow)
                .toList();

        show(untypedBox, rows, messageBundle.getMessage("untypedEmpty.text"));
    }

    // the card offers Fill with AI on the same terms, and the button opens it with the filling started
    private Div untypedRow(UntypedDocuments.Row untyped) {
        String details = untyped.folder() + " · " + documentRenderers.userDate(untyped.uploaded());

        Div row = row(() -> documentOpenHandler.accept(untyped.document()),
                text(untyped.document().getName(), "dashboard-row-name"),
                text(details, "dashboard-row-details"));

        if (aiSettings.isAvailable() && untyped.fillable()) {
            JmixButton fill = uiComponents.create(JmixButton.class);
            fill.setText(messageBundle.getMessage("fillWithAiButton.text"));
            // the buttons of the rows read the same, so each names its document to a screen reader
            fill.setAriaLabel(messageBundle.formatMessage("fillWithAiButton.ariaLabel", untyped.document().getName()));
            fill.setIcon(icons.get(JmixFontIcon.MAGIC));
            fill.addClassNames("aura-accent-purple", "ai-button");
            fill.addClickListener(click -> fillWithAiHandler.accept(untyped.document()));
            row.add(fill);
        }

        return row;
    }

    private void showActivities(List<Activity> activities, ZonedDateTime now) {
        List<Div> rows = activities.stream()
                .map(activity -> activityRow(activity, now))
                .toList();

        show(activityBox, rows, messageBundle.getMessage("activityEmpty.text"));
    }

    // who, what and on which document, without verbs that would need the gender of the person
    private Div activityRow(Activity activity, ZonedDateTime now) {
        String name = nameOf(activity);
        String event = " · " + eventOf(activity) + " · " + activity.document().getName();

        Span line = uiComponents.create(Span.class);
        line.addClassName("dashboard-row-name");
        line.add(text(name, "dashboard-row-author"), text(event, "dashboard-row-event"));

        return row(() -> documentOpenHandler.accept(activity.document()), avatarOf(activity, name), line,
                timeOf(activity.time(), now));
    }

    private String eventOf(Activity activity) {
        return switch (activity.kind()) {
            case UPLOAD -> messageBundle.getMessage("event.upload");
            case VERSION -> messageBundle.formatMessage("event.version", activity.number());
            case COMMENT -> messageBundle.getMessage("event.comment");
        };
    }

    // the first and the last name, without the username in brackets of the lists: on a glance the avatar beside it is
    // enough; a person without a name, or deleted since, keeps the username
    private String nameOf(Activity activity) {
        User author = activity.author();

        if (author == null) {
            return activity.username();
        }

        String name = Strings.nullToEmpty(author.getFirstName()) + " " + Strings.nullToEmpty(author.getLastName());

        return name.isBlank() ? activity.username() : name.strip();
    }

    // the initials of the first and the last name, as in the comments: the avatar would take every word of the name.
    // The row names the person already, so the avatar is hidden from a screen reader
    private Avatar avatarOf(Activity activity, String name) {
        User author = activity.author();

        String initials = author != null && !Strings.isNullOrEmpty(author.getFirstName())
                && !Strings.isNullOrEmpty(author.getLastName())
                ? "%c%c".formatted(author.getFirstName().charAt(0), author.getLastName().charAt(0))
                : activity.username().substring(0, 1).toUpperCase(Locale.ROOT);

        Avatar avatar = uiComponents.create(Avatar.class);
        avatar.setName(name);
        avatar.setAbbreviation(initials);
        avatar.setColorIndex(Math.floorMod(activity.username().hashCode(), AVATAR_COLORS));
        avatar.addClassName("dashboard-row-avatar");
        avatar.getElement().setAttribute("tabindex", "-1");
        avatar.getElement().setAttribute("aria-hidden", "true");

        return avatar;
    }

    private void showChats(List<RecentChat> chats, ZonedDateTime now) {
        List<Div> rows = chats.stream()
                .map(chat -> row(() -> openChat(chat), chatIcon(chat), text(chat.title(), "dashboard-row-name"),
                        timeOf(chat.time(), now)))
                .toList();

        show(chatsBox, rows, messageBundle.getMessage("chatsEmpty.text"));
    }

    // a discussion of chosen documents wears the icon of a document instead of the icon of a chat
    private Component chatIcon(RecentChat chat) {
        Component icon = icons.get(chat.discussion() ? JmixFontIcon.FILE_TEXT : JmixFontIcon.COMMENTS);
        icon.addClassNames("aura-accent-purple", "dashboard-ai-icon");

        if (chat.discussion()) {
            icon.getElement().setAttribute("role", "img");
            icon.getElement().setAttribute("aria-label", messageBundle.getMessage("discussionIcon.ariaLabel"));
        }

        return icon;
    }

    private void openChat(RecentChat chat) {
        viewNavigators.view(UiComponentUtils.getCurrentView(), AiChatView.class)
                .withRouteParameters(routeSupport.createRouteParameters(AiChatView.ROUTE_PARAM_ID, chat.id()))
                .navigate();
    }

    private void showSummary(Summary summary) {
        summaryBox.removeAll();

        if (summary.types().isEmpty() && summary.statuses().isEmpty()) {
            summaryBox.add(text(messageBundle.getMessage("summaryEmpty.text"), "dashboard-empty"));
            return;
        }

        addSummaryGroup(messageBundle.getMessage("typesHeading.text"), summary.types());
        addSummaryGroup(messageBundle.getMessage("statusesHeading.text"), summary.statuses());
    }

    // a bar shows the number against the largest one of its group; a status keeps the color of its badge
    private void addSummaryGroup(String title, List<Summary.Row> rows) {
        if (rows.isEmpty()) {
            return;
        }

        H4 heading = uiComponents.create(H4.class);
        heading.addClassName("dashboard-summary-heading");
        heading.setText(title);
        summaryBox.add(heading);

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

            // the number stands next to the bar, so a screen reader skips the bar
            Div bar = uiComponents.create(Div.class);
            bar.addClassName("dashboard-bar");
            bar.getElement().setAttribute("aria-hidden", "true");
            bar.add(fill);

            String name = row.name() == null ? messageBundle.getMessage("untypedRow.text") : row.name();
            String count = String.valueOf(row.documents());

            Div line = uiComponents.create(Div.class);
            line.addClassName("dashboard-summary-row");
            line.add(text(name, "dashboard-summary-name"), bar, text(count, "dashboard-summary-count"));
            summaryBox.add(line);
        }
    }

    // a block without rows says so in a line of its own
    private void show(VerticalLayout box, List<Div> rows, String emptyText) {
        box.removeAll();

        if (rows.isEmpty()) {
            box.add(text(emptyText, "dashboard-empty"));
            return;
        }

        rows.forEach(box::add);
    }

    // a row opens what it shows; a button after it, such as Fill with AI, is no part of it
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

    // within the hour in minutes, today in hours, then yesterday, then the date; the title gives the moment
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
            return messageBundle.getMessage("justNow");
        }

        if (minutes < 60) {
            return messageBundle.formatMessage("minutesAgo", minutes);
        }

        if (days == 0) {
            return messageBundle.formatMessage("hoursAgo", minutes / 60);
        }

        return days == 1
                ? messageBundle.getMessage("yesterday")
                : documentRenderers.userDate(time);
    }

    // "30 Nov" in a row and "30 November" in the summary line, with the year when it is not the current one
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
