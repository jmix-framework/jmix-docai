package com.company.dms.view.repository;

import com.company.dms.ai.assistant.DocumentDiscussion;
import com.company.dms.model.document.Document;
import com.company.dms.view.document.DocumentDetailView;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dnd.DropEvent;
import com.vaadin.flow.component.dnd.DropTarget;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeLeaveEvent;
import com.vaadin.flow.shared.Registration;
import io.jmix.aichat.component.chat.AiChat;
import io.jmix.aichat.component.chat.GenerationFailedEvent;
import io.jmix.aichat.component.chat.GenerationStateChangeEvent;
import io.jmix.aichat.component.messagelist.GenerationState;
import io.jmix.aichat.component.messagelist.ThinkingStatusItem;
import io.jmix.aichat.data.SimpleAiChatMessage;
import io.jmix.aitools.tool.AiToolStatusUpdate;
import io.jmix.aitoolsflowui.AiToolsFlowuiProperties;
import io.jmix.aitoolsflowui.view.chat.AiChatView;
import io.jmix.core.DataManager;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.backgroundtask.BackgroundTask;
import io.jmix.flowui.backgroundtask.BackgroundTaskHandler;
import io.jmix.flowui.backgroundtask.BackgroundWorker;
import io.jmix.flowui.backgroundtask.TaskLifeCycle;
import io.jmix.flowui.component.sidedialog.SideDialog;
import io.jmix.flowui.fragment.Fragment;
import io.jmix.flowui.fragment.FragmentDescriptor;
import io.jmix.flowui.fragment.FragmentUtils;
import io.jmix.flowui.icon.Icons;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.kit.component.sidedialog.SideDialogPosition;
import io.jmix.flowui.kit.icon.JmixFontIcon;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.navigation.RouteSupport;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * The window of the repository in which a person discusses a selection of documents with the assistant: the
 * selection, which takes the rows of a document table dropped on the window, and the chat, whose conversation
 * the history of the assistant keeps from the first question on.
 */
@FragmentDescriptor("document-discussion-fragment.xml")
public class DocumentDiscussionFragment extends Fragment<VerticalLayout> {

    private static final Logger log = LoggerFactory.getLogger(DocumentDiscussionFragment.class);

    private static final String WIDTH = "40%";
    private static final String MIN_WIDTH = "24em";

    @ViewComponent
    private H4 discussionTitle;
    @ViewComponent
    private JmixButton fullChatButton;
    @ViewComponent
    private HorizontalLayout selection;
    @ViewComponent
    private Span dropHint;
    @ViewComponent
    private AiChat<SimpleAiChatMessage> chat;
    @ViewComponent
    private MessageBundle messageBundle;

    @Autowired
    private DocumentDiscussion discussion;
    @Autowired
    private DataManager dataManager;
    @Autowired
    private BackgroundWorker backgroundWorker;
    @Autowired
    private AiToolsFlowuiProperties aiToolsProperties;
    @Autowired
    private Dialogs dialogs;
    @Autowired
    private Notifications notifications;
    @Autowired
    private UiComponents uiComponents;
    @Autowired
    private Icons icons;
    @Autowired
    private ViewNavigators viewNavigators;
    @Autowired
    private RouteSupport routeSupport;

    @Nullable
    private SideDialog window;
    @Nullable
    private ThinkingStatusItem lastStatus;
    @Nullable
    private Registration cardLinks;
    private Consumer<Document> cardOpener = document -> {
    };

    @Subscribe
    public void onReady(final ReadyEvent event) {
        chat.setLlmProvider(request -> Flux.create(answer -> ask(request.userMessage(), answer)));
        DropTarget.create(getContent())
                .addDropListener(this::onDrop);
    }

