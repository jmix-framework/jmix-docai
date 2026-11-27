package com.company.docai.view.about;

import com.company.docai.DocAiApplication;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import io.jmix.core.CoreProperties;
import io.jmix.core.Messages;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The About page, as in the Jmix B2B CRM demo: the links to Jmix with the campaign of DocAI, the link to the code of
 * DocAI and the version.
 */
@UiTest
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
class AboutViewTest {

    private static final String ABOUT_GROUP = "com.company.docai.view.about";
    private static final String UTM = "?utm_source=aboutdocai&utm_medium=aboutdocai&utm_campaign=docai";

    @Autowired
    private ViewNavigators viewNavigators;

    @Autowired
    private Messages messages;

    @Autowired
    private CoreProperties coreProperties;

    @Test
    void thePageShowsTheVersionAndTheProfile() {
        AboutView view = navigateToAbout();

        Span versionValue = UiTestUtils.getComponent(view, "versionValue");
        Span buildValue = UiTestUtils.getComponent(view, "buildValue");

        assertThat(versionValue.getText()).isNotBlank();
        assertThat(buildValue.getText()).isNotBlank();
    }

    @Test
    void theCommunityAndLearningLinksCarryTheCampaignOfDocAi() {
        AboutView view = navigateToAbout();

        VerticalLayout community = UiTestUtils.getComponent(view, "communityLinks");
        VerticalLayout learn = UiTestUtils.getComponent(view, "learnLinks");

        assertThat(hrefsOf(community)).containsExactly(
                "https://www.jmix.io/" + UTM,
                "https://github.com/jmix-framework/jmix",
                "https://forum.jmix.io/" + UTM,
                "https://www.jmix.io/blog/" + UTM);
        assertThat(hrefsOf(learn)).containsExactly(
                "https://ai-assistant.jmix.io/" + UTM,
                "https://docs.jmix.io/jmix/intro.html" + UTM,
                "https://docs.jmix.io/jmix/concepts/index.html" + UTM,
                "https://www.udemy.com/course/rapid-application-development-with-jmix/",
                "https://www.udemy.com/course/frontend-with-java-and-jmix/?referralCode=FCE0EA245FF7F448C414");
        assertThat(anchorsOf(community)).allSatisfy(anchor -> assertThat(anchor.getTarget()).contains("_blank"));
        assertThat(anchorsOf(learn)).allSatisfy(anchor -> assertThat(anchor.getTarget()).contains("_blank"));
    }

    @Test
    void theSocialLinksAreIconsWithAnAccessibleLabel() {
        AboutView view = navigateToAbout();

        HorizontalLayout social = UiTestUtils.getComponent(view, "socialLinks");

        assertThat(hrefsOf(social)).containsExactly(
                "https://www.youtube.com/channel/UCEmWc8OwhgHnAV7vVVxtglQ",
                "https://www.linkedin.com/company/jmix-platform/",
                "https://www.facebook.com/JmixPlatform",
                "https://twitter.com/JmixPlatform");
        assertThat(anchorsOf(social)).allSatisfy(anchor -> {
            assertThat(anchor.getText()).isBlank();
            assertThat(anchor.getTarget()).contains("_blank");
            assertThat(anchor.getElement().getAttribute("aria-label")).isNotBlank();
        });
    }

    @Test
    void theTextLinksToTheCodeOfDocAi() {
        AboutView view = navigateToAbout();

        Div source = UiTestUtils.getComponent(view, "aboutDocAiSource");

        assertThat(hrefsOf(source)).containsExactly("https://github.com/jmix-framework/jmix-docai");
    }

    @Test
    void russianHasTheSitesAndTheSocialNetworksOfJmixRu() {
        Locale russian = Locale.forLanguageTag("ru");

        assertThat(messages.findMessage(ABOUT_GROUP, "links.website.url", russian))
                .isEqualTo("https://www.jmix.ru/" + UTM);
        assertThat(messages.findMessage(ABOUT_GROUP, "links.section.learn.items", russian))
                .doesNotContain("uiCourse");
        assertThat(messages.findMessage(ABOUT_GROUP, "links.section.social.items", russian))
                .isEqualTo("links.vk,links.telegram,links.max,links.youtube");
    }

    @Test
    void thePageIsNamedLikeItsMenuItemAndTheProductIsDocAiInEveryLanguage() {
        for (Locale locale : coreProperties.getAvailableLocales()) {
            assertThat(messages.getMessage(ABOUT_GROUP, "aboutView.title", locale))
                    .as("the title in %s", locale)
                    .isEqualTo(messages.getMessage("com.company.docai", "menu.about.title", locale));
            assertThat(messages.getMessage(ABOUT_GROUP, "product.name", locale)).isEqualTo("Jmix DocAI");
            assertThat(messages.getMessage(ABOUT_GROUP, "aboutDocAi.title", locale)).contains("Jmix DocAI");
        }
    }

    @Test
    void aLinkIsHiddenOnTheTopLevelDomainsItNames() {
        assertThat(AboutView.isHiddenOnTld("docai.jmix.ru", "ru")).isTrue();
        assertThat(AboutView.isHiddenOnTld("jmix.ru:8080", "ru")).isTrue();
        assertThat(AboutView.isHiddenOnTld("DOCAI.JMIX.RU", "ru")).isTrue();
        assertThat(AboutView.isHiddenOnTld("site.by", "ru, by, kz")).isTrue();

        assertThat(AboutView.isHiddenOnTld("demo.jmix.io", "ru")).isFalse();
        assertThat(AboutView.isHiddenOnTld("localhost", "ru")).isFalse();
        assertThat(AboutView.isHiddenOnTld("docai.jmix.ru", null)).isFalse();
        assertThat(AboutView.isHiddenOnTld("docai.jmix.ru", "  ")).isFalse();
        assertThat(AboutView.isHiddenOnTld(null, "ru")).isFalse();
    }

    private AboutView navigateToAbout() {
        viewNavigators.view(UiTestUtils.getCurrentView(), AboutView.class).navigate();
        return UiTestUtils.getCurrentView();
    }

    private static List<Anchor> anchorsOf(Component container) {
        return container.getChildren()
                .filter(Anchor.class::isInstance)
                .map(Anchor.class::cast)
                .toList();
    }

    private static List<String> hrefsOf(Component container) {
        return anchorsOf(container).stream()
                .map(Anchor::getHref)
                .toList();
    }
}
