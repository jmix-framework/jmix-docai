package com.company.docai.app.ui.component;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DomEvent;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.dependency.JsModule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The client modules of the components of the application. A component lives in the package of its feature under
 * {@code app.ui.component}, and its module, named after its tag, in the folder of that feature under
 * {@code src/main/frontend/src/component}. A wrong path fails only the production build, a module that defines another
 * tag leaves an element that does nothing, and an event that the module sends under another name than the component
 * listens to is lost without a trace.
 */
class ComponentModulesTest {

    private static final String COMPONENTS = "com.company.docai.app.ui.component.";
    private static final String FRONTEND = "src/main/frontend";

    @Test
    void everyComponentHasItsModuleInTheFolderOfItsFeatureAndTheModuleDefinesItsTag() throws IOException {
        List<Class<?>> components = components();

        // the scan sees the components of every feature
        assertThat(components)
                .extracting(Class::getSimpleName)
                .contains("DrawerHandle", "FileDropGuard", "FileDropZone", "SearchPaletteOpener", "SearchPaletteLine",
                        "SearchPaletteMotion");

        for (Class<?> component : components) {
            Tag tag = component.getAnnotation(Tag.class);
            assertThat(tag).as(component.getName()).isNotNull();

            // a component that only inherits a module is checked with the class that declares it
            for (JsModule module : component.getDeclaredAnnotationsByType(JsModule.class)) {
                Path file = Path.of(FRONTEND, module.value()).normalize();

                assertThat(mirrors(component, module.value(), tag.value()))
                        .as("%s: %s lies in the folder of its feature and is named after %s", component.getName(),
                                module.value(), tag.value())
                        .isTrue();

                assertThat(file).as(component.getName()).exists();
                // the browser knows the element by the tag the server gives it
                assertThat(Files.readString(file)).as(component.getName()).contains("'" + tag.value() + "'");
            }
        }
    }

    @Test
    void everyEventOfAComponentHasItsNameInTheModule() throws IOException {
        List<Class<?>> components = components();

        // the scan sees the events of the edge, the opener and the line
        assertThat(components)
                .flatExtracting(ComponentModulesTest::events)
                .contains("docai-drawer-resize", "docai-search-palette-open", "docai-search-palette-cancel",
                        "docai-search-palette-key");

        for (Class<?> component : components) {
            for (JsModule module : component.getDeclaredAnnotationsByType(JsModule.class)) {
                String text = Files.readString(Path.of(FRONTEND, module.value()).normalize());

                for (String event : events(component)) {
                    assertThat(text).as("%s: %s", component.getName(), event).contains("'" + event + "'");
                }
            }
        }
    }

    // the components of the application, with a module of their own or of their parent; the shell of the application
    // carries a module of the framework and is no component
    private static List<Class<?>> components() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(JsModule.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(JsModule.Container.class));

        return scanner.findCandidateComponents("com.company.docai")
                .stream()
                .map(BeanDefinition::getBeanClassName)
                .map(Objects::requireNonNull)
                .<Class<?>>map(name -> ClassUtils.resolveClassName(name, null))
                .filter(Component.class::isAssignableFrom)
                .toList();
    }

    // the events the element sends the server, by the names that the nested event classes of the component listen to
    private static List<String> events(Class<?> component) {
        return Arrays.stream(component.getDeclaredClasses())
                .map(event -> event.getAnnotation(DomEvent.class))
                .filter(Objects::nonNull)
                .map(DomEvent::value)
                .toList();
    }

    // app.ui.component.searchpalette holds ./src/component/search-palette/<tag>.js
    private static boolean mirrors(Class<?> component, String module, String tag) {
        String feature = component.getPackageName().replace(COMPONENTS, "");
        String[] path = module.split("/");

        return path.length == 5 && module.startsWith("./src/component/")
                && path[3].replace("-", "").equals(feature)
                && path[4].equals(tag + ".js");
    }
}
