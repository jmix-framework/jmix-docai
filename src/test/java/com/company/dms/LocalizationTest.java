package com.company.dms;

import io.jmix.core.CoreProperties;
import io.jmix.core.Messages;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
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
    void theApplicationIsCalledJmixDmsInEveryLanguage() {
        for (Locale locale : coreProperties.getAvailableLocales()) {
            assertThat(messages.getMessage("com.company.dms.view.main/applicationTitle.text", locale))
                    .isEqualTo("Jmix DMS");
            assertThat(messages.getMessage("com.company.dms.view.login/loginForm.headerTitle", locale))
                    .isEqualTo("Jmix DMS");
        }
    }

    @Test
    void russianCaptionsOfTheApplicationAndTheFrameworkAreTranslated() {
        assertThat(messages.getMessage("com.company.dms.view.login/loginForm.submit", RUSSIAN))
                .containsPattern("\\p{IsCyrillic}");
        assertThat(messages.getMessage("com.company.dms.model.user/User", RUSSIAN))
                .containsPattern("\\p{IsCyrillic}");
        assertThat(messages.getMessage("actions.logout.text", RUSSIAN))
                .containsPattern("\\p{IsCyrillic}");
    }
}
