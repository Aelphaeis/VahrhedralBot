package co.phoenixlab.discord.commands.tempstorage;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Future;

public class ServerTimeout {

    private final String serverId;
    private final String userId;
    private final Instant startTime;
    private final Duration duration;
    private final Instant endTime;
    private final String issuedByUserId;
    private final Future<Void> timerFuture;

    public ServerTimeout(Duration duration, Instant startTime, String userId, String serverId, String issuedByUserId,
                         Future<Void> timerFuture) {
        this.duration = duration;
        this.startTime = startTime;
        this.userId = userId;
        this.serverId = serverId;
        this.timerFuture = timerFuture;
        this.endTime = startTime.plus(duration);
        this.issuedByUserId = issuedByUserId;
    }

    public String getServerId() {
        return serverId;
    }

    public String getUserId() {
        return userId;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Duration getDuration() {
        return duration;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public String getIssuedByUserId() {
        return issuedByUserId;
    }

    public Future<Void> getTimerFuture() {
        return timerFuture;
    }
}
