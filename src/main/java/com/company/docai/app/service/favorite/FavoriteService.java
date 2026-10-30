package com.company.docai.app.service.favorite;

import com.company.docai.app.service.OperationRejectedException;
import com.company.docai.model.document.Document;
import com.company.docai.model.favorite.Favorite;
import com.company.docai.model.favorite.FavoriteItem;
import com.company.docai.model.folder.Folder;
import com.company.docai.model.user.User;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.Messages;
import io.jmix.core.MetadataTools;
import io.jmix.core.SaveContext;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.core.usersubstitution.CurrentUserSubstitution;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Keeps the favorites of the current user: marks on the folders and the documents the user comes back to often.
 * The owner of a mark is the user the person works as, the substituted one under a substitution. Every query names
 * the owner itself: the administrator is not narrowed by the row-level role and must see only own marks too.
 * A mark is shown only while its target is readable and outside the trash, so the target is read by a query of its
 * own: a reference loaded through the mark would skip both checks.
 */
@Service
public class FavoriteService {

    private final DataManager dataManager;
    private final CurrentUserSubstitution currentUserSubstitution;
    private final CurrentAuthentication currentAuthentication;
    private final MetadataTools metadataTools;
    private final Messages messages;

    public FavoriteService(DataManager dataManager, CurrentUserSubstitution currentUserSubstitution,
                           CurrentAuthentication currentAuthentication, MetadataTools metadataTools,
                           Messages messages) {
        this.dataManager = dataManager;
        this.currentUserSubstitution = currentUserSubstitution;
        this.currentAuthentication = currentAuthentication;
        this.metadataTools = metadataTools;
        this.messages = messages;
    }

    /**
     * Marks the folders and the documents as favorites; a target marked already stays as it is.
     *
     * @throws OperationRejectedException if a target is gone, in the trash or unreadable for the user
     */
    public void mark(Collection<?> targets) {
        User owner = owner();
        Set<UUID> marked = markedIds(targets);
        Map<UUID, Folder> folders = loadReadable(Folder.class, targets);
        Map<UUID, Document> documents = loadReadable(Document.class, targets);
        SaveContext saveContext = new SaveContext();

        for (Object target : targets) {
            if (marked.contains(idOf(target))) {
                continue;
            }

            Favorite favorite = dataManager.create(Favorite.class);
            favorite.setUser(owner);

            switch (target) {
                case Folder folder -> favorite.setFolder(readable(folders, folder, "folderUnavailable"));
                case Document document -> favorite.setDocument(readable(documents, document, "documentUnavailable"));
                default -> throw new IllegalArgumentException("Not a folder or a document: " + target);
            }

            saveContext.saving(favorite);
        }

        dataManager.save(saveContext);
    }

    /**
     * Removes the marks of the user on the folders and the documents, whatever the user may read now.
     */
    public void unmark(Collection<?> targets) {
        List<Favorite> favorites = ownMarks(targets);

        if (!favorites.isEmpty()) {
            dataManager.save(new SaveContext().removing(favorites));
        }
    }

    /**
     * Returns the ids of those of the folders and the documents that the user has marked.
     */
    public Set<UUID> markedIds(Collection<?> targets) {
        return ownMarks(targets).stream()
                .map(favorite -> favorite.getFolder() != null
                        ? favorite.getFolder().getId()
                        : favorite.getDocument().getId())
                .collect(Collectors.toSet());
    }

