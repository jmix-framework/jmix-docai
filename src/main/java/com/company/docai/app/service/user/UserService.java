package com.company.docai.app.service.user;

import com.company.docai.model.user.User;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.MetadataTools;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reads users by username as the current user may read them: the names that documents, comments and the trash show.
 */
@Service
public class UserService {

    private final DataManager dataManager;
    private final MetadataTools metadataTools;

    public UserService(DataManager dataManager, MetadataTools metadataTools) {
        this.dataManager = dataManager;
        this.metadataTools = metadataTools;
    }

    /**
     * Returns the names of the users as the application shows them, by username. A null username is skipped, and
     * a user deleted since is not in the map, so the caller keeps the username.
     */
    public Map<String, String> namesOf(Collection<String> usernames) {
        return usersOf(usernames)
                .values()
                .stream()
                .collect(Collectors.toMap(User::getUsername, metadataTools::getInstanceName));
    }

    /**
     * Returns the users by username, loaded with the attributes of their instance names. A null username is skipped,
     * and a user deleted since is not in the map.
     */
    public Map<String, User> usersOf(Collection<String> usernames) {
        Set<String> wanted = usernames.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (wanted.isEmpty()) {
            return Map.of();
        }

        return dataManager.load(User.class)
                .query("select u from User u where u.username in :usernames")
                .parameter("usernames", wanted)
                .fetchPlan(FetchPlan.INSTANCE_NAME)
                .list()
                .stream()
                .collect(Collectors.toMap(User::getUsername, Function.identity()));
    }
}
