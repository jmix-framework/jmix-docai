package com.company.dms.ai.assistant;

import com.company.dms.DMSApplication;
import com.company.dms.test_support.AuthenticatedAsAdmin;
import com.company.dms.test_support.StubAiConfiguration;
import io.jmix.aitools.dataload.introspection.model.EntityPropertyDescriptor;
import io.jmix.aitools.dataload.introspection.model.EntitySummary;
import io.jmix.aitools.dataload.tool.DomainModelDiscoveryTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The boundary of the assistant in the code: the entities and attributes marked {@code @ExcludeFromAi} stay closed
 * even when the settings of a deployment name them.
 */
@SpringBootTest(classes = {DMSApplication.class, StubAiConfiguration.class}, properties = """
        jmix.aitools.dataload.include-entities=Document,Folder,dynat_Category,AccessSet,AccessEntry,User,\
        DocumentRevision,DocumentComment""")
@ExtendWith(AuthenticatedAsAdmin.class)
class ExcludeFromAiTest {

    @Autowired
    private DomainModelDiscoveryTool discovery;

    @Test
    void theClosedEntitiesAndAttributesStayClosedWhenTheSettingsNameThem() {
        ToolContext context = new ToolContext(Map.of());

        List<String> entities = discovery.getAvailableEntities(context)
                .stream()
                .map(EntitySummary::getEntityName)
                .toList();

        List<String> attributes = discovery.getDomainModelForEntities(List.of("Folder"), context)
                .getFirst()
                .getProperties()
                .stream()
                .map(EntityPropertyDescriptor::getName)
                .toList();

        assertThat(entities).containsExactlyInAnyOrder("Document", "Folder", "dynat_Category");
        assertThat(attributes).contains("name", "parent").doesNotContain("nameKey", "accessSet");
    }
}
