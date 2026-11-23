package com.company.docai;

import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.model.tag.Tag;
import io.jmix.core.CoreProperties;
import io.jmix.core.Messages;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class LocalizationTest {

    private static final Locale RUSSIAN = Locale.forLanguageTag("ru");

    @Autowired
    private CoreProperties coreProperties;

    @Autowired
    private Messages messages;

    @Test
    void theApplicationIsCalledJmixDocAiInEveryLanguage() {
        for (Locale locale : coreProperties.getAvailableLocales()) {
            assertThat(messages.getMessage("com.company.docai.view.main/applicationTitle.text", locale))
                    .isEqualTo("Jmix DocAI");
            assertThat(messages.getMessage("com.company.docai.view.login/loginForm.headerTitle", locale))
                    .isEqualTo("Jmix DocAI");
        }
    }

    @Test
    void russianCaptionsOfTheApplicationAndTheFrameworkAreTranslated() {
        assertThat(messages.getMessage("com.company.docai.view.login/loginForm.submit", RUSSIAN))
                .containsPattern("\\p{IsCyrillic}");
        assertThat(messages.getMessage("com.company.docai.model.user/User", RUSSIAN))
                .containsPattern("\\p{IsCyrillic}");
        assertThat(messages.getMessage("actions.logout.text", RUSSIAN))
                .containsPattern("\\p{IsCyrillic}");
    }

    // a language without the translation pack of the framework gets its English captions
    @Test
    void theCaptionsOfTheApplicationAndTheFrameworkAreTranslatedInEveryLanguage() {
        List<String> keys = List.of("com.company.docai.view.login/loginForm.submit",
                "com.company.docai.model.user/User", "actions.logout.text", "actions.Cancel");

        for (Locale locale : coreProperties.getAvailableLocales()) {
            if (locale.getLanguage().equals(Locale.ENGLISH.getLanguage())) {
                continue;
            }

            assertThat(keys).allSatisfy(key -> assertThat(messages.getMessage(key, locale))
                    .as("%s in %s", key, locale)
                    .isNotEqualTo(messages.getMessage(key, Locale.ENGLISH)));
        }
    }

    @Test
    void theNameKeysHaveCaptionsInEveryLanguage() {
        for (Class<?> entityClass : List.of(Folder.class, Document.class, Tag.class)) {
            for (Locale locale : coreProperties.getAvailableLocales()) {
                assertThat(messages.findMessage(entityClass.getPackageName(),
                        entityClass.getSimpleName() + ".nameKey", locale))
                        .as("%s.nameKey in %s", entityClass.getSimpleName(), locale)
                        .isNotBlank();
            }
        }
    }

    @Test
    void theFailuresOfTheAiReadAsOneMessageInEveryLanguage() {
        List<String> failures = List.of(
                "io.jmix.aitoolsflowui.view.chat/aiChatFragment.errorProcessingMessage",
                "aiMessageList.i18n.error",
                "aiChat.i18n.failed");

        for (Locale locale : coreProperties.getAvailableLocales()) {
            String fillWithAi = messages.getMessage("com.company.docai.view.document/fillWithAiAction.failed", locale);

            assertThat(failures).allSatisfy(key -> assertThat(messages.getMessage(key, locale))
                    .as("%s in %s", key, locale)
                    .isEqualTo(fillWithAi));
        }
    }

    @Test
    void theLastStageOfTheWaitForTheAiPromisesNoAnswer() {
        assertThat(messages.getMessage("aiMessageList.i18n.almostDone", Locale.ENGLISH)).isEqualTo("Still working…");
    }

    @Test
    void thePagesOfTheAddOnsAreNamedLikeTheirMenuItemsInEveryLanguage() {
        Map<String, String> itemsOfPages = Map.of(
                "io.jmix.aitoolsflowui.view.chathub/aiChatHubView.title",
                "com.company.docai/menu.assistantChat.title",
                "io.jmix.dynattrflowui.view.category/categoryListView.title",
                "com.company.docai/menu.documentTypes.title");

        for (Locale locale : coreProperties.getAvailableLocales()) {
            itemsOfPages.forEach((page, item) -> assertThat(messages.getMessage(page, locale))
                    .as("%s in %s", page, locale)
                    .isEqualTo(messages.getMessage(item, locale)));
        }
    }
}