    /**
     * Opens the window on the right of the view, which stays usable meanwhile.
     */
    public void open() {
        window = dialogs.createSideDialog()
                .withSideDialogPosition(SideDialogPosition.RIGHT)
                .withHorizontalSize(WIDTH)
                .withHorizontalMinSize(MIN_WIDTH)
                .withModal(false)
                // the table stays in use: a click on it selects documents and a drag brings them here
                .withCloseOnOutsideClick(false)
                .withOpenedChangeListener(event -> {
                    if (!event.isOpened()) {
                        onClosed();
                    }
                })
                .withContentComponents(this)
                .build();

        window.open();
        // a link of an answer leads to the card page, and the window does not stay over another view: while it is
        // open, the card opens over it, as from a chip
        cardLinks = UI.getCurrent().addBeforeLeaveListener(this::openLinkedCard);
    }

    /**
     * Returns whether the window is open. A closed window takes no more documents.
     */
    public boolean isOpened() {
        return window != null && window.isOpened();
    }

    /**
     * Closes the window. A conversation that has begun stays in the history of the assistant.
     */
    public void close() {
        if (window != null) {
            window.close();
        }
    }

    /**
     * Receives a click on a document of the selection, which opens the card of the document.
     */
    public void setCardOpener(Consumer<Document> cardOpener) {
        this.cardOpener = cardOpener;
    }

    /**
     * Adds the documents that are not in the selection yet, while it has room for them.
     */
    public void add(Collection<Document> documents) {
        if (!discussion.add(documents)) {
            notifications.create(messageBundle.formatMessage("tooManyDocuments", discussion.getMaxDocuments()))
                    .withType(Notifications.Type.WARNING)
                    .show();
        }

        showSelection();
    }

    @Subscribe("summaryButton")
    public void onSummaryButtonClick(final ClickEvent<JmixButton> event) {
        chat.prompt(messageBundle.getMessage("summaryButton.prompt"));
    }

    @Subscribe("closeButton")
    public void onCloseButtonClick(final ClickEvent<JmixButton> event) {
        close();
    }

    @Subscribe("fullChatButton")
    public void onFullChatButtonClick(final ClickEvent<JmixButton> event) {
        discussion.getConversation().ifPresent(conversation -> {
            close();

            viewNavigators.view(FragmentUtils.getHostView(this), AiChatView.class)
                    .withRouteParameters(routeSupport.createRouteParameters(AiChatView.ROUTE_PARAM_ID,
                            conversation.getId()))
                    .navigate();
        });
    }

    // an answer may take out of the selection a document the person may no longer open, and the first one names
    // the window after the conversation; the full chat waits for the answer to be in the conversation
    @Subscribe("chat")
    public void onChatGenerationStateChange(final GenerationStateChangeEvent<SimpleAiChatMessage> event) {
        showSelection();
        discussion.getConversation().ifPresent(conversation -> discussionTitle.setText(conversation.getTitle()));
        fullChatButton.setEnabled(discussion.getConversation().isPresent()
                && event.getNewState() != GenerationState.GENERATING);
    }

    @Subscribe("chat")
    public void onChatGenerationFailed(final GenerationFailedEvent<SimpleAiChatMessage> event) {
        log.warn("The assistant did not answer in the discussion: {}", event.getError().toString());

        notifications.create(messageBundle.getMessage("discussionFailed"))
                .withType(Notifications.Type.ERROR)
                .show();
    }

    // a closed dialog leaves the page only after the browser has played the closing, and the chat stops its answer
    // only then; a closed window stops it at once, and the links of answers lead to the card page again
    private void onClosed() {
        chat.stop();

        if (cardLinks != null) {
            cardLinks.remove();
            cardLinks = null;
        }
    }

    private void openLinkedCard(BeforeLeaveEvent event) {
        if (!DocumentDetailView.class.equals(event.getNavigationTarget())) {
            return;
        }

        event.getRouteParameters()
                .get("id")
                .flatMap(this::linkedDocument)
                .ifPresent(document -> {
                    BeforeLeaveEvent.ContinueNavigationAction navigation = event.postpone();
                    UI ui = event.getUI();
                    // a cancel right away would take the postponement back; cancelled before the response, the
                    // navigation stays here and the browser drops it too
                    ui.beforeClientResponse(ui, context -> navigation.cancel());
                    cardOpener.accept(document);
                });
    }

