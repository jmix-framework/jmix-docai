package com.company.docai;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
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

    private Set<String> keysOf(Resource bundle) throws IOException {
        Properties properties = new Properties();

        try (Reader reader = new InputStreamReader(bundle.getInputStream(), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }

        return properties.stringPropertyNames();
    }
}
