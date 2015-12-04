package co.phoenixlab.discord.api.entities;

import co.phoenixlab.discord.api.ApiConst;
import co.phoenixlab.discord.api.util.ApiUtils;

import java.net.URL;
import java.util.Objects;

public class User {

    /**
     * The User's display name (can change over time)
     */
    private final String username;

    /**
     * The User's identifier (does not change, not necessarily unqiue?)
     */
    private final String id;

    /**
     * An identifier used to discriminate between two users with the same name and/or id
     */
    private final String discriminator;

    /**
     * The user's avatar ID
     */
    private final String avatar;

    /**
     * The URL where the avatar can be downloaded from
     */
    private URL avatarUrl;

    public User(String username, String id, String discriminator, String avatar) {
        this.username = username;
        this.id = id;
        this.discriminator = discriminator;
        this.avatar = avatar;
    }

    public User() {
        this(null, null, null, null);
    }

    /**
     * @return {@link #username}
     */
    public String getUsername() {
        return username;
    }

    /**
     * @return {@link #id}
     */
    public String getId() {
        return id;
    }

    /**
     * @return {@link #discriminator}
     */
    public String getDiscriminator() {
        return discriminator;
    }

    /**
     * @return {@link #avatar}
     */
    public String getAvatar() {
        return avatar;
    }

    /**
     * @return {@link #avatarUrl}
     */
    public URL getAvatarUrl() {
        if (avatarUrl == null) {
            if (avatar != null) {
                avatarUrl = ApiUtils.url(String.format(ApiConst.AVATAR_URL_PATTERN, id, avatar));
            } else {
                avatarUrl = ApiUtils.url(String.format(ApiConst.AVATAR_URL_PATTERN, id, "NO_AVATAR.JPG"));
            }
        }
        return avatarUrl;
    }

    @Override
    public String toString() {
        return String.format("User[username:\"%s\",id:\"%s\",avatar:\"%s\",avatarUrl:\"%s\"]",
                username, id, avatar, getAvatarUrl().toExternalForm());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        User user = (User) o;
        return Objects.equals(id, user.id) &&
                Objects.equals(discriminator, user.discriminator);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, discriminator);
    }
}
