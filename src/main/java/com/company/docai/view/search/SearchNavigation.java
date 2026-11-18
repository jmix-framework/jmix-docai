package com.company.docai.view.search;

import com.company.docai.ai.AiSettings;
import com.google.common.base.Strings;
import com.vaadin.flow.router.QueryParameters;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.view.View;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Opens what a search leads to: the search page by words or by meaning and the page of the documents with a tag.
 */
@Component
public class SearchNavigation {

    private final ViewNavigators viewNavigators;
    private final RecentQueries recentQueries;
    private final AiSettings aiSettings;

    public SearchNavigation(ViewNavigators viewNavigators, RecentQueries recentQueries, AiSettings aiSettings) {
        this.viewNavigators = viewNavigators;
        this.recentQueries = recentQueries;
        this.aiSettings = aiSettings;
    }

    /**
     * Opens the search page with the text, which joins the recent searches; an empty text opens nothing, and the search
     * goes by meaning only while the AI is on. From the main view a search page that is open shows the new search; from
     * a search page a new one opens.
     */
    public void openSearch(View<?> origin, @Nullable String text, boolean ai) {
        String query = Strings.nullToEmpty(text).strip();

        if (query.isEmpty()) {
            return;
        }

        boolean aiQuery = ai && aiSettings.isAvailable();
        recentQueries.add(query, aiQuery);

        QueryParameters parameters = QueryParameters.of(DocumentSearchView.QUERY_PARAMETER, query);

        if (aiQuery) {
            parameters = parameters.merging(DocumentSearchView.MODE_PARAMETER, DocumentSearchView.AI_MODE);
        }

        viewNavigators.view(origin, DocumentSearchView.class)
                .withQueryParameters(parameters)
                .navigate();
    }

    /**
     * Opens the page of the documents with the tag. From the main view a search page that is open shows the tag; from
     * a search page a new one opens.
     */
    public void openTag(View<?> origin, String name) {
        viewNavigators.view(origin, DocumentSearchView.class)
                .withQueryParameters(QueryParameters.of(DocumentSearchView.TAG_PARAMETER, name))
                .navigate();
    }
}
