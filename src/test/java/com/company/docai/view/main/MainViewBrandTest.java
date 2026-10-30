package com.company.docai.view.main;

import com.company.docai.DocAiApplication;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.vaadin.flow.component.html.Header;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.SvgIcon;
import io.jmix.flowui.component.UiComponentUtils;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.view.View;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
class MainViewBrandTest {

    @Test
    void theHeaderDerivesItsColorsForTheMixedScheme() {
        Header header = UiTestUtils.getComponent(mainView(), "header");

        assertThat(header.getClassNames()).contains("aura-accent-color");
    }

    @Test
    void theDrawerShowsTheMarkAndTheNameWithoutASubtitle() {
        SvgIcon logo = UiTestUtils.getComponent(mainView(), "brandLogo");
        Span name = UiTestUtils.getComponent(mainView(), "brandName");

        assertThat(logo.getSrc()).isEqualTo("themes/jmix-docai-aura/images/logo.svg");
        assertThat(name.getText()).isEqualTo("Jmix DocAI");
        assertThat(UiComponentUtils.findComponent(mainView(), "brandSubtitle")).isEmpty();
    }

    private View<?> mainView() {
        return UiTestUtils.getCurrentView();
    }
}
