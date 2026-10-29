package com.company.dms.view.document;

import com.company.dms.ai.AiSettings;
import com.company.dms.ai.DocumentTextReader;
import com.company.dms.ai.classification.ClassificationProposal;
import com.company.dms.ai.classification.ClassificationResult;
import com.company.dms.ai.classification.DocumentClassifier;
import com.company.dms.app.service.document.NewVersion;
import com.company.dms.model.document.Document;
import com.company.dms.model.document.DocumentStatus;
import io.jmix.core.Messages;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.action.ActionType;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.action.view.ViewAction;
import io.jmix.flowui.backgroundtask.BackgroundTask;
import io.jmix.flowui.backgroundtask.TaskLifeCycle;
import io.jmix.flowui.icon.Icons;
import io.jmix.flowui.kit.icon.JmixFontIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Fills the type, the fields and the date of the document in the card with what the AI reads in the file
 * the card shows, saved or just uploaded, and adds tags to those the document has. The values go into the form only:
 * the user checks them and saves the document as usual, and a new tag joins the vocabulary then.
 */
@ActionType(FillWithAiAction.ID)
public class FillWithAiAction extends ViewAction<FillWithAiAction, DocumentDetailView> {

    public static final String ID = "dms_fillWithAi";

    /**
     * How long the card waits for the AI, in seconds. A call of the model gives up earlier, see
     * {@code spring.ai.openai.timeout} and {@code spring.ai.openai.max-retries}.
     */
    public static final long TIMEOUT_SECONDS = 90;

    private static final Logger log = LoggerFactory.getLogger(FillWithAiAction.class);

    private AiSettings aiSettings;
    private DocumentTextReader documentTextReader;
    private DocumentClassifier documentClassifier;
    private Dialogs dialogs;
    private Notifications notifications;
    private Messages messages;

    public FillWithAiAction() {
        this(ID);
    }

    public FillWithAiAction(String id) {
        super(id);
    }

    @Autowired
    protected void setMessages(Messages messages) {
        this.messages = messages;
        this.text = message("fillWithAiAction.text");
    }

    @Autowired
    protected void setIcons(Icons icons) {
        this.icon = icons.get(JmixFontIcon.MAGIC);
    }

    @Autowired
    protected void setAiSettings(AiSettings aiSettings) {
        this.aiSettings = aiSettings;
    }

    @Autowired
    protected void setDocumentTextReader(DocumentTextReader documentTextReader) {
        this.documentTextReader = documentTextReader;
    }

    @Autowired
    protected void setDocumentClassifier(DocumentClassifier documentClassifier) {
        this.documentClassifier = documentClassifier;
    }

    @Autowired
    protected void setDialogs(Dialogs dialogs) {
        this.dialogs = dialogs;
    }

    @Autowired
    protected void setNotifications(Notifications notifications) {
        this.notifications = notifications;
    }

    /**
     * Shows the action to a user who may edit the document while the AI is on, and enables it when the card
     * is not archived and shows a file.
     */
    @Override
    public void refreshState() {
        super.refreshState();
        setVisibleInternal(visibleExplicitly && aiSettings.isAvailable() && target != null && !target.isReadOnly());
    }

    // the view sets the target while it loads, before it has a document
    @Override
    protected boolean isApplicable() {
        Document document = target == null ? null : target.getEditedEntityOrNull();

        return super.isApplicable()
                && document != null
                && !target.isReadOnly()
                && document.getStatus() != DocumentStatus.ARCHIVED
                && target.getShownVersion() != null;
    }

    @Override
    public void execute() {
        checkTarget();
        Document document = target.getEditedEntity();

        if (document.getCategory() == null && document.getDocumentDate() == null) {
            fill();
            return;
        }

        dialogs.createOptionDialog()
                .withHeader(message("fillWithAiAction.replaceHeader"))
                .withText(message("fillWithAiAction.replaceText"))
                .withActions(new DialogAction(DialogAction.Type.YES)
                                .withHandler(event -> fill()),
                        new DialogAction(DialogAction.Type.NO))
                .open();
    }

    private void fill() {
        FillTask task = new FillTask(target.getShownVersion(), target.getEditedEntity().getName());

        dialogs.createBackgroundTaskDialog(task)
                .withHeader(message("fillWithAiAction.progressHeader"))
                .withText(message("fillWithAiAction.progressText"))
                .withCancelAllowed(true)
                .open();
    }

    private void showResult(ClassificationResult result) {
        String typeName = result.typeName();
        String tags = result.tags().stream()
                .map(tag -> tag.newTag()
                        ? messages.formatMessage(getClass(), "fillWithAiAction.newTag", tag.name())
                        : tag.name())
                .collect(Collectors.joining(", "));

        if (typeName == null && tags.isEmpty()) {
            showNotification("fillWithAiAction.unknownType", Notifications.Type.WARNING);
            return;
        }

        if (typeName == null) {
            notifications.create(messages.formatMessage(getClass(), "fillWithAiAction.unknownTypeTags", tags))
                    .withType(Notifications.Type.WARNING)
                    .show();
            return;
        }

        target.showProperties();

        notifications.create(tags.isEmpty()
                        ? messages.formatMessage(getClass(), "fillWithAiAction.done", typeName, result.filled().size())
                        : messages.formatMessage(getClass(), "fillWithAiAction.doneWithTags", typeName,
                        result.filled().size(), tags))
                .withType(Notifications.Type.SUCCESS)
                .show();

        if (!result.skipped().isEmpty()) {
            notifications.create(messages.formatMessage(getClass(), "fillWithAiAction.skipped",
                            String.join("; ", result.skipped())))
                    .withType(Notifications.Type.WARNING)
                    .show();
        }
    }

    private void showNotification(String key, Notifications.Type type) {
        notifications.create(message(key))
                .withType(type)
                .show();
    }

    private String message(String key) {
        return messages.getMessage(getClass(), key);
    }

    private class FillTask extends BackgroundTask<Integer, Optional<ClassificationProposal>> {

        private final NewVersion version;
        private final String documentName;

        private FillTask(NewVersion version, String documentName) {
            super(TIMEOUT_SECONDS, TimeUnit.SECONDS, target);
            this.version = version;
            this.documentName = documentName;
        }

        @Override
        public Optional<ClassificationProposal> run(TaskLifeCycle<Integer> taskLifeCycle) {
            return documentTextReader.read(version)
                    .map(text -> documentClassifier.classify(documentName, text));
        }

        @Override
        public void done(Optional<ClassificationProposal> proposal) {
            // the user may leave the card while the AI reads, and a late answer has nowhere to go
            if (!target.isAttached()) {
                return;
            }

            proposal.ifPresentOrElse(
                    found -> showResult(documentClassifier.apply(found, target.getEditedEntity())),
                    () -> showNotification("fillWithAiAction.noText", Notifications.Type.WARNING));
        }

        @Override
        public boolean handleException(Exception e) {
            log.warn("The AI could not fill document {}", target.getEditedEntity().getId(), e);

            if (target.isAttached()) {
                showNotification("fillWithAiAction.failed", Notifications.Type.ERROR);
            }

            return true;
        }

        @Override
        public boolean handleTimeoutException() {
            if (target.isAttached()) {
                showNotification("fillWithAiAction.failed", Notifications.Type.ERROR);
            }

            return true;
        }
    }
}
