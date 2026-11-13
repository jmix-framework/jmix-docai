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
import com.google.common.base.Strings;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.card.Card;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import io.jmix.aitoolsflowui.view.chat.AiChatView;
import io.jmix.aitoolsflowui.view.chathub.AiChatHubView;
import io.jmix.core.AccessManager;
import io.jmix.core.usersubstitution.CurrentUserSubstitution;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.accesscontext.UiShowViewContext;
import io.jmix.flowui.component.UiComponentUtils;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.fragment.Fragment;
import io.jmix.flowui.fragment.FragmentDescriptor;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.navigation.RouteSupport;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
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
    private CurrentUserSubstitution currentUserSubstitution;

    @Autowired
    private DashboardRenderers dashboardRenderers;

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
            String deadline = dashboardRenderers.longDate(dates.getFirst().date(), now.toLocalDate());

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
                .map(upcoming -> dashboardRenderers.upcomingRow(upcoming, today,
                        () -> documentOpenHandler.accept(upcoming.document())))
                .toList();

        show(upcomingBox, rows, messageBundle.getMessage("upcomingEmpty.text"));
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
        Runnable fillWithAi = aiSettings.isAvailable() && untyped.fillable()
                ? () -> fillWithAiHandler.accept(untyped.document())
                : null;

        return dashboardRenderers.untypedRow(untyped, () -> documentOpenHandler.accept(untyped.document()),
                fillWithAi);
    }

    private void showActivities(List<Activity> activities, ZonedDateTime now) {
        List<Div> rows = activities.stream()
                .map(activity -> dashboardRenderers.activityRow(activity, now,
                        () -> documentOpenHandler.accept(activity.document())))
                .toList();

        show(activityBox, rows, messageBundle.getMessage("activityEmpty.text"));
    }

    private void showChats(List<RecentChat> chats, ZonedDateTime now) {
        List<Div> rows = chats.stream()
                .map(chat -> dashboardRenderers.chatRow(chat, now, () -> openChat(chat)))
                .toList();

        show(chatsBox, rows, messageBundle.getMessage("chatsEmpty.text"));
    }

    private void openChat(RecentChat chat) {
        viewNavigators.view(UiComponentUtils.getCurrentView(), AiChatView.class)
                .withRouteParameters(routeSupport.createRouteParameters(AiChatView.ROUTE_PARAM_ID, chat.id()))
                .navigate();
    }

    private void showSummary(Summary summary) {
        summaryBox.removeAll();

        if (summary.types().isEmpty() && summary.statuses().isEmpty()) {
            summaryBox.add(dashboardRenderers.emptyLine(messageBundle.getMessage("summaryEmpty.text")));
            return;
        }

        String types = messageBundle.getMessage("typesHeading.text");
        String statuses = messageBundle.getMessage("statusesHeading.text");

        dashboardRenderers.summaryGroup(types, summary.types())
                .ifPresent(summaryBox::add);

        dashboardRenderers.summaryGroup(statuses, summary.statuses())
                .ifPresent(summaryBox::add);
    }

    // a block without rows says so in a line of its own
    private void show(VerticalLayout box, List<Div> rows, String emptyText) {
        box.removeAll();

        if (rows.isEmpty()) {
            box.add(dashboardRenderers.emptyLine(emptyText));
            return;
        }

        rows.forEach(box::add);
    }
}
