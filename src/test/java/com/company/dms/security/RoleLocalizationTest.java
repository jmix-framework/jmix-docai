package com.company.dms.security;

import com.company.dms.security.role.AccessRowLevelRole;
import io.jmix.security.model.ResourceRole;
import io.jmix.security.model.RowLevelRole;
import io.jmix.security.role.ResourceRoleRepository;
import io.jmix.security.role.RoleLocalizationSupport;
import io.jmix.security.role.RowLevelRoleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RoleLocalizationTest {

    private static final Locale RUSSIAN = Locale.forLanguageTag("ru");

    @Autowired
    private ResourceRoleRepository resourceRoleRepository;

    @Autowired
    private RowLevelRoleRepository rowLevelRoleRepository;

    @Autowired
    private RoleLocalizationSupport roleLocalizationSupport;

    @ParameterizedTest
    @CsvSource({
            "system-full-access, Full Access",
            "employee, Employee",
            "ui-minimal, UI: minimal access"
    })
    void theRoleNameIsTranslated(String code, String englishName) {
        ResourceRole role = resourceRoleRepository.getRoleByCode(code);

        assertThat(roleLocalizationSupport.getLocalizedName(role, Locale.ENGLISH))
                .isEqualTo(englishName);
        assertThat(roleLocalizationSupport.getLocalizedName(role, RUSSIAN))
                .matches(".*\\p{IsCyrillic}.*");
    }

    @ParameterizedTest
    @ValueSource(strings = {"system-full-access", "employee", "ui-minimal"})
    void theRoleDescriptionIsTranslated(String code) {
        ResourceRole role = resourceRoleRepository.getRoleByCode(code);

        assertThat(roleLocalizationSupport.getLocalizedDescription(role, Locale.ENGLISH))
                .isNotBlank()
                .doesNotContain(".description");
        assertThat(roleLocalizationSupport.getLocalizedDescription(role, RUSSIAN))
                .matches(".*\\p{IsCyrillic}.*");
    }

    @Test
    void theAccessRowLevelRoleIsTranslated() {
        RowLevelRole role = rowLevelRoleRepository.getRoleByCode(AccessRowLevelRole.CODE);

        assertThat(roleLocalizationSupport.getLocalizedName(role, Locale.ENGLISH))
                .isEqualTo("Folder and document access");
        assertThat(roleLocalizationSupport.getLocalizedName(role, RUSSIAN))
                .matches(".*\\p{IsCyrillic}.*");
        assertThat(roleLocalizationSupport.getLocalizedDescription(role, RUSSIAN))
                .matches(".*\\p{IsCyrillic}.*");
    }
}
