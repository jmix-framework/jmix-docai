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

    // beyond the keys of the application a bundle has only the texts of Jmix that the translation pack of its language
    // lacks, in the block at its end; once the pack has one, it is deleted there, and the pack's text shows
    @Test
    void everyBundleCarriesTheKeysOfTheEnglishOneAndBeyondThemOnlyTheTextsOfJmixItsPackLacks() throws IOException {
        Set<String> english = keysOf(new ClassPathResource("com/company/docai/messages_en.properties"));
        Set<String> jmix = textsOf("classpath*:io/jmix/**/messages.properties").keySet();
        Resource[] bundles = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:com/company/docai/messages_*.properties");

        assertThat(english).isNotEmpty();
        assertThat(bundles).hasSizeGreaterThan(1);

        for (Resource bundle : bundles) {
            Set<String> keys = keysOf(bundle);
            Set<String> beyond = new HashSet<>(keys);
            beyond.removeAll(english);

            assertThat(keys)
                    .as(bundle.getFilename())
                    .containsAll(english);

            if (!beyond.isEmpty()) {
                Set<String> pack = textsOf("classpath*:io/jmix/**/messages_" + languageOf(bundle) + ".properties")
                        .keySet();

                assertThat(beyond)
                        .as("%s: keys beyond the English bundle that are no texts of Jmix", bundle.getFilename())
                        .isSubsetOf(jmix);
                assertThat(beyond)
                        .as("%s: texts of Jmix that its translation pack has now", bundle.getFilename())
                        .doesNotContainAnyElementsOf(pack);
            }
        }
    }

    @Test
    void aTextOfJmixTheApplicationRewordsReadsDifferentlyInEveryLanguage() {
        Map<String, String> bundlesOfJmix = Map.of(
                "en", "classpath*:io/jmix/**/messages.properties",
                "ru", "classpath*:io/jmix/**/messages_ru.properties",
                "de", "classpath*:io/jmix/**/messages_de.properties",
                "es", "classpath*:io/jmix/**/messages_es.properties",
                "it", "classpath*:io/jmix/**/messages_it.properties");

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

    // messages_de.properties is German
    private static String languageOf(Resource bundle) {
        String name = String.valueOf(bundle.getFilename());

        return name.substring("messages_".length(), name.length() - ".properties".length());
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
