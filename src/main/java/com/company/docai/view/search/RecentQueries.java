package com.company.docai.view.search;

import io.jmix.flowui.settings.UserSettingsService;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The last searches of the person signed in, newest first: what the search palette opened the search page with. The
 * palette lists them before the person types, and the person removes them there. They live in a user setting of the
 * authenticated user, so a substitute keeps their own searches.
 */
@Component
public class RecentQueries {

    /**
     * The user setting with the last searches: a JSON array of objects with the text and the mode, newest first.
     */
    public static final String RECENT_QUERIES_SETTING = "docai.recentQueries";

    /**
     * The number of the searches kept.
     */
    public static final int MAX_QUERIES = 5;

    // the values of the mode setting of the header, MainView.SEARCH_MODE_SETTING
    private static final String AI_MODE = DocumentSearchView.AI_MODE;
    private static final String WORDS_MODE = "words";

    private static final JsonMapper JSON = JsonMapper.builder()
            .build();

    private final UserSettingsService userSettingsService;

    public RecentQueries(UserSettingsService userSettingsService) {
        this.userSettingsService = userSettingsService;
    }

    /**
     * Returns the last searches, newest first. A setting that cannot be read gives none, and the next search writes
     * it anew; an entry without a text is left out, and an unknown mode reads as the search by words.
     */
    public List<RecentQuery> load() {
        return userSettingsService.load(RECENT_QUERIES_SETTING)
                .map(RecentQueries::parse)
                .orElse(List.of());
    }

    /**
     * Puts the search first: the same text in any case leaves its old place, an empty text is not kept, and only
     * the last {@link #MAX_QUERIES} stay.
     */
    public void add(String text, boolean ai) {
        String stripped = text.strip();

        if (stripped.isEmpty()) {
            return;
        }

        List<RecentQuery> queries = new ArrayList<>();
        queries.add(new RecentQuery(stripped, ai));

        load().stream()
                .filter(query -> !query.text().equalsIgnoreCase(stripped))
                .limit(MAX_QUERIES - 1)
                .forEach(queries::add);

        save(queries);
    }

    /**
     * Removes the search with exactly this text and mode, as {@link #load()} gives it; the others keep their order.
     */
    public void remove(String text, boolean ai) {
        RecentQuery removed = new RecentQuery(text, ai);
        List<RecentQuery> queries = load();

        if (queries.contains(removed)) {
            save(queries.stream()
                    .filter(query -> !query.equals(removed))
                    .toList());
        }
    }

    private void save(List<RecentQuery> queries) {
        List<StoredQuery> stored = queries.stream()
                .map(query -> new StoredQuery(query.text(), query.ai() ? AI_MODE : WORDS_MODE))
                .toList();

        userSettingsService.save(RECENT_QUERIES_SETTING, JSON.writeValueAsString(stored));
    }

    private static List<RecentQuery> parse(String setting) {
        StoredQuery[] stored;

        try {
            stored = JSON.readValue(setting, StoredQuery[].class);
        } catch (JacksonException e) {
            return List.of();
        }

        // a setting of null reads as no array at all
        if (stored == null) {
            return List.of();
        }

        return Arrays.stream(stored)
                .filter(Objects::nonNull)
                .filter(query -> query.text() != null && !query.text().isBlank())
                .map(query -> new RecentQuery(query.text().strip(), AI_MODE.equals(query.mode())))
                .toList();
    }

    /**
     * A search of the person.
     *
     * @param text the text without the spaces around it
     * @param ai   whether the search went by meaning
     */
    public record RecentQuery(String text, boolean ai) {
    }

    private record StoredQuery(@Nullable String text, @Nullable String mode) {
    }
}
