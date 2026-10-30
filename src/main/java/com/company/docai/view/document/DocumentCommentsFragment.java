package com.company.docai.view.document;

import com.company.docai.app.service.document.DocumentCommentService;
import com.company.docai.model.document.Document;
import com.company.docai.model.document.DocumentComment;
import com.company.docai.model.user.User;
import com.google.common.base.Strings;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.KeyModifier;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.component.textarea.JmixTextArea;
import io.jmix.flowui.component.virtuallist.JmixVirtualList;
import io.jmix.flowui.fragment.Fragment;
import io.jmix.flowui.fragment.FragmentDescriptor;
import io.jmix.flowui.kit.action.ActionVariant;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * The comments tab of the document card: a field for a new comment and the comments of the document, the newest
 * first, in a virtual list that draws each comment with {@link DocumentCommentItemFragment}. The comment service saves
 * a comment at once, apart from OK of the card, so the tab works in a card the user only views. The comments are read
 * again after each own action; those of other people appear then or when the card opens again.
 */
@FragmentDescriptor("document-comments-fragment.xml")
public class DocumentCommentsFragment extends Fragment<VerticalLayout> {

    @ViewComponent
    private JmixTextArea commentField;
    @ViewComponent
    private Span commentLength;
    @ViewComponent
    private JmixButton postCommentButton;
    @ViewComponent
    private Span noComments;
    @ViewComponent
    private JmixVirtualList<DocumentComment> commentsList;
    @ViewComponent
    private CollectionLoader<DocumentComment> commentsDl;
    @ViewComponent
    private CollectionContainer<DocumentComment> commentsDc;
    @ViewComponent
    private MessageBundle messageBundle;

    @Autowired
    private DocumentCommentService commentService;
    @Autowired
    private Dialogs dialogs;

    private Document document;
    private Map<String, User> authors = Map.of();
    private IntConsumer countHandler = count -> {
    };

    @Subscribe
    public void onReady(final ReadyEvent event) {
        // Enter starts a new line, Ctrl+Enter or Cmd+Enter sends the comment
        postCommentButton.addClickShortcut(Key.ENTER, KeyModifier.CONTROL)
                .listenOn(commentField);
        postCommentButton.addClickShortcut(Key.ENTER, KeyModifier.META)
                .listenOn(commentField);

        trackLength(commentField, commentLength, postCommentButton);
    }

    /**
     * Shows the comments of the document and reports their number after every load.
     */
    public void show(Document document, IntConsumer countHandler) {
        this.document = document;
        this.countHandler = countHandler;
        reload();
    }

    @Subscribe(id = "postCommentButton", subject = "clickListener")
    public void onPostCommentButtonClick(final ClickEvent<JmixButton> event) {
        try {
            commentService.post(document, commentField.getValue());
            commentField.clear();
            // the new comment comes first, and the list may be scrolled down
            commentsList.scrollToStart();
        } finally {
            reload();
        }
    }

    /**
     * Returns the user who wrote the comment, read with the comments.
     *
     * @return the user, or null for a user deleted since: the comment shows the username then
     */
    @Nullable
    public User authorOf(DocumentComment comment) {
        return authors.get(comment.getCreatedBy());
    }

    /**
     * Saves the new text of the comment and reads the comments again, also when the comment service refuses.
     */
    public void edit(DocumentComment comment, String text) {
        try {
            commentService.edit(comment, text);
        } finally {
            reload();
        }
    }

    /**
     * Asks and deletes the comment for good, then reads the comments again.
     */
    public void delete(DocumentComment comment) {
        dialogs.createOptionDialog()
                .withHeader(messageBundle.getMessage("deleteCommentDialog.header"))
                .withText(messageBundle.getMessage("deleteCommentDialog.text"))
                .withActions(
                        new DialogAction(DialogAction.Type.YES).withHandler(actionEvent -> {
                            try {
                                commentService.delete(comment);
                            } finally {
                                reload();
                            }
                        }),
                        new DialogAction(DialogAction.Type.NO).withVariant(ActionVariant.PRIMARY))
                .open();
    }

    /**
     * Shows the length of the text in the field against the limit and lets the button save only a text that fits.
     * The field has no maximum length of its own: that would cut a pasted text without a word.
     */
    public void trackLength(JmixTextArea field, Span length, JmixButton button) {
        Runnable update = () -> {
            String text = Strings.nullToEmpty(field.getValue());
            boolean tooLong = text.length() > DocumentComment.MAX_TEXT_LENGTH;

            length.setText(messageBundle.formatMessage("commentLength", text.length(),
                    DocumentComment.MAX_TEXT_LENGTH));
            length.setClassName("document-comment-length-over", tooLong);
            button.setEnabled(!text.isBlank() && !tooLong);
        };

        field.addValueChangeListener(event -> update.run());
        update.run();
    }

    // the list draws its rows only when the response is ready, so the authors read here are there for the rows
    private void reload() {
        commentsDl.setParameter("document", document);
        commentsDl.load();

        List<DocumentComment> comments = commentsDc.getItems();
        authors = commentService.authorsOf(comments);
        noComments.setVisible(comments.isEmpty());
        countHandler.accept(comments.size());
    }
}
