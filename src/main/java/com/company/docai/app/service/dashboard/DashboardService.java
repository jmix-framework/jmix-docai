package com.company.docai.app.service.dashboard;

import com.company.docai.app.service.access.AccessChecker;
import com.company.docai.app.service.document.DocumentStatusService;
import com.company.docai.app.service.document.DocumentTypes;
import com.company.docai.app.service.user.UserService;
import com.company.docai.model.discussion.DiscussedDocument;
import com.company.docai.model.document.Document;
import com.company.docai.model.document.DocumentComment;
import com.company.docai.model.document.DocumentRevision;
import com.company.docai.model.user.User;
import io.jmix.aitoolsflowui.model.AiChatMessage;
import io.jmix.aitoolsflowui.model.AiConversation;
import io.jmix.aitoolsflowui.service.AiChatMessageService;
import io.jmix.aitoolsflowui.service.AiConversationService;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.MetadataTools;
import io.jmix.core.TimeSource;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.core.usersubstitution.CurrentUserSubstitution;
import io.jmix.dynattr.AttributeDefinition;
import io.jmix.dynattr.AttributeType;
import io.jmix.dynattr.DynAttrQueryHints;
import io.jmix.dynattr.DynAttrUtils;
import io.jmix.dynattr.model.Category;
import org.springframework.stereotype.Service;

import java.text.Collator;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Reads the blocks of the dashboard for the current user, one method for each: the nearest dates of the fields of
 * the document types, the documents without a type, what other people did lately, the own recent chats with the AI
 * assistant and the numbers of the documents by type and status. Every query runs with the rights of the user, so
 * only the documents the user may open count, and those in the trash do not. Today is the day in the time zone of
 * the user. Nothing here writes.
 */
@Service
public class DashboardService {

    private static final int UPCOMING_DATES = 5;
    private static final int UNTYPED_DOCUMENTS = 5;
    private static final int ACTIVITIES = 8;
    private static final int RECENT_CHATS = 3;

    // a date within a week from today stands out
    private static final int SOON_DAYS = 7;

    private final DataManager dataManager;
    private final DocumentTypes documentTypes;
    private final DocumentStatusService statusService;
    private final AccessChecker accessChecker;
    private final UserService userService;
    private final AiConversationService conversationService;
    private final AiChatMessageService messageService;
    private final MetadataTools metadataTools;
    private final TimeSource timeSource;
    private final CurrentAuthentication currentAuthentication;
    private final CurrentUserSubstitution currentUserSubstitution;

    public DashboardService(DataManager dataManager, DocumentTypes documentTypes, DocumentStatusService statusService,
                            AccessChecker accessChecker, UserService userService,
                            AiConversationService conversationService, AiChatMessageService messageService,
                            MetadataTools metadataTools, TimeSource timeSource,
                            CurrentAuthentication currentAuthentication,
                            CurrentUserSubstitution currentUserSubstitution) {
        this.dataManager = dataManager;
        this.documentTypes = documentTypes;
        this.statusService = statusService;
        this.accessChecker = accessChecker;
        this.userService = userService;
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.metadataTools = metadataTools;
        this.timeSource = timeSource;
        this.currentAuthentication = currentAuthentication;
        this.currentUserSubstitution = currentUserSubstitution;
    }

    /**
     * Returns the moment now in the time zone of the current user, which the days of the dashboard count in.
     */
    public ZonedDateTime now() {
        return timeSource.now().withZoneSameInstant(currentAuthentication.getTimeZone().toZoneId());
    }

    /**
     * Returns the five nearest dates from today on that the date fields of the document types hold, the nearest first
     * and, on one day, by the name of the document. A list of dates and the date of the document itself are not
     * among them.
     */
    public List<UpcomingDate> upcomingDates() {
        LocalDate today = now().toLocalDate();
        Collator collator = Collator.getInstance(currentAuthentication.getLocale());

        // a query of the documents, so that their access rule decides; its subquery reads the values of the fields,
        // which a query of values would check against the rights of an employee and refuse
        List<Document> documents = dataManager.load(Document.class)
                .query("select d from Document d where exists (select v from dynat_CategoryAttributeValue v"
                        + " where v.entity.entityId = d.id and v.parent is null and v.dateWithoutTimeValue >= :today)")
                .parameter("today", today)
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("folder", FetchPlan.INSTANCE_NAME)
                        .add("category", FetchPlan.INSTANCE_NAME))
                .hint(DynAttrQueryHints.LOAD_DYN_ATTR, true)
                .list();

