package com.company.docai.dashboard;

import com.company.docai.app.service.dashboard.Activity;
import com.company.docai.app.service.dashboard.DashboardService;
import com.company.docai.app.service.dashboard.RecentChat;
import com.company.docai.app.service.dashboard.Summary;
import com.company.docai.app.service.dashboard.UntypedDocuments;
import com.company.docai.app.service.dashboard.UpcomingDate;
import com.company.docai.app.service.discussion.DocumentDiscussionService;
import com.company.docai.app.service.document.DocumentCommentService;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.document.DocumentStatusService;
import com.company.docai.app.service.document.DocumentTypes;
import com.company.docai.app.service.document.UploadedFile;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.access.AccessLevel;
import com.company.docai.model.document.Document;
import com.company.docai.model.document.DocumentStatus;
import com.company.docai.model.folder.Folder;
import com.company.docai.model.user.User;
import com.company.docai.test_support.AuthenticatedAsAdmin;
import com.company.docai.test_support.TestAccess;
import com.company.docai.test_support.TestAccess.Grant;
import com.company.docai.test_support.TestComments;
import com.company.docai.test_support.TestDashboards;
import com.company.docai.test_support.TestFiles;
import com.company.docai.test_support.TestStatuses;
import io.jmix.aitoolsflowui.model.AiChatMessageType;
import io.jmix.aitoolsflowui.model.AiConversation;
import io.jmix.aitoolsflowui.service.AiChatMessageService;
import io.jmix.aitoolsflowui.service.AiConversationService;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.Id;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.core.usersubstitution.UserSubstitutionManager;
import io.jmix.dynattr.DynAttrQueryHints;
import io.jmix.dynattr.model.Category;
import io.jmix.securitydata.entity.UserSubstitutionEntity;
import org.assertj.core.groups.Tuple;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The blocks of the dashboard as the people of a sandbox see them: peter is an employee, helen an employee who gets
 * her own access to a closed folder, maria the colleague whose work they see, and admin has full access. The
 * database is shared with the other tests, so the assertions look for the rows of the test, and today is the day in
 * the time zone of the person who asks.
 */
