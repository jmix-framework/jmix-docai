package com.company.docai.view.dashboard;

import com.company.docai.DocAiApplication;
import com.company.docai.app.service.dashboard.DashboardService;
import com.company.docai.app.service.dashboard.Summary;
import com.company.docai.app.service.dashboard.UpcomingDate;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.app.service.user.UserService;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.model.user.User;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.TestDashboards;
import com.company.docai.test_support.TestFiles;
import com.company.docai.test_support.TestMenus;
import com.company.docai.view.main.MainView;
import com.google.common.base.Strings;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.card.Card;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.Id;
import io.jmix.core.Messages;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.security.ClientDetails;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.dynattr.DynAttrQueryHints;
import io.jmix.dynattr.model.Category;
import io.jmix.flowui.Fragments;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dashboard as an employee sees it with the AI off. The test makes the fragment as the main view does and gives it
 * handlers that keep what they get. The database is shared with the other tests, so the assertions look for the rows of
 * the test, and the numbers are those the service gives.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
class DashboardFragmentTest {

    private static final String OTHER = "maria";

    @Autowired
    private Fragments fragments;

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private UserService userService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private CurrentAuthentication currentAuthentication;

    @Autowired
    private Messages messages;

    @TempDir
    private Path tempDir;

    private Folder sandbox;
    private DashboardFragment dashboard;
    private final List<Document> opened = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(folderService.getRoot());
        folder.setName("dashboard-fragment-test-" + UUID.randomUUID());
        sandbox = folderService.save(folder);

