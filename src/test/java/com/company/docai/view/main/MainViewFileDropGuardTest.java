package com.company.docai.view.main;

import com.company.docai.DocAiApplication;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.TestMenus;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.dependency.JsModule;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guard of the page against files dragged from the computer: the main view holds it once, as an element of its
 * own module in the browser.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
class MainViewFileDropGuardTest {

    @Test
    void theMainViewHoldsOneGuardOfItsOwnModule() throws IOException {
        // a text has no tag
        List<Component> guards = componentsIn(TestMenus.mainView())
                .filter(component -> !component.getElement().isTextNode())
                .filter(component -> component.getElement().getTag().equals("docai-file-drop-guard"))
                .toList();

        assertThat(guards).hasSize(1);

        List<String> modules = Arrays.stream(guards.getFirst().getClass().getAnnotationsByType(JsModule.class))
                .map(JsModule::value)
                .toList();

        Path module = Path.of("src/main/frontend", modules.getFirst());

        assertThat(modules).containsExactly("./components/file-drop-guard.js");
        assertThat(module).exists();
        // the browser knows the element by the tag the server gives it
        assertThat(Files.readString(module)).contains("'docai-file-drop-guard'");
    }

    private static Stream<Component> componentsIn(Component component) {
        Stream<Component> inside = component.getChildren()
                .flatMap(MainViewFileDropGuardTest::componentsIn);

        return Stream.concat(Stream.of(component), inside);
    }
}