    /**
     * Returns the marked folders, then the marked documents, each in the alphabetical order of the language of the
     * user, that the user may read and that are not in the trash.
     */
    public List<FavoriteItem> loadItems() {
        Collator collator = Collator.getInstance(currentAuthentication.getLocale());
        List<FavoriteItem> items = new ArrayList<>();
        List<UUID> folderIds = markedTargetIds("folder");
        List<UUID> documentIds = markedTargetIds("document");

        if (!folderIds.isEmpty()) {
            dataManager.load(Folder.class)
                    .query("select f from Folder f where f.id in :ids")
                    .parameter("ids", folderIds)
                    .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                            .add("parent", FetchPlan.INSTANCE_NAME))
                    .list()
                    .stream()
                    .sorted(Comparator.comparing(Folder::getName, collator))
                    .forEach(folder -> items.add(item(folder.getId(), folder, null, folder.getName(),
                            folder.getParent() == null || folder.getParent().isRoot()
                                    ? null
                                    : metadataTools.getInstanceName(folder.getParent()))));
        }

        if (!documentIds.isEmpty()) {
            dataManager.load(Document.class)
                    .query("select d from Document d where d.id in :ids")
                    .parameter("ids", documentIds)
                    .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                            .add("folder", FetchPlan.INSTANCE_NAME))
                    .list()
                    .stream()
                    .sorted(Comparator.comparing(Document::getName, collator))
                    .forEach(document -> items.add(item(document.getId(), null, document, document.getName(),
                            metadataTools.getInstanceName(document.getFolder()))));
        }

        return items;
    }

    // the marks of the user on the targets; a mark has one of the two targets, so both joins are outer
    private List<Favorite> ownMarks(Collection<?> targets) {
        List<UUID> ids = targets.stream()
                .map(FavoriteService::idOf)
                .toList();

        if (ids.isEmpty()) {
            return List.of();
        }

        return dataManager.load(Favorite.class)
                .query("select f from Favorite f left join f.folder fo left join f.document d"
                        + " where f.user.username = :username and (fo.id in :ids or d.id in :ids)")
                .parameter("username", owner().getUsername())
                .parameter("ids", ids)
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("user", user -> user.add("username"))
                        .add("folder", folder -> folder.add("id"))
                        .add("document", document -> document.add("id")))
                .list();
    }

    // the ids of all the folders or all the documents the user has marked, whatever they are now
    private List<UUID> markedTargetIds(String target) {
        return dataManager.loadValues(("select f.%s.id from Favorite f where f.user.username = :username"
                        + " and f.%s is not null").formatted(target, target))
                .properties("id")
                .parameter("username", owner().getUsername())
                .list()
                .stream()
                .map(row -> row.<UUID>getValue("id"))
                .toList();
    }

    // the folders or the documents among the targets that the user may read, by their ids: the targets are read
    // again with the rights of the user, one query for each kind, since a reference to a target deleted meanwhile
    // would fail the save as well
    private <T> Map<UUID, T> loadReadable(Class<T> type, Collection<?> targets) {
        List<UUID> ids = targets.stream()
                .filter(type::isInstance)
                .map(FavoriteService::idOf)
                .toList();

        if (ids.isEmpty()) {
            return Map.of();
        }

        return dataManager.load(type)
                .query("e.id in :ids")
                .parameter("ids", ids)
                .list()
                .stream()
                .collect(Collectors.toMap(FavoriteService::idOf, Function.identity()));
    }

    private <T> T readable(Map<UUID, T> readable, T target, String refusal) {
        T found = readable.get(idOf(target));

        if (found == null) {
            throw new OperationRejectedException(messages.formatMessage(getClass(), refusal,
                    metadataTools.getInstanceName(target)));
        }

        return found;
    }

    private User owner() {
        return (User) currentUserSubstitution.getEffectiveUser();
    }

    private static UUID idOf(Object target) {
        return switch (target) {
            case Folder folder -> folder.getId();
            case Document document -> document.getId();
            default -> throw new IllegalArgumentException("Not a folder or a document: " + target);
        };
    }

    private FavoriteItem item(UUID id, @Nullable Folder folder, @Nullable Document document, String name,
                              @Nullable String location) {
        FavoriteItem item = dataManager.create(FavoriteItem.class);
        item.setId(id);
        item.setFolder(folder);
        item.setDocument(document);
        item.setName(name);
        item.setLocation(location);

        return item;
    }
}
