package com.company.docai.view.access;

import com.company.docai.app.service.access.AccessService;
import com.company.docai.app.service.access.AccessSubject;
import com.company.docai.model.access.AccessDraft;
import com.company.docai.model.access.AccessEntryDraft;
import com.company.docai.model.access.AccessLevel;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.data.provider.Query;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.data.renderer.Renderer;
import io.jmix.core.MetadataTools;
import io.jmix.core.SaveContext;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.component.checkbox.JmixCheckbox;
import io.jmix.flowui.component.combobox.JmixComboBox;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.select.JmixSelect;
import io.jmix.flowui.icon.Icons;
import io.jmix.flowui.kit.action.ActionVariant;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.kit.icon.JmixFontIcon;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.model.InstanceContainer;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.Install;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.Supply;
import io.jmix.flowui.view.Target;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The access dialog of a folder or a document, over the draft that {@link AccessService#describe} builds.
 * The box is bound to {@code inherit}: checked, it shows the inherited entries read-only; cleared, it edits
 * the item's own entries. OK hands the draft to {@link AccessService#save(AccessDraft)}, which applies it
 * in one transaction.
 */
@ViewController(id = "AccessDraft.detail")
@ViewDescriptor(path = "access-detail-view.xml")
@EditedEntityContainer("accessDc")
@DialogMode(width = "44em")
public class AccessDetailView extends StandardDetailView<AccessDraft> {

    @ViewComponent
    private JmixCheckbox inheritField;
    @ViewComponent
    private DataGrid<AccessEntryDraft> inheritedEntriesGrid;
    @ViewComponent
    private DataGrid<AccessEntryDraft> entriesGrid;
    @ViewComponent
    private CollectionContainer<AccessEntryDraft> entriesDc;
    @ViewComponent
    private HorizontalLayout addPanel;
    @ViewComponent
    private JmixComboBox<AccessSubject> subjectField;
    @ViewComponent
    private JmixSelect<AccessLevel> levelField;

    @ViewComponent
    private MessageBundle messageBundle;

    @Autowired
    private AccessService accessService;
    @Autowired
    private Dialogs dialogs;
    @Autowired
    private UiComponents uiComponents;
    @Autowired
    private Icons icons;
    @Autowired
    private MetadataTools metadataTools;

    @Subscribe
    public void onInit(final InitEvent event) {
        // the draft comes from the access service, and no data store can reload it
        setReloadEdited(false);
        setShowSaveNotification(false);

        levelField.setValue(AccessLevel.VIEW);
    }

    @Subscribe
    public void onReady(final ReadyEvent event) {
        AccessDraft access = getEditedEntity();
        setPageTitle(messageBundle.formatMessage("accessTitle", itemName()));

        // the root has no parent to inherit from
        if (access.getInheritedFrom() != null) {
            inheritField.setLabel(messageBundle.formatMessage("inheritField.label", access.getInheritedFrom()));
        } else {
            inheritField.setVisible(false);
        }

        showInheritance();
    }

    @Subscribe(id = "accessDc", target = Target.DATA_CONTAINER)
    public void onAccessDcItemPropertyChange(final InstanceContainer.ItemPropertyChangeEvent<AccessDraft> event) {
        if ("inherit".equals(event.getProperty())) {
            showInheritance();
        }
    }

    // a subject that is listed already gets the new level instead of a second entry
    @Subscribe("addButton")
    public void onAddButtonClick(final ClickEvent<JmixButton> event) {
        AccessSubject subject = subjectField.getValue();
        AccessLevel level = levelField.getValue();
        if (subject == null || level == null) {
            return;
        }

        AccessEntryDraft listed = entriesDc.getItems().stream()
                .filter(entry -> Objects.equals(entry.getUsername(), subject.username())
                        && Objects.equals(entry.getRoleCode(), subject.roleCode()))
                .findFirst()
                .orElse(null);

        if (listed != null) {
            listed.setAccessLevel(level);
        } else {
            AccessEntryDraft entry = getViewData().getDataContext().create(AccessEntryDraft.class);
            entry.setUsername(subject.username());
            entry.setRoleCode(subject.roleCode());
            entry.setSubjectName(subject.name());
            entry.setAccessLevel(level);

            entriesDc.getMutableItems().add(entry);
        }

        subjectField.clear();
    }

    @Subscribe
    public void onBeforeSave(final BeforeSaveEvent event) {
        if (accessService.keepsManagement(getEditedEntity())) {
            return;
        }

        event.preventSave();
        dialogs.createOptionDialog()
                .withHeader(messageBundle.getMessage("loseManagementDialog.header"))
                .withText(messageBundle.formatMessage("loseManagementDialog.text", itemName()))
                .withActions(
                        new DialogAction(DialogAction.Type.YES).withHandler(actionEvent -> event.resume()),
                        new DialogAction(DialogAction.Type.NO).withVariant(ActionVariant.PRIMARY))
                .open();
    }

    // the service applies the draft; the dialog closes, so nothing comes back into its data context
    @Install(target = Target.DATA_CONTEXT)
    private Set<Object> saveDelegate(final SaveContext saveContext) {
        accessService.save(getEditedEntity());
        return Set.of();
    }

    // there may be many users, so the field looks them up by a part of the name, a page at a time
    @Install(to = "subjectField", subject = "itemsFetchCallback")
    private Stream<AccessSubject> subjectFieldItemsFetchCallback(final Query<AccessSubject, String> query) {
        return accessService.findSubjects(query.getFilter().orElse(""), query.getOffset(), query.getLimit())
                .stream();
    }

    @Install(to = "subjectField", subject = "itemLabelGenerator")
    private String subjectFieldItemLabelGenerator(final AccessSubject subject) {
        return subject.name();
    }

    @Supply(to = "subjectField", subject = "renderer")
    private Renderer<AccessSubject> subjectFieldRenderer() {
        return new ComponentRenderer<>(subject -> subject(subject.isRole(), subject.name()));
    }

    @Supply(to = "inheritedEntriesGrid.subjectName", subject = "renderer")
    private Renderer<AccessEntryDraft> inheritedEntriesGridSubjectRenderer() {
        return new ComponentRenderer<>(entry -> subject(entry.getRoleCode() != null, entry.getSubjectName()));
    }

    @Supply(to = "entriesGrid.subjectName", subject = "renderer")
    private Renderer<AccessEntryDraft> entriesGridSubjectRenderer() {
        return new ComponentRenderer<>(entry -> subject(entry.getRoleCode() != null, entry.getSubjectName()));
    }

    @SuppressWarnings("unchecked")
    @Supply(to = "entriesGrid.accessLevel", subject = "renderer")
    private Renderer<AccessEntryDraft> entriesGridLevelRenderer() {
        return new ComponentRenderer<>(entry -> {
            JmixSelect<AccessLevel> select = uiComponents.create(JmixSelect.class);
            select.setItems(AccessLevel.class);
            select.setNoVerticalOverlap(true);
            select.setValue(entry.getAccessLevel());
            select.setAriaLabel(messageBundle.getMessage("levelColumn.header"));
            select.addValueChangeListener(change -> entry.setAccessLevel(change.getValue()));
            return select;
        });
    }

    @Supply(to = "entriesGrid.remove", subject = "renderer")
    private Renderer<AccessEntryDraft> entriesGridRemoveRenderer() {
        return new ComponentRenderer<>(entry -> {
            JmixButton button = uiComponents.create(JmixButton.class);

            button.setIcon(icons.get(JmixFontIcon.TRASH));
            button.addThemeVariants(ButtonVariant.TERTIARY, ButtonVariant.ERROR);
            button.setAriaLabel(messageBundle.getMessage("removeButton.tooltip"));
            button.setTooltipText(messageBundle.getMessage("removeButton.tooltip"));
            button.addClickListener(click -> {
                getViewData().getDataContext().remove(entry);
                entriesDc.getMutableItems().remove(entry);
            });

            return button;
        });
    }

    private Span subject(boolean role, String name) {
        Span subject = uiComponents.create(Span.class);
        subject.addClassName("name-with-icon");
        subject.add(icons.get(role ? JmixFontIcon.USERS : JmixFontIcon.USER), new Text(name));
        return subject;
    }

    private String itemName() {
        AccessDraft access = getEditedEntity();
        return metadataTools.getInstanceName(access.getFolder() != null ? access.getFolder() : access.getDocument());
    }

    private void showInheritance() {
        boolean inherit = Boolean.TRUE.equals(getEditedEntity().getInherit());

        inheritedEntriesGrid.setVisible(inherit);
        entriesGrid.setVisible(!inherit);
        addPanel.setVisible(!inherit);
    }
}