@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class DashboardServiceTest {

    private static final String EMPLOYEE = "peter";
    private static final String HR_EMPLOYEE = "helen";
    private static final String OTHER = "maria";
    private static final String ADMIN = "admin";

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DocumentCommentService commentService;

    @Autowired
    private DocumentStatusService statusService;

    @Autowired
    private DocumentTypes documentTypes;

    @Autowired
    private DocumentDiscussionService discussionService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private AiConversationService conversationService;

    @Autowired
    private AiChatMessageService messageService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private UnconstrainedDataManager unconstrainedDataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private UserSubstitutionManager userSubstitutionManager;

    @TempDir
    private Path tempDir;

    private Folder sandbox;
    private final Set<UUID> ours = new HashSet<>();
    private final Map<AiConversation, String> chats = new LinkedHashMap<>();

    @BeforeEach
    void createSandbox() {
        sandbox = folder(folderService.getRoot(), "dashboard-service-test-" + UUID.randomUUID());
    }

    @AfterEach
    void cleanUp() {
        chats.forEach((conversation, owner) -> as(owner, () -> conversationService.remove(conversation)));
        TestComments.removeAll(dataManager, sandbox);

        dataManager.load(Id.of(sandbox))
                .optional()
                .ifPresent(folderService::delete);
    }

    @Test
    void aDateFromTodayOnIsUpcomingAndADateOfYesterdayIsNot() {
        LocalDate today = today();
        Document dueToday = typed(upload("due-today.txt"), "Invoice", Map.of("+dueDate", today));
        Document dueYesterday = typed(upload("due-yesterday.txt"), "Invoice", Map.of("+dueDate", today.minusDays(1)));

        List<UpcomingDate> dates = as(EMPLOYEE, dashboardService::upcomingDates);

        assertThat(dates).extracting(UpcomingDate::date)
                .allMatch(date -> !date.isBefore(today));

        assertThat(dates).extracting(UpcomingDate::document)
                .doesNotContain(dueYesterday);

        TestDashboards.assertNearestShown(dates, Map.of(dueToday, today));
    }

    @Test
    void theDatesOfTheFieldsOfDifferentTypesComeInTheirOrderAndThoseOfTheWeekStandOut() {
        LocalDate today = today();
        Document invoice = typed(upload("invoice.txt"), "Invoice", Map.of("+dueDate", today.plusDays(1)));
        Document contract = typed(upload("contract.txt"), "Contract", Map.of("+validUntil", today.plusDays(7)));
        Document later = typed(upload("later.txt"), "Invoice", Map.of("+dueDate", today.plusDays(8)));

        List<UpcomingDate> shown = as(EMPLOYEE, dashboardService::upcomingDates);

        TestDashboards.assertNearestShown(shown, Map.of(invoice, today.plusDays(1), contract, today.plusDays(7),
                later, today.plusDays(8)));

        List<Tuple> expected = List.of(tuple(invoice, true, fieldName("dueDate")),
                tuple(contract, true, fieldName("validUntil")),
                tuple(later, false, fieldName("dueDate")));

        List<UpcomingDate> dates = ourDates(shown);

        // the dates of the test that show are its nearest ones, in their order
        assertThat(dates).extracting(UpcomingDate::document, UpcomingDate::soon, UpcomingDate::field)
                .containsExactlyElementsOf(expected.subList(0, dates.size()));

        assertThat(dates).extracting(UpcomingDate::folder)
                .containsOnly(sandbox.getName());
    }

    @Test
    void atMostFiveDatesShowTheNearestFirst() {
        LocalDate today = today();
        Map<Document, LocalDate> invoices = new LinkedHashMap<>();

        for (int days = 0; days < 6; days++) {
            Document invoice = upload("invoice-" + days + ".txt");
            invoices.put(typed(invoice, "Invoice", Map.of("+dueDate", today.plusDays(days))), today.plusDays(days));
        }

        List<UpcomingDate> dates = as(EMPLOYEE, dashboardService::upcomingDates);

        assertThat(dates).hasSize(5)
                .isSortedAccordingTo(Comparator.comparing(UpcomingDate::date));

        TestDashboards.assertNearestShown(dates, invoices);
    }

    @Test
    void aDocumentOfAClosedFolderOrOfTheTrashGivesNoDate() {
        LocalDate today = today();

        Folder closed = TestAccess.giveOwnAccess(dataManager, folder(sandbox, "Closed"),
                Grant.user(HR_EMPLOYEE, AccessLevel.VIEW));

        Document hidden = typed(upload(closed, "hidden.txt"), "Invoice", Map.of("+dueDate", today));
        Document trashed = typed(upload("trashed.txt"), "Invoice", Map.of("+dueDate", today));
        documentService.delete(List.of(trashed));

        assertThat(as(EMPLOYEE, dashboardService::upcomingDates)).extracting(UpcomingDate::document)
                .doesNotContain(hidden, trashed);

        assertThat(as(HR_EMPLOYEE, dashboardService::upcomingDates)).extracting(UpcomingDate::document)
                .contains(hidden)
                .doesNotContain(trashed);
    }

    @Test
    void theDocumentsWithoutATypeAreCountedAndTheLatestFiveListed() {
        long before = as(EMPLOYEE, () -> dashboardService.untypedDocuments().count());

        Folder closed = TestAccess.giveOwnAccess(dataManager, folder(sandbox, "Closed"),
                Grant.user(HR_EMPLOYEE, AccessLevel.VIEW));

        upload(closed, "hidden.txt");
        documentService.delete(List.of(upload("trashed.txt")));
        typed(upload("typed.txt"), "Invoice", Map.of());
        List<Document> untyped = new ArrayList<>();

        for (int number = 0; number < 6; number++) {
            untyped.add(upload("untyped-" + number + ".txt"));
        }

        UntypedDocuments found = as(EMPLOYEE, dashboardService::untypedDocuments);

        assertThat(found.count()).isEqualTo(before + 6);

        assertThat(found.latest()).hasSize(5)
                .isSortedAccordingTo(Comparator.comparing(UntypedDocuments.Row::uploaded)
                        .reversed());

        assertThat(found.latest()).extracting(UntypedDocuments.Row::document)
                .isSubsetOf(untyped);

        assertThat(found.latest()).extracting(UntypedDocuments.Row::folder)
                .containsOnly(sandbox.getName());
    }

    @Test
    void fillWithAiIsForADocumentTheUserMayEditWithAFileAndAStatusThatDoesNotLockIt() {
        Document editable = upload("editable.txt");

        Document viewed = TestAccess.giveOwnAccess(dataManager, upload("viewed.txt"),
                Grant.user(EMPLOYEE, AccessLevel.VIEW));

        Document archived = upload("archived.txt");
        documentService.changeStatus(List.of(archived), TestStatuses.active(dataManager));
        documentService.changeStatus(List.of(archived), TestStatuses.archived(dataManager));

        Map<Document, Boolean> fillable = as(EMPLOYEE, dashboardService::untypedDocuments)
                .latest()
                .stream()
                .collect(Collectors.toMap(UntypedDocuments.Row::document, UntypedDocuments.Row::fillable));

        assertThat(fillable)
                .containsEntry(editable, true)
                .containsEntry(viewed, false)
                .containsEntry(archived, false);
    }

    @Test
    void theFeedShowsUploadsNewVersionsAndCommentsOfOtherPeopleAndNothingOfTheUser() {
        Document lease = as(OTHER, () -> upload("lease.txt"));
        as(OTHER, () -> documentService.uploadRevision(lease, file("lease-signed.txt")));
        as(OTHER, () -> commentService.post(lease, "Check the penalty"));
        as(EMPLOYEE, () -> upload("own.txt"));
        as(EMPLOYEE, () -> commentService.post(lease, "Agreed"));

        List<Activity> feed = as(EMPLOYEE, dashboardService::activities);

        assertThat(ourEvents(feed)).extracting(Activity::kind, Activity::number, Activity::username)
                .containsExactlyInAnyOrder(
                        tuple(Activity.Kind.UPLOAD, 1, OTHER),
                        tuple(Activity.Kind.VERSION, 2, OTHER),
                        tuple(Activity.Kind.COMMENT, 0, OTHER));

        assertThat(ourEvents(feed)).allSatisfy(activity -> {
            User author = activity.author();

            assertThat(author).isNotNull();
            assertThat(author.getUsername()).isEqualTo(OTHER);
        });

        assertThat(feed).extracting(Activity::username)
                .doesNotContain(EMPLOYEE);

        assertThat(feed).isSortedAccordingTo(Comparator.comparing(Activity::time)
                .reversed());
    }

    @Test
    void theFeedHoldsTheEightLatestEvents() {
        Document lease = as(OTHER, () -> upload("lease.txt"));

        for (int number = 1; number <= 9; number++) {
            String text = "Remark " + number;
            as(OTHER, () -> commentService.post(lease, text));
        }

        List<Activity> feed = as(EMPLOYEE, dashboardService::activities);

        assertThat(feed).hasSize(8)
                .allSatisfy(activity -> {
                    assertThat(activity.kind()).isEqualTo(Activity.Kind.COMMENT);
                    assertThat(activity.document()).isEqualTo(lease);
                });
    }

    @Test
    void theEventsOfAClosedFolderAndOfTheTrashStayOutAlsoForFullAccess() {
        Folder closed = TestAccess.giveOwnAccess(dataManager, folder(sandbox, "Closed"),
                Grant.user(HR_EMPLOYEE, AccessLevel.VIEW), Grant.user(OTHER, AccessLevel.EDIT));

        Document secret = as(OTHER, () -> upload(closed, "secret.txt"));
        as(OTHER, () -> commentService.post(secret, "For the HR department"));
        Document trashed = as(OTHER, () -> upload("trashed.txt"));
        as(OTHER, () -> commentService.post(trashed, "A wrong file"));
        documentService.delete(List.of(trashed));

        assertThat(documentsOf(as(EMPLOYEE, dashboardService::activities))).doesNotContain(secret, trashed);

        assertThat(documentsOf(as(HR_EMPLOYEE, dashboardService::activities))).contains(secret)
                .doesNotContain(trashed);
        // full access has no access rule that would hide a comment in the trash
        assertThat(documentsOf(as(ADMIN, dashboardService::activities))).contains(secret)
                .doesNotContain(trashed);
    }

    @Test
    void underASubstitutionTheEventsOfTheSubstitutedUserAreTheOwnOnes() {
        Document byMaria = as(OTHER, () -> upload("by-maria.txt"));
        Document byPeter = as(EMPLOYEE, () -> upload("by-peter.txt"));
        UserSubstitutionEntity substitution = unconstrainedDataManager.create(UserSubstitutionEntity.class);
        substitution.setUsername(EMPLOYEE);
        substitution.setSubstitutedUsername(OTHER);
        UserSubstitutionEntity saved = unconstrainedDataManager.save(substitution);

        try {
            List<Document> seen = as(EMPLOYEE, () -> {
                userSubstitutionManager.substituteUser(OTHER);
                return documentsOf(dashboardService.activities());
            });

            assertThat(seen).contains(byPeter)
                    .doesNotContain(byMaria);
        } finally {
            unconstrainedDataManager.remove(saved);
        }
    }

    @Test
    void theRecentChatsAreTheThreeOwnOnesWithAMessageLastTheLatestFirst() {
        List<UUID> withMessages = new ArrayList<>();

        for (int number = 1; number <= 4; number++) {
            withMessages.add(chat(EMPLOYEE, "Question " + number).getId());
        }

        AiConversation empty = as(EMPLOYEE, conversationService::create);
        chats.put(empty, EMPLOYEE);
        AiConversation foreign = chat(HR_EMPLOYEE, "A question of helen");

        List<RecentChat> recent = as(EMPLOYEE, dashboardService::recentChats);

        assertThat(recent).hasSize(3)
                .isSortedAccordingTo(Comparator.comparing(RecentChat::time)
                        .reversed());

        assertThat(recent).extracting(RecentChat::id)
                .isSubsetOf(withMessages)
                .doesNotContain(empty.getId(), foreign.getId());
    }

    @Test
    void aChatAboutChosenDocumentsIsADiscussion() {
        Document lease = upload("lease.txt");
        AiConversation discussion = chat(EMPLOYEE, "Sum up the lease");
        as(EMPLOYEE, () -> discussionService.saveSelection(discussion.getId(), List.of(lease)));
        AiConversation plain = chat(EMPLOYEE, "Invoices of the month");

        Map<UUID, Boolean> discussions = as(EMPLOYEE, dashboardService::recentChats)
                .stream()
                .collect(Collectors.toMap(RecentChat::id, RecentChat::discussion));

        assertThat(discussions)
                .containsEntry(discussion.getId(), true)
                .containsEntry(plain.getId(), false);
    }

    @Test
    void theSummaryCountsTheTypesWithoutATypeLastAndTheStatusesInTheirOrder() {
        String invoices = as(EMPLOYEE, () -> typeName("Invoice"));
        String contracts = as(EMPLOYEE, () -> typeName("Contract"));
        DocumentStatus draft = TestStatuses.draft(dataManager);
        DocumentStatus active = TestStatuses.active(dataManager);
        Summary before = as(EMPLOYEE, dashboardService::summary);

        typed(upload("a.txt"), "Invoice", Map.of());
        typed(upload("b.txt"), "Invoice", Map.of());
        typed(upload("c.txt"), "Contract", Map.of());
        upload("d.txt");
        documentService.changeStatus(List.of(upload("e.txt")), active);

        Folder closed = TestAccess.giveOwnAccess(dataManager, folder(sandbox, "Closed"),
                Grant.user(HR_EMPLOYEE, AccessLevel.VIEW));

        typed(upload(closed, "hidden.txt"), "Invoice", Map.of());
        documentService.delete(List.of(typed(upload("trashed.txt"), "Invoice", Map.of())));

        Summary after = as(EMPLOYEE, dashboardService::summary);

        List<String> dictionary = as(EMPLOYEE, statusService::loadAll)
                .stream()
                .map(DocumentStatus::getName)
                .toList();

        assertThat(count(after.types(), invoices) - count(before.types(), invoices)).isEqualTo(2);
        assertThat(count(after.types(), contracts) - count(before.types(), contracts)).isEqualTo(1);
        assertThat(count(after.types(), null) - count(before.types(), null)).isEqualTo(2);

        assertThat(count(after.statuses(), draft.getName()) - count(before.statuses(), draft.getName()))
                .isEqualTo(4);

        assertThat(count(after.statuses(), active.getName()) - count(before.statuses(), active.getName()))
                .isEqualTo(1);

        assertThat(after.types().getLast().name()).isNull();

        assertThat(after.types().subList(0, after.types().size() - 1))
                .isSortedAccordingTo(Comparator.comparingLong(Summary.Row::documents)
                        .reversed());

        assertThat(after.types()).allMatch(row -> row.documents() > 0);

        assertThat(dictionary).containsSubsequence(after.statuses()
                .stream()
                .map(Summary.Row::name)
                .toList());

        assertThat(after.statuses()).extracting(Summary.Row::color)
                .doesNotContainNull();
    }

    private List<UpcomingDate> ourDates(List<UpcomingDate> dates) {
        return dates.stream()
                .filter(upcoming -> ours.contains(upcoming.document().getId()))
                .toList();
    }

    private List<Activity> ourEvents(List<Activity> feed) {
        return feed.stream()
                .filter(activity -> ours.contains(activity.document().getId()))
                .toList();
    }

    private static List<Document> documentsOf(List<Activity> feed) {
        return feed.stream()
                .map(Activity::document)
                .toList();
    }

    private static long count(List<Summary.Row> rows, @Nullable String name) {
        return rows.stream()
                .filter(row -> Objects.equals(row.name(), name))
                .mapToLong(Summary.Row::documents)
                .sum();
    }

    private String fieldName(String code) {
        return as(EMPLOYEE, () -> documentTypes.fields()
                .stream()
                .filter(field -> field.getCode().equals(code))
                .map(documentTypes::nameOf)
                .findFirst()
                .orElseThrow());
    }

    private String typeName(String name) {
        return documentTypes.find(name)
                .map(documentTypes::nameOf)
                .orElseThrow();
    }

    private LocalDate today() {
        return as(EMPLOYEE, () -> dashboardService.now().toLocalDate());
    }

    // the chat of the user with a title and one question
    private AiConversation chat(String owner, String title) {
        AiConversation conversation = as(owner, () -> {
            AiConversation created = conversationService.create();
            created.setTitle(title);
            AiConversation saved = conversationService.save(created);
            messageService.createMessage(saved, AiChatMessageType.USER, title + "?");

            return saved;
        });

        chats.put(conversation, owner);

        return conversation;
    }

    private Folder folder(Folder parent, String name) {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(parent);
        folder.setName(name);

        return folderService.save(folder);
    }

    private Document upload(String fileName) {
        return upload(sandbox, fileName);
    }

    private Document upload(Folder folder, String fileName) {
        Document document = documentService.upload(folder, List.of(file(fileName)))
                .created()
                .getFirst();

        ours.add(document.getId());

        return document;
    }

    private UploadedFile file(String fileName) {
        return TestFiles.file(tempDir, fileName, "A document of the dashboard test");
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

    private <T> T as(String username, Supplier<T> work) {
        return systemAuthenticator.withUser(username, work::get);
    }

    private void as(String username, Runnable work) {
        systemAuthenticator.runWithUser(username, work);
    }
}
