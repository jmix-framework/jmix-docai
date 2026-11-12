package com.company.docai.view.search;

import com.company.docai.test_support.AuthenticatedAsAdmin;
import com.company.docai.view.search.RecentQueries.RecentQuery;
import io.jmix.flowui.settings.UserSettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The recent searches of the person: how many stay, in which order, how they are kept, how one is removed and what a
 * broken setting gives.
 */
@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class RecentQueriesTest {

    @Autowired
    private RecentQueries recentQueries;

    @Autowired
    private UserSettingsService userSettingsService;

    @BeforeEach
    void startWithoutSearches() {
        userSettingsService.delete(RecentQueries.RECENT_QUERIES_SETTING);
    }

    @AfterEach
    void forgetTheSearches() {
        userSettingsService.delete(RecentQueries.RECENT_QUERIES_SETTING);
    }

    @Test
    void theLastFiveSearchesComeNewestFirstEachTextOnceWhateverItsCase() {
        List.of("lease", "tractor", "invoice", "contract", "act")
                .forEach(text -> recentQueries.add(text, false));

        recentQueries.add("  LEASE  ", true);
        recentQueries.add("supply", false);

        assertThat(recentQueries.load()).containsExactly(
                new RecentQuery("supply", false),
                new RecentQuery("LEASE", true),
                new RecentQuery("act", false),
                new RecentQuery("contract", false),
                new RecentQuery("invoice", false));
    }

    @Test
    void aSearchIsKeptWithItsTextAndTheModeOfTheHeaderAndAnEmptyOneIsNot() {
        recentQueries.add("   ", true);

        assertThat(userSettingsService.load(RecentQueries.RECENT_QUERIES_SETTING)).isEmpty();

        recentQueries.add("tractor", true);
        recentQueries.add("lease", false);

        assertThat(userSettingsService.load(RecentQueries.RECENT_QUERIES_SETTING)).hasValueSatisfying(setting ->
                assertThat(setting).contains("\"text\":\"tractor\"", "\"mode\":\"ai\"", "\"text\":\"lease\"",
                        "\"mode\":\"words\""));
    }

    @Test
    void aRemovedSearchGoesWithItsExactTextAndModeAndTheOthersKeepTheirOrder() {
        recentQueries.add("lease", false);
        recentQueries.add("tractor", true);
        recentQueries.add("invoice", false);

        // another mode or another case is another search
        recentQueries.remove("tractor", false);
        recentQueries.remove("Tractor", true);

        assertThat(recentQueries.load()).containsExactly(
                new RecentQuery("invoice", false),
                new RecentQuery("tractor", true),
                new RecentQuery("lease", false));

        recentQueries.remove("tractor", true);

        assertThat(recentQueries.load()).containsExactly(
                new RecentQuery("invoice", false),
                new RecentQuery("lease", false));

        recentQueries.remove("invoice", false);
        recentQueries.remove("lease", false);

        assertThat(recentQueries.load()).isEmpty();
    }

    @Test
    void aBrokenSettingGivesNoSearchesAndAStrangeEntryGivesWhatCanBeRead() {
        userSettingsService.save(RecentQueries.RECENT_QUERIES_SETTING, "[{\"text\":");

        assertThat(recentQueries.load()).isEmpty();

        // the next search writes the setting anew
        recentQueries.add("tractor", true);

        assertThat(recentQueries.load()).containsExactly(new RecentQuery("tractor", true));

        userSettingsService.save(RecentQueries.RECENT_QUERIES_SETTING, "[{\"text\":\"lease\",\"mode\":\"telepathy\"},"
                + "{\"text\":\"  \",\"mode\":\"ai\"},{\"mode\":\"ai\"},null]");

        assertThat(recentQueries.load()).containsExactly(new RecentQuery("lease", false));
    }
}
