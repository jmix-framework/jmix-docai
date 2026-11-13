package com.company.docai.view.user;

import com.company.docai.model.user.User;
import com.google.common.base.Strings;
import com.vaadin.flow.component.avatar.Avatar;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * How the application shows a person: the short name of the first and the last name, and the avatar with the
 * initials of the person in a color of their own. A person deleted since keeps the username.
 */
@Component
public class UserRenderers {

    // the number of the user colors of an avatar
    private static final int AVATAR_COLORS = 7;

    /**
     * Returns the first and the last name of the person, or the username when the person has neither or is gone.
     */
    public String shortName(@Nullable User user, String username) {
        if (user == null) {
            return username;
        }

        String name = Strings.nullToEmpty(user.getFirstName()) + " " + Strings.nullToEmpty(user.getLastName());

        return name.isBlank() ? username : name.strip();
    }

    /**
     * Shows the person in the avatar: the name the caller gives, the initials of the first and the last name, or the
     * first letter of the username when the person lacks either of them or is gone, and the color the username
     * picks among the user colors, the same wherever the person shows.
     */
    public void fillAvatar(Avatar avatar, String name, @Nullable User user, String username) {
        // the avatar would take the initials of every word of the name, a username in brackets too
        String initials = user != null && !Strings.isNullOrEmpty(user.getFirstName())
                && !Strings.isNullOrEmpty(user.getLastName())
                ? "%c%c".formatted(user.getFirstName().charAt(0), user.getLastName().charAt(0))
                : username.substring(0, 1).toUpperCase(Locale.ROOT);

        avatar.setName(name);
        avatar.setAbbreviation(initials);
        avatar.setColorIndex(Math.floorMod(username.hashCode(), AVATAR_COLORS));
    }
}
