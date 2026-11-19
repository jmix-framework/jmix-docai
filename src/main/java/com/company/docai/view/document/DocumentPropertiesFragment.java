package com.company.docai.view.document;

import com.company.docai.model.document.Document;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasValueAndElement;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import io.jmix.core.Metadata;
import io.jmix.core.MetadataTools;
import io.jmix.dynattr.DynAttrMetadata;
import io.jmix.dynattr.DynAttrUtils;
import io.jmix.dynattr.model.Category;
import io.jmix.dynattrflowui.panel.DynamicAttributesPanel;
import io.jmix.flowui.component.UiComponentUtils;
import io.jmix.flowui.component.combobox.EntityComboBox;
import io.jmix.flowui.data.EntityValueSource;
import io.jmix.flowui.data.SupportsValueSource;
import io.jmix.flowui.fragment.Fragment;
import io.jmix.flowui.fragment.FragmentDescriptor;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Comparator;
import java.util.Set;

/**
 * The type of a document and its fields, which the panel of the dynamic attributes add-on rebuilds for the chosen type.
 */
@FragmentDescriptor("document-properties-fragment.xml")
public class DocumentPropertiesFragment extends Fragment<VerticalLayout> {

    @ViewComponent
    private EntityComboBox<Category> typeField;
    @ViewComponent
    private DynamicAttributesPanel fieldsPanel;

    @Autowired
    private AiFillMarks aiFillMarks;
    @Autowired
    private DynAttrMetadata dynAttrMetadata;
    @Autowired
    private Metadata metadata;
    @Autowired
    private MetadataTools metadataTools;

    @Subscribe
    public void onReady(final ReadyEvent event) {
        typeField.setItems(dynAttrMetadata.getCategories(metadata.getClass(Document.class)).stream()
                .map(type -> (Category) type.getSource())
                .sorted(Comparator.comparing(metadataTools::getInstanceName))
                .toList());
    }

    /**
     * Marks the type and the fields of the type that Fill with AI set, given by their properties, and takes the mark
     * off the others.
     *
     * @return whether the type or one of the fields shows the mark
     */
    public boolean markFilledByAi(Set<String> properties) {
        boolean marked = properties.contains("category");
        aiFillMarks.markField(typeField, marked);

        for (Component field : UiComponentUtils.getComponents(fieldsPanel)) {
            if (field instanceof SupportsValueSource<?> bound
                    && bound.getValueSource() instanceof EntityValueSource<?, ?> source
                    && DynAttrUtils.isDynamicAttributeProperty(source.getMetaPropertyPath().toPathString())) {
                boolean filled = properties.contains(source.getMetaPropertyPath().toPathString());
                aiFillMarks.markField(field, filled);
                marked |= filled;
            }
        }

        return marked;
    }

    /**
     * Makes the type and its fields read-only; nothing here makes them editable again.
     */
    public void lock() {
        typeField.setReadOnly(true);

        UiComponentUtils.getComponents(fieldsPanel).stream()
                .filter(HasValueAndElement.class::isInstance)
                .map(HasValueAndElement.class::cast)
                .forEach(field -> field.setReadOnly(true));
    }
}