        dashboard = fragments.create(TestMenus.mainView(), DashboardFragment.class);
        dashboard.setDocumentOpenHandler(opened::add);
    }

    @AfterEach
    void deleteSandbox() {
        dataManager.load(Id.of(sandbox))
                .optional()
                .ifPresent(folderService::delete);
    }

    @Test
    void withTheAiOffTheQuestionAndTheChatsAreHidden() {
        dashboard.refresh();

        assertThat(TestDashboards.<VerticalLayout>component(dashboard, "askBox").isVisible()).isFalse();
        assertThat(TestDashboards.<Card>component(dashboard, "chatsCard").isVisible()).isFalse();
    }

    @Test
    void theGreetingCallsTheUserByTheFirstName() {
        User user = (User) currentAuthentication.getUser();
        String name = Strings.isNullOrEmpty(user.getFirstName()) ? user.getUsername() : user.getFirstName();

        dashboard.refresh();

        assertThat(TestDashboards.<H2>component(dashboard, "greeting").getText())
                .isIn(Stream.of("morning", "afternoon", "evening", "night")
                        .map(part -> messages.formatMessage(DashboardFragment.class, "greeting." + part, name))
                        .toList());
    }

    @Test
    void aDocumentWithoutATypeIsCountedAndOpensItsCardButOffersNoFillingWithoutTheAi() {
        Document scan = upload("scan.txt");

        dashboard.refresh();
        Div row = TestDashboards.row(dashboard, "untypedBox", scan.getName());

        assertThat(TestDashboards.<H3>component(dashboard, "untypedTitle").getElement().getAttribute("data-count"))
                .isEqualTo(String.valueOf(dashboardService.untypedDocuments().count()));

        assertThat(TestDashboards.buttons(row)).isEmpty();

        TestDashboards.click(row);

        assertThat(opened).containsExactly(scan);
    }

    @Test
    void aDateOfTheWeekStandsOutInAYellowBadgeAndALaterOneDoesNot() {
        LocalDate today = dashboardService.now().toLocalDate();
        Document soon = typed(upload("soon.txt"), "Invoice", Map.of("+dueDate", today.plusDays(2)));
        Document later = typed(upload("later.txt"), "Contract", Map.of("+validUntil", today.plusDays(8)));
        Map<Document, Boolean> ofTheWeek = Map.of(soon, true, later, false);

        dashboard.refresh();
        List<UpcomingDate> shown = dashboardService.upcomingDates();

        TestDashboards.assertNearestShown(shown, Map.of(soon, today.plusDays(2), later, today.plusDays(8)));

        // the dates of other tests may take some of the five places, so the badge is read on the rows of the test that
        // show
        shown.stream()
                .map(UpcomingDate::document)
                .filter(ofTheWeek::containsKey)
                .forEach(document -> {
                    Component date = dateOf(document);

                    assertThat(date).isInstanceOf(ofTheWeek.get(document) ? Badge.class : Span.class);
                    assertThat(date.hasClassName("aura-accent-yellow")).isEqualTo(ofTheWeek.get(document));
                });
    }

    @Test
    void todayIsTheDayOfTheTimeZoneOfTheUser() {
        // the day starts there first, so for half of every day it is not the day of the server
        ZoneId zone = ZoneId.of("Pacific/Kiritimati");
        ((User) currentAuthentication.getUser()).setTimeZoneId(zone.getId());
        LocalDate today = LocalDate.now(zone);
        Document dueToday = typed(upload("due-today.txt"), "Invoice", Map.of("+dueDate", today));
        Document dueYesterday = typed(upload("due-yesterday.txt"), "Invoice", Map.of("+dueDate", today.minusDays(1)));

        dashboard.refresh();
        List<UpcomingDate> shown = dashboardService.upcomingDates();

        assertThat(dashboardService.now().getZone()).isEqualTo(zone);
        TestDashboards.assertNearestShown(shown, Map.of(dueToday, today));

        assertThat(TestDashboards.hasRow(dashboard, "upcomingBox", dueToday.getName()))
                .isEqualTo(shown.stream().anyMatch(upcoming -> upcoming.document().equals(dueToday)));

        assertThat(TestDashboards.hasRow(dashboard, "upcomingBox", dueYesterday.getName())).isFalse();
    }

    @Test
    void theGreetingFollowsThePartOfTheDayInTheTimeZoneOfTheUser() {
        User user = (User) currentAuthentication.getUser();
        String name = Strings.isNullOrEmpty(user.getFirstName()) ? user.getUsername() : user.getFirstName();

        // the English evening and night read alike, so the test reads the Russian greetings, which differ
        ((AbstractAuthenticationToken) currentAuthentication.getAuthentication()).setDetails(ClientDetails.builder()
                .locale(Locale.forLanguageTag("ru"))
                .build());

        // the first and the last hour of each part of the day
        Map<Integer, String> parts = Map.of(5, "morning", 11, "morning", 12, "afternoon", 16, "afternoon",
                17, "evening", 22, "evening", 23, "night", 4, "night");

        parts.forEach((hour, part) -> {
            user.setTimeZoneId(zoneAtHalfPast(hour));
            dashboard.refresh();

            assertThat(TestDashboards.<H2>component(dashboard, "greeting").getText())
                    .as("the greeting at %s o'clock", hour)
                    .isEqualTo(messages.formatMessage(DashboardFragment.class, "greeting." + part, name));
        });
    }

    @Test
    void theSummaryLineNamesTheNearestDeadlineAndTheNumberOfTheDocumentsWithoutAType() {
        LocalDate today = dashboardService.now().toLocalDate();
        typed(upload("due.txt"), "Invoice", Map.of("+dueDate", today));
        upload("untyped.txt");

        dashboard.refresh();
        String line = TestDashboards.<Span>component(dashboard, "summaryLine").getText();

        LocalDate nearest = dashboardService.upcomingDates()
                .getFirst()
                .date();

        String date = DateTimeFormatter.ofPattern(messages.getMessage(DashboardFragment.class, "longDate.format"),
                        currentAuthentication.getLocale())
                .format(nearest);

        assertThat(line)
                .startsWith(messages.formatMessage(DashboardFragment.class, "summaryLine.deadline", date))
                .endsWith(messages.formatMessage(DashboardFragment.class, "summaryLine.untyped",
                        dashboardService.untypedDocuments().count()));
    }

    @Test
    void anEventOfAnotherPersonNamesThePersonTheEventAndTheDocumentAndOpensItsCard() {
        Document lease = systemAuthenticator.withUser(OTHER, () -> upload("lease.txt"));

        User other = userService.usersOf(List.of(OTHER))
                .get(OTHER);

        dashboard.refresh();
        Div row = TestDashboards.row(dashboard, "activityBox", lease.getName());

        // the person goes by the first and the last name, without the username in brackets of the lists
        assertThat(TestDashboards.text(row))
                .contains(other.getFirstName() + " " + other.getLastName(),
                        messages.getMessage(DashboardFragment.class, "event.upload"))
                .doesNotContain("[" + OTHER + "]");

        TestDashboards.click(row);

        assertThat(opened).containsExactly(lease);
    }

    @Test
    void theBarsOfTheSummaryMeasureAgainstTheLargestNumberOfTheirGroupAndAStatusPaintsItsOwn() {
        upload("untyped.txt");

        dashboard.refresh();
        Summary summary = dashboardService.summary();
        List<Div> rows = summaryRows();
        Div firstStatus = rows.get(summary.types().size());

        assertThat(rows).hasSize(summary.types().size() + summary.statuses().size());
        assertThat(fill(rows.getFirst()).getStyle().get("width")).isEqualTo("100%");

        assertThat(TestDashboards.text(rows.get(summary.types().size() - 1)))
                .startsWith(messages.getMessage(DashboardFragment.class, "untypedRow.text"));

        assertThat(fill(firstStatus).getStyle().get("background-color"))
                .isEqualTo(summary.statuses().getFirst().color());
    }

    private List<Div> summaryRows() {
        return TestDashboards.<VerticalLayout>component(dashboard, "summaryBox")
                .getChildren()
                .filter(child -> child.hasClassName("dashboard-summary-group"))
                .flatMap(Component::getChildren)
                .filter(child -> child.hasClassName("dashboard-summary-row"))
                .map(Div.class::cast)
                .toList();
    }

    // the bar of a row of the summary, and the part of it the number fills
    private static Div fill(Div row) {
        return (Div) row.getChildren()
                .filter(child -> child.hasClassName("dashboard-bar"))
                .findFirst()
                .orElseThrow()
                .getChildren()
                .findFirst()
                .orElseThrow();
    }

    // the date at the start of the row of an upcoming document
    private Component dateOf(Document document) {
        return TestDashboards.parts(TestDashboards.row(dashboard, "upcomingBox", document.getName()))
                .getFirst();
    }

    // a zone where it is half past the hour now, so the hour holds while the test reads the dashboard
    private static String zoneAtHalfPast(int hour) {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        int minutes = Math.floorMod(hour * 60 + 30 - now.getHour() * 60 - now.getMinute() + 12 * 60, 24 * 60) - 12 * 60;

        return ZoneId.ofOffset("GMT", ZoneOffset.ofTotalSeconds(minutes * 60))
                .getId();
    }

    // a name of its own, so that a row of another test with the same file name is never taken for it
    private Document upload(String fileName) {
        return documentService.upload(sandbox, List.of(TestFiles.file(tempDir, UUID.randomUUID() + "-" + fileName,
                        "A document of the test")))
                .created()
                .getFirst();
    }

    private Document typed(Document document, String type, Map<String, Object> fields) {
        Document edited = dataManager.load(Document.class)
                .id(document.getId())
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("folder", FetchPlan.BASE)
                        .add("category", FetchPlan.INSTANCE_NAME))
                .hint(DynAttrQueryHints.LOAD_DYN_ATTR, true)
                .one();

        edited.setCategory(dataManager.load(Category.class)
                .query("select c from dynat_Category c where c.name = :name and c.entityType = 'Document'")
                .parameter("name", type)
                .one());

        fields.forEach((field, value) -> EntityValues.setValue(edited, field, value));

        return documentService.save(edited, null, null);
    }
}
