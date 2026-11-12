package com.company.docai.view.main;

import com.company.docai.DocAiApplication;
import com.company.docai.app.ui.component.filedrop.FileDropGuard;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.TestMenus;
import com.vaadin.flow.component.Component;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guard of the page against files dragged from the computer: the main view holds it once.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
class MainViewFileDropGuardTest {

    @Test
    void theMainViewHoldsOneGuard() {
        // a text has no tag
        List<Component> guards = componentsIn(TestMenus.mainView())
                .filter(component -> !component.getElement().isTextNode())
                .filter(component -> component.getElement().getTag().equals("docai-file-drop-guard"))
                .toList();

        assertThat(guards).singleElement().isInstanceOf(FileDropGuard.class);
    }

    private static Stream<Component> componentsIn(Component component) {
        Stream<Component> inside = component.getChildren()
                .flatMap(MainViewFileDropGuardTest::componentsIn);

        return Stream.concat(Stream.of(component), inside);
    }
}
