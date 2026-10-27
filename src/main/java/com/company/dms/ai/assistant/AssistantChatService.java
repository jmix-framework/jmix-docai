package com.company.dms.ai.assistant;

import com.company.dms.ai.AiSettings;
import com.company.dms.model.document.Document;
import com.company.dms.view.document.DocumentDetailView;
import com.vaadin.flow.router.RouteParameters;
import io.jmix.aitoolsflowuidata.entity.AiChatMessageEntity;
import io.jmix.aitoolsflowuidata.service.impl.AiChatDataService;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.flowui.view.ViewRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The chat of the assistant in the menu: the chat of the AI Tools add-on, which is on while the AI is, and whose
 * answers link the documents they name to their cards and are never saved empty.
 */
@Component
public class AssistantChatService extends AiChatDataService {

    private static final Pattern LINK = Pattern.compile("(?<!!)\\[([^\\n\\]]+)]\\(([^)\\s]+)\\)");
    private static final Pattern ID = Pattern.compile("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");

    private final DataManager secureDataManager;
    private final ViewRegistry viewRegistry;
    private final AiSettings aiSettings;

    public AssistantChatService(DataManager secureDataManager, ViewRegistry viewRegistry, AiSettings aiSettings) {
        this.secureDataManager = secureDataManager;
        this.viewRegistry = viewRegistry;
        this.aiSettings = aiSettings;
    }

    // Spring AI makes a client without a key as well, and the chat would take questions only to fail on each
    @Override
    public boolean isAvailable() {
        return aiSettings.isAvailable() && super.isAvailable();
    }

    // the add-on saves an empty answer as an empty message; an error lets the chat say that the AI did not answer
    @Override
    protected void saveAssistantResponse(AiChatMessageEntity placeholder, @Nullable String response) {
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("The model answered with no text");
        }

        super.saveAssistantResponse(placeholder, withCardLinks(response));
    }

    // the model knows the id of a document from the tools, and only the code knows the address of its card; a link
    // to a document the person cannot open, or to none, becomes plain text
    private String withCardLinks(String answer) {
        Set<UUID> ids = LINK.matcher(answer)
                .results()
                .map(link -> idIn(link.group(2)))
                .flatMap(Optional::stream)
                .collect(Collectors.toSet());

        if (ids.isEmpty()) {
            return answer;
        }

        Set<UUID> readable = secureDataManager.load(Document.class)
                .query("select d from Document d where d.id in :ids")
                .parameter("ids", ids)
                .fetchPlan(FetchPlan.INSTANCE_NAME)
                .list()
                .stream()
                .map(Document::getId)
                .collect(Collectors.toSet());

        return LINK.matcher(answer)
                .replaceAll(link -> Matcher.quoteReplacement(cardLink(link, readable)));
    }

    private String cardLink(MatchResult link, Set<UUID> readable) {
        Optional<UUID> id = idIn(link.group(2));

        if (id.isEmpty()) {
            return link.group();
        }

        return readable.contains(id.get())
                ? "[%s](%s)".formatted(link.group(1), cardUrl(id.get()))
                : link.group(1);
    }

    private static Optional<UUID> idIn(String target) {
        Matcher id = ID.matcher(target);

        return id.find() ? Optional.of(UUID.fromString(id.group())) : Optional.empty();
    }

    // relative to the root of the application, which the pages of Vaadin set as their base address
    private String cardUrl(UUID id) {
        return viewRegistry.getRouteConfiguration()
                .getUrl(DocumentDetailView.class, new RouteParameters("id", id.toString()));
    }
}