        List<UpcomingDate> dates = new ArrayList<>();
        documents.forEach(document -> dates.addAll(datesOf(document, today)));

        return dates.stream()
                .sorted(Comparator.comparing(UpcomingDate::date)
                        .thenComparing(upcoming -> upcoming.document().getName(), collator))
                .limit(UPCOMING_DATES)
                .toList();
    }

    /**
     * Returns how many documents have no type and the five of them uploaded last, the latest first.
     */
    public UntypedDocuments untypedDocuments() {
        long count = dataManager.loadValue("select count(d) from Document d where d.category is null", Long.class)
                .one();

        List<Document> latest = dataManager.load(Document.class)
                .query("select d from Document d where d.category is null order by d.createdDate desc, d.id desc")
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("folder", FetchPlan.INSTANCE_NAME)
                        .add("status", FetchPlan.BASE)
                        .add("currentRevision", FetchPlan.INSTANCE_NAME))
                .maxResults(UNTYPED_DOCUMENTS)
                .list();

        return new UntypedDocuments(count, latest.stream()
                .map(document -> new UntypedDocuments.Row(document, folderOf(document), document.getCreatedDate(),
                        isFillable(document)))
                .toList());
    }

    /**
     * Returns the eight latest events of other people on the documents the user may open: uploads, new versions and
     * comments, the latest first. The events of the user are left out; under a substitution, those of the user the
     * person works as.
     */
    public List<Activity> activities() {
        String own = currentUserSubstitution.getEffectiveUser().getUsername();

        // the versions of a document in the trash lie there with it
        List<DocumentRevision> revisions = dataManager.load(DocumentRevision.class)
                .query("select r from DocumentRevision r where r.createdBy <> :own"
                        + " order by r.createdDate desc, r.id desc")
                .parameter("own", own)
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("document", FetchPlan.INSTANCE_NAME))
                .maxResults(ACTIVITIES)
                .list();

        // a comment stays with its document in the trash, and full access has no access rule that would hide it
        List<DocumentComment> comments = dataManager.load(DocumentComment.class)
                .query("select c from DocumentComment c where c.createdBy <> :own and c.document.deletedDate is null"
                        + " order by c.createdDate desc, c.id desc")
                .parameter("own", own)
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("document", FetchPlan.INSTANCE_NAME))
                .maxResults(ACTIVITIES)
                .list();

        Set<String> usernames = new HashSet<>();
        revisions.forEach(revision -> usernames.add(revision.getCreatedBy()));
        comments.forEach(comment -> usernames.add(comment.getCreatedBy()));
        Map<String, User> authors = userService.usersOf(usernames);

        List<Activity> activities = new ArrayList<>();

        for (DocumentRevision revision : revisions) {
            activities.add(new Activity(revision.getNumber() == 1 ? Activity.Kind.UPLOAD : Activity.Kind.VERSION,
                    revision.getNumber(), revision.getDocument(), revision.getCreatedBy(),
                    authors.get(revision.getCreatedBy()), revision.getCreatedDate()));
        }

        for (DocumentComment comment : comments) {
            activities.add(new Activity(Activity.Kind.COMMENT, 0, comment.getDocument(), comment.getCreatedBy(),
                    authors.get(comment.getCreatedBy()), comment.getCreatedDate()));
        }

        return activities.stream()
                .sorted(Comparator.comparing(Activity::time)
                        .reversed())
                .limit(ACTIVITIES)
                .toList();
    }

    /**
     * Returns the three own chats with the AI assistant that had a message last, the latest first. A chat without
     * messages, which a question given up on the way to its chat leaves, is not among them.
     */
    public List<RecentChat> recentChats() {
        Map<AiConversation, AiChatMessage> latest = messageService.loadLatestMessages(
                conversationService.loadConversations(), null);

        Comparator<AiChatMessage> latestFirst = Comparator.comparing(AiChatMessage::getCreatedDate,
                Comparator.nullsLast(Comparator.reverseOrder()));

        // a conversation without messages has no latest one, so it is not among them
        return latest.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByValue(latestFirst))
                .limit(RECENT_CHATS)
                .map(entry -> new RecentChat(entry.getKey().getId(), entry.getKey().getTitle(),
                        entry.getValue().getCreatedDate(), isDiscussion(entry.getKey().getId())))
                .toList();
    }

    /**
     * Returns how many documents have each type, the most first and the documents without a type last, and each
     * status, in the order of the dictionary. A type or a status without documents has no row.
     */
    public Summary summary() {
        // the documents without a type form the group without an id, which an inner join would lose
        Map<UUID, Long> byType = counts("select c.id, count(d) from Document d left join d.category c group by c.id");
        Map<UUID, Long> byStatus = counts("select d.status.id, count(d) from Document d group by d.status.id");
        Collator collator = Collator.getInstance(currentAuthentication.getLocale());

        List<Summary.Row> types = new ArrayList<>(documentTypes.types()
                .stream()
                .filter(type -> type.getSource() instanceof Category)
                .map(type -> new Summary.Row(documentTypes.nameOf(type), null,
                        byType.getOrDefault(((Category) type.getSource()).getId(), 0L)))
                .filter(row -> row.documents() > 0)
                .sorted(Comparator.comparingLong(Summary.Row::documents)
                        .reversed()
                        .thenComparing(Summary.Row::name, collator))
                .toList());

        long untyped = byType.getOrDefault(null, 0L);

        if (untyped > 0) {
            types.add(new Summary.Row(null, null, untyped));
        }

        List<Summary.Row> statuses = statusService.loadAll()
                .stream()
                .map(status -> new Summary.Row(status.getName(), status.getColor(),
                        byStatus.getOrDefault(status.getId(), 0L)))
                .filter(row -> row.documents() > 0)
                .toList();

        return new Summary(types, statuses);
    }

    // the date fields of the own type of the document: the values of another type stay until its next save
    private List<UpcomingDate> datesOf(Document document, LocalDate today) {
        List<UpcomingDate> dates = new ArrayList<>();

        List<AttributeDefinition> fields = documentTypes.types()
                .stream()
                .filter(type -> Objects.equals(type.getSource(), document.getCategory()))
                .flatMap(type -> type.getAttributeDefinitions().stream())
                .filter(field -> field.getDataType() == AttributeType.DATE_WITHOUT_TIME && !field.isCollection())
                .toList();

        for (AttributeDefinition field : fields) {
            Object value = EntityValues.getValue(document, DynAttrUtils.getPropertyFromAttributeCode(field.getCode()));

            if (value instanceof LocalDate date && !date.isBefore(today)) {
                dates.add(new UpcomingDate(document, folderOf(document), documentTypes.nameOf(field), date,
                        !date.isAfter(today.plusDays(SOON_DAYS))));
            }
        }

        return dates;
    }

    // what the card checks before it offers Fill with AI; whether the AI is on is for the views to decide
    private boolean isFillable(Document document) {
        return !Boolean.TRUE.equals(document.getStatus().getLocked())
                && document.getCurrentRevision() != null
                && accessChecker.canEdit(document);
    }

    // the selection of a discussion has rows of its own, which load for the owner of the conversation only
    private boolean isDiscussion(UUID conversationId) {
        return !dataManager.load(DiscussedDocument.class)
                .query("e.conversation.id = :conversationId")
                .parameter("conversationId", conversationId)
                .maxResults(1)
                .list()
                .isEmpty();
    }

    // the numbers of the documents by the id a grouped query gives them
    private Map<UUID, Long> counts(String query) {
        Map<UUID, Long> counts = new HashMap<>();

        dataManager.loadValues(query)
                .properties("id", "documents")
                .list()
                .forEach(row -> counts.put(row.getValue("id"), row.getValue("documents")));

        return counts;
    }

    // the folder is named as in the favorites, also when it is closed to the user
    private String folderOf(Document document) {
        return metadataTools.getInstanceName(document.getFolder());
    }
}
