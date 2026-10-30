package com.company.dms.view.document;

import com.company.dms.app.service.document.DocumentCommentService;
import com.company.dms.model.document.DocumentComment;
import com.company.dms.model.user.User;
import com.google.common.base.Strings;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.avatar.Avatar;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import io.jmix.core.MetadataTools;
import io.jmix.core.metamodel.datatype.DatatypeFormatter;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.flowui.component.textarea.JmixTextArea;
import io.jmix.flowui.fragment.FragmentDescriptor;
import io.jmix.flowui.fragmentrenderer.FragmentRenderer;
import io.jmix.flowui.fragmentrenderer.RendererItemContainer;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.model.InstanceContainer;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.Target;
import io.jmix.flowui.view.ViewComponent;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * One comment in the list of the comments tab: who wrote it and when, whether it was changed, and its text with the
 * line breaks. The list creates it for each comment it draws and puts the comment into {@code commentDc}. The author
 * changes the own comment in place; the comments tab saves and deletes it.
 */
@FragmentDescriptor("document-comment-item-fragment.xml")
@RendererItemContainer("commentDc")
public class DocumentCommentItemFragment extends FragmentRenderer<HorizontalLayout, DocumentComment> {

    // the number of the user colors of an avatar
    private static final int AVATAR_COLORS = 7;

    @ViewComponent
    private InstanceContainer<DocumentComment> commentDc;
    @ViewComponent
    private Avatar authorAvatar;
    @ViewComponent
    private Span authorName;
    @ViewComponent
    private Span writtenTime;
    @ViewComponent
    private Span editedMark;
    @ViewComponent
    private JmixButton editCommentButton;
    @ViewComponent
    private JmixButton deleteCommentButton;
    @ViewComponent
    private Span commentText;
    @ViewComponent
    private VerticalLayout editPanel;
    @ViewComponent
    private JmixTextArea editCommentField;
    @ViewComponent
    private Span editCommentLength;
    @ViewComponent
    private JmixButton saveCommentButton;

    @Autowired
    private DocumentCommentService commentService;
    @Autowired
    private MetadataTools metadataTools;
    @Autowired
    private DatatypeFormatter datatypeFormatter;
    @Autowired
    private CurrentAuthentication currentAuthentication;

    @Subscribe
    public void onReady(final ReadyEvent event) {
        host().trackLength(editCommentField, editCommentLength, saveCommentButton);
    }

    @Subscribe(id = "commentDc", target = Target.DATA_CONTAINER)
    public void onCommentDcItemChange(final InstanceContainer.ItemChangeEvent<DocumentComment> event) {
        DocumentComment comment = commentDc.getItem();
        User author = host().authorOf(comment);
        String username = comment.getCreatedBy();
        String name = author == null ? username : metadataTools.getInstanceName(author);
        // the avatar would take the initials of every word of the name, the username in brackets too
        String initials = author != null && !Strings.isNullOrEmpty(author.getFirstName())
                && !Strings.isNullOrEmpty(author.getLastName())
                ? "%c%c".formatted(author.getFirstName().charAt(0), author.getLastName().charAt(0))
                : username.substring(0, 1).toUpperCase();

        authorAvatar.setName(name);
        authorAvatar.setAbbreviation(initials);
        authorAvatar.setColorIndex(Math.floorMod(username.hashCode(), AVATAR_COLORS));
        authorName.setText(name);
        writtenTime.setText(datatypeFormatter.formatLocalDateTime(comment.getCreatedDate()
                .atZoneSameInstant(currentAuthentication.getTimeZone().toZoneId())
                .toLocalDateTime()));

        editedMark.setVisible(comment.getEditedDate() != null);
        editCommentButton.setVisible(commentService.isMine(comment));
        deleteCommentButton.setVisible(commentService.canDelete(comment));
    }

    @Subscribe(id = "editCommentButton", subject = "clickListener")
    public void onEditCommentButtonClick(final ClickEvent<JmixButton> event) {
        editCommentField.setValue(commentDc.getItem().getText());
        commentText.setVisible(false);
        editPanel.setVisible(true);
        editCommentField.focus();
    }

    @Subscribe(id = "cancelCommentButton", subject = "clickListener")
    public void onCancelCommentButtonClick(final ClickEvent<JmixButton> event) {
        editPanel.setVisible(false);
        commentText.setVisible(true);
    }

    @Subscribe(id = "saveCommentButton", subject = "clickListener")
    public void onSaveCommentButtonClick(final ClickEvent<JmixButton> event) {
        host().edit(commentDc.getItem(), editCommentField.getValue());
    }

    @Subscribe(id = "deleteCommentButton", subject = "clickListener")
    public void onDeleteCommentButtonClick(final ClickEvent<JmixButton> event) {
        host().delete(commentDc.getItem());
    }

    // the fragment renderer of the list in the comments tab creates the comment with that tab as its parent
    private DocumentCommentsFragment host() {
        return (DocumentCommentsFragment) getParentController();
    }
}