    // an answer writes the id of a document as it is; another address, such as the card of a new document, goes on
    private Optional<Document> linkedDocument(String id) {
        UUID documentId;

        try {
            documentId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }

        return dataManager.load(Document.class)
                .id(documentId)
                .optional();
    }

    // the table puts the rows it drags into the drag data: all the selected rows when one of them is dragged
    private void onDrop(DropEvent<VerticalLayout> event) {
        if (event.getDragData().orElse(null) instanceof Collection<?> rows) {
            add(rows.stream()
                    .filter(Document.class::isInstance)
                    .map(Document.class::cast)
                    .toList());
        }
    }

    // the question runs as a background task of the view, as in the chat of the add-on: as the person who asks,
    // within the time the add-on gives an answer
    private void ask(String question, FluxSink<String> answer) {
        BackgroundTaskHandler<String> handler = backgroundWorker.handle(new QuestionTask(question, answer));
        answer.onCancel(handler::cancel);
        handler.execute();
    }

    private void showSelection() {
        selection.removeAll();
        List<Document> documents = discussion.getDocuments();

        if (documents.isEmpty()) {
            selection.add(dropHint);
        } else {
            documents.forEach(document -> selection.add(chip(document)));
        }
    }

    private Component chip(Document document) {
        JmixButton name = uiComponents.create(JmixButton.class);
        name.setText(document.getName());
        name.setTitle(document.getName());
        name.addThemeVariants(ButtonVariant.TERTIARY, ButtonVariant.SMALL);
        name.addClickListener(click -> cardOpener.accept(document));

        JmixButton remove = uiComponents.create(JmixButton.class);
        remove.setIcon(icons.get(JmixFontIcon.CLOSE));
        remove.addThemeVariants(ButtonVariant.TERTIARY, ButtonVariant.SMALL);
        remove.setTitle(messageBundle.formatMessage("removeButton.title", document.getName()));
        remove.addClickListener(click -> {
            discussion.remove(document);
            showSelection();
        });

        Span chip = uiComponents.create(Span.class);
        chip.addClassName("document-discussion-chip");
        chip.add(name, remove);

        return chip;
    }

    // the tools run one after another, so a new step ends the one before; a step reported again with its result
    // has already been shown
    private void showStatus(AiToolStatusUpdate update) {
        if (update.isCompleted()) {
            return;
        }

        if (lastStatus != null) {
            lastStatus.complete();
        }

        lastStatus = chat.getThinkingStatusPublisher().start(update.getMessage());
    }

    private class QuestionTask extends BackgroundTask<AiToolStatusUpdate, String> {

        private final String question;
        private final FluxSink<String> answer;

        private QuestionTask(String question, FluxSink<String> answer) {
            super(aiToolsProperties.getAssistantResponseTimeout().toSeconds(),
                    FragmentUtils.getHostView(DocumentDiscussionFragment.this));

            this.question = question;
            this.answer = answer;
        }

        @Override
        public String run(TaskLifeCycle<AiToolStatusUpdate> taskLifeCycle) {
            return discussion.ask(question, update -> {
                try {
                    taskLifeCycle.publish(update);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        @Override
        public void progress(List<AiToolStatusUpdate> changes) {
            changes.forEach(DocumentDiscussionFragment.this::showStatus);
        }

        @Override
        public void done(String result) {
            answer.next(result);
            answer.complete();
        }

        @Override
        public boolean handleException(Exception ex) {
            answer.error(ex);
            return true;
        }

        @Override
        public boolean handleTimeoutException() {
            answer.error(new TimeoutException("The assistant did not answer in time"));
            return true;
        }
    }
}
