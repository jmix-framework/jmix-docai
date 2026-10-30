package com.company.docai.view.document;

import com.company.docai.model.document.Document;
import com.vaadin.flow.component.HasValueAndElement;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import io.jmix.core.Metadata;
import io.jmix.core.MetadataTools;
import io.jmix.dynattr.DynAttrMetadata;
import io.jmix.dynattr.model.Category;
import io.jmix.dynattrflowui.panel.DynamicAttributesPanel;
import io.jmix.flowui.component.UiComponentUtils;
import io.jmix.flowui.component.combobox.EntityComboBox;
import io.jmix.flowui.fragment.Fragment;
import io.jmix.flowui.fragment.FragmentDescriptor;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Comparator;

/**
 * The type of a document and the fields of the type. The panel of the dynamic attributes add-on follows
 * the type of the edited document and rebuilds its fields; the type itself is chosen in a field of the form,
 * the panel's own selector stays hidden.
 */
@FragmentDescriptor("document-properties-fragment.xml")
public class DocumentPropertiesFragment extends Fragment<VerticalLayout> {

    @ViewComponent
    private EntityComboBox<Category> typeField;
    @ViewComponent
    private DynamicAttributesPanel fieldsPanel;

    @Autowired
    private DynAttrMetadata dynAttrMetadata;
    @Autowired
    private Metadata metadata;
    @Autowired
    private MetadataTools metadataTools;

    @Subscribe
    public void onReady(final ReadyEvent event) {
        // the types come from the add-on's cache, like the fields of the panel
        typeField.setItems(dynAttrMetadata.getCategories(metadata.getClass(Document.class)).stream()
                .map(type -> (Category) type.getSource())
                .sorted(Comparator.comparing(metadataTools::getInstanceName))
                .toList());
    }

    /**
     * Makes the type and the fields of the type read-only. Nothing here makes them editable again: the fields
     * stay as the data binding and the roles left them.
     */
    public void lock() {
        typeField.setReadOnly(true);

        UiComponentUtils.getComponents(fieldsPanel).stream()
                .filter(HasValueAndElement.class::isInstance)
                .map(HasValueAndElement.class::cast)
                .forEach(field -> field.setReadOnly(true));
    }
}
