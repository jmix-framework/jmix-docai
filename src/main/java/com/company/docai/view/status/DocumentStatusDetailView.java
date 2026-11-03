package com.company.docai.view.status;

import com.company.docai.app.service.document.DocumentStatusService;
import com.company.docai.model.document.DocumentStatus;
import com.company.docai.view.main.MainView;
import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Input;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.router.Route;
import io.jmix.core.SaveContext;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.component.checkbox.JmixCheckbox;
import io.jmix.flowui.component.multiselectcombobox.JmixMultiSelectComboBox;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.Install;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.Target;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A status of the dictionary: its name, its color from the palette or any other, its place in the lists, its flags
 * and the statuses it moves to. The list of statuses hides while the status moves to any of them.
 */
@Route(value = "document-statuses/:id", layout = MainView.class)
@ViewController(id = "DocumentStatus.detail")
@ViewDescriptor(path = "document-status-detail-view.xml")
@EditedEntityContainer("statusDc")
@DialogMode(width = "32em")
public class DocumentStatusDetailView extends StandardDetailView<DocumentStatus> {

    /**
     * The colors of the palette, close to the accent colors of the Aura theme: gray, green, yellow, orange, red and
     * blue. Purple stays the color of the AI controls.
     */
    public static final List<String> PALETTE = List.of("#717171", "#00a045", "#ffd400", "#d95a00", "#db373a",
            "#3266e4");

    private static final List<String> PALETTE_NAMES = List.of("palette.gray", "palette.green", "palette.yellow",
            "palette.orange", "palette.red", "palette.blue");

    @ViewComponent
    private Div swatches;

    @ViewComponent
    private Input customColorInput;

    @ViewComponent
    private JmixMultiSelectComboBox<DocumentStatus> targetsField;

    @ViewComponent
    private MessageBundle messageBundle;

    @Autowired
    private DocumentStatusService statusService;

    @Autowired
    private UiComponents uiComponents;

    private final List<NativeButton> paletteSwatches = new ArrayList<>();

    // the binding gives the field the targets of a saved status as soon as it is loaded, so the items come first
    @Subscribe
    public void onInit(final InitEvent event) {
        targetsField.setItems(new ArrayList<>(statusService.loadAll()));
    }

    @Subscribe
    public void onInitEntity(final InitEntityEvent<DocumentStatus> event) {
        DocumentStatus status = event.getEntity();
        status.setColor(PALETTE.getLast());
        status.setPosition(statusService.nextPosition());
    }

    @Subscribe
    public void onReady(final ReadyEvent event) {
        for (int i = 0; i < PALETTE.size(); i++) {
            String color = PALETTE.get(i);
            NativeButton swatch = uiComponents.create(NativeButton.class);
            swatch.addClassName("status-swatch");
            swatch.getStyle().set("background-color", color);
            swatch.setAriaLabel(messageBundle.getMessage(PALETTE_NAMES.get(i)));
            swatch.addClickListener(click -> chooseColor(color));
            swatches.addComponentAtIndex(i, swatch);
            paletteSwatches.add(swatch);
        }

        customColorInput.addValueChangeListener(change -> {
            if (change.isFromClient() && change.getValue() != null) {
                chooseColor(change.getValue());
            }
        });

        DocumentStatus status = getEditedEntity();
        showColor(status.getColor());
        targetsField.getListDataView().removeItem(status);
        targetsField.setVisible(!Boolean.TRUE.equals(status.getAnyTarget()));
    }

    @Subscribe("anyTargetField")
    public void onAnyTargetFieldComponentValueChange(
            final AbstractField.ComponentValueChangeEvent<JmixCheckbox, Boolean> event) {
        targetsField.setVisible(!Boolean.TRUE.equals(event.getValue()));
    }

    // one status is given to new documents: the status service moves the flag in the same save
    @Install(target = Target.DATA_CONTEXT)
    private Set<Object> saveDelegate(final SaveContext saveContext) {
        return Set.of(statusService.save(getEditedEntity()));
    }

    private void chooseColor(String color) {
        String hex = color.toLowerCase(Locale.ROOT);
        getEditedEntity().setColor(hex);
        showColor(hex);
    }

    // the swatch of the color is pressed; a color off the palette shows in the custom swatch, pressed too
    private void showColor(String color) {
        boolean inPalette = false;

        for (int i = 0; i < PALETTE.size(); i++) {
            boolean chosen = PALETTE.get(i).equalsIgnoreCase(color);
            inPalette |= chosen;
            paletteSwatches.get(i).getElement().setAttribute("aria-pressed", String.valueOf(chosen));
        }

        customColorInput.setValue(color);
        customColorInput.getElement().setAttribute("aria-pressed", String.valueOf(!inPalette));
    }
}
