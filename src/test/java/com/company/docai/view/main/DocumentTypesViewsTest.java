package com.company.docai.view.main;

import com.company.docai.DocAiApplication;
import com.company.docai.test_support.TestMenus;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasLabel;
import com.vaadin.flow.component.HasText;
import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.router.RouteParam;
import com.vaadin.flow.router.RouteParameters;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.Messages;
import io.jmix.dynattr.model.Category;
import io.jmix.dynattr.model.CategoryAttribute;
import io.jmix.dynattrflowui.view.category.CategoryDetailView;
import io.jmix.dynattrflowui.view.category.CategoryListView;
import io.jmix.dynattrflowui.view.categoryattr.CategoryAttributesDetailView;
import io.jmix.flowui.DialogWindows;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.tabsheet.JmixTabSheet;
import io.jmix.flowui.kit.component.main.ListMenu;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.view.DialogWindow;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.View;
import io.jmix.flowui.view.navigation.UrlParamSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The views of the Dynamic Attributes add-on behind the Document types item speak of document types and their fields.
 */
@UiTest
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
class DocumentTypesViewsTest {

    private static final Pattern WORDS_OF_THE_ADD_ON = Pattern.compile(
            "(?i)\\b(categor(y|ies)|attributes?|dynamic|entity type|is default|is collection|apply changes)\\b");

    @Autowired
    private ViewNavigators viewNavigators;

    @Autowired
    private DialogWindows dialogWindows;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private UrlParamSerializer urlParamSerializer;

    @Autowired
    private Messages messages;

    @Test
    void theListIsNamedLikeItsMenuItemAndSpeaksOfTypesAndFields() {
        viewNavigators.view(UiTestUtils.getCurrentView(), MainView.class).navigate();
        ListMenu.MenuItem item = TestMenus.listMenu("menu").getMenuItem("documentTypes");

        viewNavigators.view(UiTestUtils.getCurrentView(), CategoryListView.class).navigate();
        View<?> list = UiTestUtils.getCurrentView();

        assertThat(item).isNotNull();
        assertThat(list.getPageTitle()).isEqualTo(item.getTitle());
        assertThat(textsOf(list))
                .isNotEmpty()
                .noneMatch(WORDS_OF_THE_ADD_ON.asPredicate());
    }

    @Test
    void theEditorOfATypeSpeaksOfTypes() {
        DialogWindow<CategoryDetailView> window = dialogWindows.detail(UiTestUtils.getCurrentView(), Category.class)
                .withViewClass(CategoryDetailView.class)
                .newEntity()
                .build();

        window.open();
        CategoryDetailView editor = window.getView();

        assertThat(editor.getPageTitle()).doesNotContainPattern(WORDS_OF_THE_ADD_ON);
        assertThat(textsOf(editor))
                .isNotEmpty()
                .noneMatch(WORDS_OF_THE_ADD_ON.asPredicate());
    }

    @Test
    void theEditorOfAFieldSpeaksOfFieldsOnBothItsTabs() {
        CategoryAttribute field = aFieldOfTheContractType();
        CategoryAttributesDetailView editor = openEditorOf(field);
        List<String> general = textsOf(editor);

        UiTestUtils.<JmixTabSheet>getComponent(editor, "tabSheet").setSelectedIndex(1);
        List<String> advanced = textsOf(editor);

        assertThat(editor.getPageTitle())
                .contains(field.getCategory().getName())
                .doesNotContainPattern(WORDS_OF_THE_ADD_ON);
        assertThat(general)
                .isNotEmpty()
                .noneMatch(WORDS_OF_THE_ADD_ON.asPredicate());
        assertThat(advanced)
                .isNotEqualTo(general)
                .noneMatch(WORDS_OF_THE_ADD_ON.asPredicate());
    }

    @Test
    void theNoticesAndChecksOfTheViewsSpeakOfTypesAndFields() {
        List<String> codes = List.of(
                "io.jmix.dynattrflowui.view.category/notification.changesApplied",
                "io.jmix.dynattrflowui.view.category/importSuccessful",
                "io.jmix.dynattrflowui.view.category/entityTypeField.required",
                "io.jmix.dynattrflowui.view.categoryattr/notUniqueAttributeName",
                "io.jmix.dynattrflowui.view.categoryattr/notUniqueAttributeCode",
                "io.jmix.dynattrflowui.view.categoryattr/uniqueName",
                "io.jmix.dynattrflowui.view.categoryattr/uniqueCode",
                "io.jmix.dynattrflowui.view.categoryattr/dataTypeField.required",
                "io.jmix.dynattrflowui.view.categoryattr/dependsOnAttributes.validationMsg");

        assertThat(codes).allSatisfy(code -> assertThat(messages.getMessage(code))
                .as(code)
                .doesNotContainPattern(WORDS_OF_THE_ADD_ON));
    }

    private CategoryAttribute aFieldOfTheContractType() {
        return dataManager.load(CategoryAttribute.class)
                .query("select a from dynat_CategoryAttribute a where a.category.name = :type order by a.orderNo")
                .parameter("type", "Contract")
                .fetchPlan(plan -> plan.addFetchPlan(FetchPlan.BASE)
                        .add("category", FetchPlan.BASE))
                .maxResults(1)
                .one();
    }

    private CategoryAttributesDetailView openEditorOf(CategoryAttribute field) {
        RouteParam type = new RouteParam(CategoryAttributesDetailView.CATEGORY_ID_ROUTE_PARAMETER,
                urlParamSerializer.serialize(field.getCategory().getId()));

        RouteParam id = new RouteParam(StandardDetailView.DEFAULT_ROUTE_PARAM,
                urlParamSerializer.serialize(field.getId()));

        viewNavigators.view(UiTestUtils.getCurrentView(), CategoryAttributesDetailView.class)
                .withRouteParameters(new RouteParameters(type, id))
                .navigate();

        return UiTestUtils.getCurrentView();
    }

    private static List<String> textsOf(Component root) {
        return visible(root)
                .flatMap(DocumentTypesViewsTest::textsOfOne)
                .filter(text -> text != null && !text.isBlank())
                .toList();
    }

    private static Stream<String> textsOfOne(Component component) {
        return switch (component) {
            case Grid<?> grid -> headersOf(grid);
            case Html html -> Stream.of(html.getInnerHtml().replaceAll("<[^>]+>", " "));
            case Text text -> Stream.of(text.getText());
            case HasLabel labelled -> Stream.of(labelled.getLabel());
            case HasText withText -> Stream.of(withText.getText());
            default -> Stream.empty();
        };
    }

    private static Stream<String> headersOf(Grid<?> grid) {
        return grid.getColumns()
                .stream()
                .filter(Grid.Column::isVisible)
                .map(Grid.Column::getHeaderText);
    }

    private static Stream<Component> visible(Component component) {
        // a text node has no visibility of its own: it shows with its parent
        if (!(component instanceof Text) && !component.isVisible()) {
            return Stream.empty();
        }

        // a tab sheet keeps the contents of its tabs apart from its children, and a person sees the selected one
        Stream<Component> children = component instanceof JmixTabSheet sheet
                ? Stream.of(sheet.getComponent(sheet.getSelectedTab()))
                : component.getChildren();

        return Stream.concat(Stream.of(component), children.flatMap(DocumentTypesViewsTest::visible));
    }
}
