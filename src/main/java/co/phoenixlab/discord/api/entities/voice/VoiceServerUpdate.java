package co.phoenixlab.discord.api.entities.voice;

import co.phoenixlab.discord.api.DiscordApiClient;
import co.phoenixlab.discord.api.entities.Server;
import com.google.gson.annotations.SerializedName;

public class VoiceServerUpdate {

    private String endpoint;

    @SerializedName("guild_id")
    private String serverId;

    private transient Server server = DiscordApiClient.NO_SERVER;

    private String token;

    public VoiceServerUpdate(String endpoint, String serverId, String token) {
        this.endpoint = endpoint;
        this.serverId = serverId;
        this.token = token;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getServerId() {
        return serverId;
    }

    public Server getServer() {
        return server;
    }

    public String getToken() {
        return token;
    }

    public void fix(DiscordApiClient apiClient) {
        if (serverId != null) {
            server = apiClient.getServerByID(serverId);
        } else {
            serverId = "";
        }
    }

    @Override
    public String toString() {
        return "VoiceServerUpdate{" +
                "endpoint='" + endpoint + '\'' +
                ", serverId='" + serverId + '\'' +
                ", token='" + token + '\'' +
                '}';
    }
}
