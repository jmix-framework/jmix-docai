package com.company.docai.view.tag;

import com.company.docai.DocAiApplication;
import com.company.docai.model.tag.Tag;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.html.Span;
import io.jmix.core.Metadata;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@UiTest
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
class TagBadgesTest {

    @Autowired
    private TagBadges tagBadges;
    @Autowired
    private Metadata metadata;

    @Test
    void aTagWearsTheAccentOfTheBrand() {
        Tag tag = metadata.create(Tag.class);
        tag.setName("lease");

        Span badges = tagBadges.create(List.of(tag));

        Component button = badges.getChildren()
                .findFirst()
                .orElseThrow();
        Badge badge = (Badge) button.getChildren()
                .findFirst()
                .orElseThrow();
        assertThat(badge.getClassNames()).contains("aura-accent-color").doesNotContain("aura-accent-blue");
    }
}
