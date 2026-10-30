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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class LocalizationTest {

    private static final Locale RUSSIAN = Locale.forLanguageTag("ru");

    @Autowired
    private CoreProperties coreProperties;

    @Autowired
    private Messages messages;

    @Test
    void theApplicationOffersEnglishAndRussian() {
        assertThat(coreProperties.getAvailableLocales()).containsExactly(Locale.ENGLISH, RUSSIAN);
    }

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
}
