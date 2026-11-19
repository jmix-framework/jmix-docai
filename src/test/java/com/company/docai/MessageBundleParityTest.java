package com.company.docai;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MessageBundleParityTest {

    @Test
    void everyBundleCarriesExactlyTheKeysOfTheEnglishOne() throws IOException {
        Set<String> english = keysOf(new ClassPathResource("com/company/docai/messages_en.properties"));
        Resource[] bundles = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:com/company/docai/messages_*.properties");

        assertThat(english).isNotEmpty();
        assertThat(bundles).hasSizeGreaterThan(1);

        for (Resource bundle : bundles) {
            assertThat(keysOf(bundle))
                    .as(bundle.getFilename())
                    .containsExactlyInAnyOrderElementsOf(english);
        }
    }

    @Test
    void aTextOfJmixTheApplicationRewordsReadsDifferentlyInEveryLanguage() {
        Map<String, String> bundlesOfJmix = Map.of(
                "en", "classpath*:io/jmix/**/messages.properties",
                "ru", "classpath*:io/jmix/**/messages_ru.properties");

        bundlesOfJmix.forEach((language, pattern) -> {
            Map<String, Set<String>> jmix = textsOf(pattern);
            Properties application = propertiesOf(new ClassPathResource(
                    "com/company/docai/messages_" + language + ".properties"));

            for (String key : application.stringPropertyNames()) {
                String text = application.getProperty(key);

                assertThat(jmix.getOrDefault(key, Set.of()))
                        .as("%s in %s", key, language)
                        .doesNotContain(text);

                if (jmix.containsKey(key) && "ru".equals(language)) {
                    assertThat(text)
                            .as("%s in %s", key, language)
                            .containsPattern("\\p{IsCyrillic}");
                }
            }
        });
    }

    private static Set<String> keysOf(Resource bundle) {
        return propertiesOf(bundle).stringPropertyNames();
    }

    private static Map<String, Set<String>> textsOf(String pattern) {
        Map<String, Set<String>> texts = new HashMap<>();

        try {
            for (Resource bundle : new PathMatchingResourcePatternResolver().getResources(pattern)) {
                Properties properties = propertiesOf(bundle);

                properties.stringPropertyNames()
                        .forEach(key -> texts.computeIfAbsent(key, ignored -> new HashSet<>())
                                .add(properties.getProperty(key)));
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        assertThat(texts)
                .as(pattern)
                .isNotEmpty();

        return texts;
    }

    private static Properties propertiesOf(Resource bundle) {
        Properties properties = new Properties();

        try (Reader reader = new InputStreamReader(bundle.getInputStream(), StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        return properties;
    }
}
