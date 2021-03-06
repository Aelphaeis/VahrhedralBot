package co.phoenixlab.discord.commands;

import co.phoenixlab.common.lang.SafeNav;
import co.phoenixlab.common.localization.Localizer;
import co.phoenixlab.discord.CommandDispatcher;
import co.phoenixlab.discord.EventListener;
import co.phoenixlab.discord.MessageContext;
import co.phoenixlab.discord.VahrhedralBot;
import co.phoenixlab.discord.api.ApiConst;
import co.phoenixlab.discord.api.DiscordApiClient;
import co.phoenixlab.discord.api.entities.*;
import co.phoenixlab.discord.api.event.MemberChangeEvent;
import co.phoenixlab.discord.api.event.MemberChangeEvent.MemberChange;
import co.phoenixlab.discord.commands.tempstorage.ServerTimeout;
import co.phoenixlab.discord.commands.tempstorage.ServerTimeoutStorage;
import co.phoenixlab.discord.commands.tempstorage.TempServerConfig;
import co.phoenixlab.discord.util.WeakEventSubscriber;
import co.phoenixlab.discord.util.adapters.DurationGsonTypeAdapter;
import co.phoenixlab.discord.util.adapters.InstantGsonTypeAdapter;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import org.apache.http.HttpHeaders;
import org.apache.http.entity.ContentType;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static co.phoenixlab.discord.VahrhedralBot.LOGGER;
import static co.phoenixlab.discord.api.DiscordApiClient.*;
import static co.phoenixlab.discord.commands.CommandUtil.findUser;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;

public class ModCommands {

    public static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("MMM dd uuuu HH:mm:ss z");
    private final CommandDispatcher dispatcher;
    private Localizer loc;
    private final VahrhedralBot bot;
    private final DiscordApiClient apiClient;

    private final Consumer<MemberChangeEvent> memberJoinListener;

    private final Map<String, TempServerConfig> serverStorage;

    private static final Path serverStorageDir = Paths.get("config/tempServerStorage/");

    private final Gson gson;

    private final ScheduledExecutorService timeoutService;

    enum JoinLeave {
        JOIN,
        LEAVE,
        BOTH
    }

    public ModCommands(VahrhedralBot bot) {
        this.bot = bot;
        dispatcher = new CommandDispatcher(bot, "");
        loc = bot.getLocalizer();
        apiClient = bot.getApiClient();
        memberJoinListener = this::onMemberJoinedServer;
        serverStorage = new HashMap<>();
        gson = new GsonBuilder().
                registerTypeAdapter(Instant.class, new InstantGsonTypeAdapter()).
                registerTypeAdapter(Duration.class, new DurationGsonTypeAdapter()).
                create();
        timeoutService = Executors.newScheduledThreadPool(10);
    }

    public CommandDispatcher getModCommandDispatcher() {
        return dispatcher;
    }

    public void registerModCommands() {
        CommandDispatcher d = dispatcher;
        if (!bot.getConfig().isSelfBot()) {
            d.registerAlwaysActiveCommand("commands.mod.timeout", this::timeout);
            d.registerAlwaysActiveCommand("commands.mod.stoptimeout", this::stopTimeout);
            d.registerAlwaysActiveCommand("commands.mod.settimeoutrole", this::setTimeoutRole);
            d.registerAlwaysActiveCommand("commands.mod.vanish", this::vanish);
            d.registerAlwaysActiveCommand("commands.mod.ban", this::ban);
            d.registerAlwaysActiveCommand("commands.mod.jl", this::joinLeave);
            d.registerAlwaysActiveCommand("commands.mod.welcome", this::setWelcome);
            d.registerAlwaysActiveCommand("commands.mod.farewell", this::setFarewell);
            d.registerAlwaysActiveCommand("commands.mod.dntrack", this::setDnTrackChannel);
        }
        d.registerAlwaysActiveCommand("commands.admin.find", this::find);
        d.registerAlwaysActiveCommand("commands.mod.setnick", this::setNick);

        EventBus eventBus = bot.getApiClient().getEventBus();
        eventBus.register(new WeakEventSubscriber<>(memberJoinListener, eventBus, MemberChangeEvent.class));
    }

    private void setNick(MessageContext context, String args) {
        if (context.getServer() == null || context.getServer() == NO_SERVER) {
            return;
        }
        String serverId = context.getServer().getId();
        Channel channel = context.getChannel();
        String[] split = args.split(" ", 2);
        String newNickname;
        if (split.length == 1) {
            //  Clearing nickname, set to empty string
            newNickname = "";
        } else {
            newNickname = split[1];
        }
        User[] mentions = context.getMessage().getMentions();
        if (mentions.length == 0) {
            apiClient.sendMessage(loc.localize("commands.mod.setnick.response.blank"), channel);
        }
        User target = mentions[0];
        Map<String, String> headers = new HashMap<>();
        headers.put(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.getMimeType());
        headers.put(HttpHeaders.AUTHORIZATION, apiClient.getToken());
        try {
            HttpResponse<JsonNode> response = Unirest.patch("https://discordapp.com/api/guilds/" +
                serverId + "/members/" + target.getId()).
                headers(headers).
                body("{\"nick\":\"" + newNickname + "\"}").
                asJson();
            int status = response.getStatus();
            if (status != 204) {
                if (status == 403) {
                    apiClient.sendMessage(loc.localize("commands.mod.setnick.response.failed.no_perms"),
                        channel);
                } else if (status == 404) {
                    apiClient.sendMessage(loc.localize("commands.mod.setnick.response.failed.not_found"),
                        channel);
                } else {
                    apiClient.sendMessage(loc.localize("commands.mod.setnick.response.unknown_error",
                        status,
                        response.getStatusText(),
                        response.getBody().toString()),
                        channel);
                }
            } else {
                if (newNickname.isEmpty()) {
                    apiClient.sendMessage(loc.localize("commands.mod.setnick.response.removed", target.getUsername()),
                        channel);
                } else {
                    apiClient.sendMessage(loc.localize("commands.mod.setnick.response.changed",
                        target.getUsername(),
                        newNickname),
                        channel);
                }
            }
        } catch (UnirestException e) {
            LOGGER.warn("Exception while setting nickname for {} {} in {} {}",
                target.getUsername(), target.getId(), context.getServer().getName(), serverId);
            LOGGER.warn("Nickname set exception", e);
        }

    }

    private void setDnTrackChannel(MessageContext context, String args) {
        if (context.getServer() == null || context.getServer() == NO_SERVER) {
            return;
        }
        String serverId = context.getServer().getId();
        TempServerConfig config = serverStorage.get(serverId);
        if (config == null) {
            config = new TempServerConfig(serverId);
            serverStorage.put(serverId, config);
        }
        Channel channel = context.getChannel();
        if (args.equalsIgnoreCase("off")) {
            config.setDnTrackChannel(null);
            apiClient.sendMessage(loc.localize("commands.mod.dntrack.response.none"), channel);
        } else {
            config.setDnTrackChannel(channel.getId());
            apiClient.sendMessage(loc.localize("commands.mod.dntrack.response.set"),
                channel);
        }
        saveServerConfig(config);

    }

    private void setWelcome(MessageContext context, String args) {
        if (context.getServer() == null || context.getServer() == NO_SERVER) {
            return;
        }
        String serverId = context.getServer().getId();
        TempServerConfig config = serverStorage.get(serverId);
        if (config == null) {
            config = new TempServerConfig(serverId);
            serverStorage.put(serverId, config);
        }
        Channel channel = context.getChannel();
        if (args.isEmpty() || args.equalsIgnoreCase("none")) {
            config.setCustomWelcomeMessage("");
            apiClient.sendMessage(loc.localize("commands.mod.welcome.response.none"), channel);
        } else if (args.equalsIgnoreCase("default")) {
            config.setCustomWelcomeMessage(null);
            apiClient.sendMessage(loc.localize("commands.mod.welcome.response.default"), channel);
        } else {
            config.setCustomWelcomeMessage(args);
            apiClient.sendMessage(loc.localize("commands.mod.welcome.response.set",
                    EventListener.createJoinLeaveMessage(context.getAuthor(), context.getServer(), args)),
                    channel);
        }
        saveServerConfig(config);
    }

    private void setFarewell(MessageContext context, String args) {
        if (context.getServer() == null || context.getServer() == NO_SERVER) {
            return;
        }
        String serverId = context.getServer().getId();
        TempServerConfig config = serverStorage.get(serverId);
        if (config == null) {
            config = new TempServerConfig(serverId);
            serverStorage.put(serverId, config);
        }
        Channel channel = context.getChannel();
        if (args.isEmpty() || args.equalsIgnoreCase("none")) {
            config.setCustomLeaveMessage("");
            apiClient.sendMessage(loc.localize("commands.mod.farewell.response.none"), channel);
        } else if (args.equalsIgnoreCase("default")) {
            config.setCustomLeaveMessage(null);
            apiClient.sendMessage(loc.localize("commands.mod.farewell.response.default"), channel);
        } else {
            config.setCustomLeaveMessage(args);
            apiClient.sendMessage(loc.localize("commands.mod.farewell.response.set",
                EventListener.createJoinLeaveMessage(context.getAuthor(), context.getServer(), args)),
                channel);
        }
        saveServerConfig(config);
    }

    private void joinLeave(MessageContext context, String args) {
        if (args.isEmpty()) {
            return;
        }
        args = args.toLowerCase();
        String[] split = args.split(" ");
        String cid = split[0];
        JoinLeave target = JoinLeave.BOTH;
        if (split.length != 1) {
            String jlStr = split[1].toLowerCase();
            for (JoinLeave joinLeave : JoinLeave.values()) {
                if (joinLeave.name().toLowerCase().startsWith(jlStr)) {
                    target = joinLeave;
                    break;
                }
            }
        }
        if ("default".equals(cid)) {
            cid = context.getServer().getId();
        } else if ("this".equals(cid)) {
            cid = context.getChannel().getId();
        }
        Channel channel = apiClient.getChannelById(cid);
        if (channel != NO_CHANNEL) {
            if (target == JoinLeave.JOIN) {
                bot.getEventListener().joinMessageRedirect.put(context.getServer().getId(), cid);
            }
            if (target == JoinLeave.LEAVE) {
                bot.getEventListener().leaveMessageRedirect.put(context.getServer().getId(), cid);
            }
            if (target == JoinLeave.BOTH) {
                bot.getEventListener().joinMessageRedirect.put(context.getServer().getId(), cid);
                bot.getEventListener().leaveMessageRedirect.put(context.getServer().getId(), cid);
            }
            apiClient.sendMessage(loc.localize("commands.mod.jl.response", channel.getName(), channel.getId()),
                    context.getChannel());
        } else {
            apiClient.sendMessage(loc.localize("commands.mod.jl.response.invalid"), context.getChannel());
        }
    }

    private void ban(MessageContext context, String args) {
        if (args.isEmpty()) {
            return;
        }
        String[] split = args.split(" ");
        List<String> banned = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        Channel channel = context.getChannel();
        for (String userStr : split) {
            User user = findUser(context, userStr);
            String userId = user.getId();
            if (user == NO_USER) {
                userId = userStr;
            }

            if (banChecked(channel, context.getAuthor(), user, context.getServer())) {
                banned.add(userId + " " + user.getUsername());
            } else {
                failed.add(userId + " " + user.getUsername());
            }
        }

        if (channel.getId() != null) {
            StringJoiner joiner = new StringJoiner("\n");
            for (String s : banned) {
                String[] pair = s.split(" ", 2);
                joiner.add(loc.localize("commands.mod.ban.response", pair[1], pair[0]));
            }
            apiClient.sendMessage(joiner.toString(), channel);
        }


    }

    public boolean banChecked(Channel channel, User author, User user, Server server) {
        String userId = user.getId();
        if (userId.equals(author.getId())) {
            apiClient.sendMessage("You cannot ban yourself", channel);
            return false;
        }
        if (userId.equals(apiClient.getClientUser().getId())) {
            apiClient.sendMessage("You cannot ban the bot", channel);
            return false;
        }
        if (bot.getCommands().checkPermission(Permission.GEN_MANAGE_ROLES,
            apiClient.getUserMember(userId, server),
            server,
            apiClient)) {
            apiClient.sendMessage("You cannot ban an admin", channel);
            return false;
        }
        return banImpl(userId, server.getId());
    }

    public boolean banImpl(String userId, String serverId) {
        Map<String, String> headers = new HashMap<>();
        headers.put(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.getMimeType());
        headers.put(HttpHeaders.AUTHORIZATION, apiClient.getToken());
        try {
            HttpResponse<JsonNode> response = Unirest.put("https://discordapp.com/api/guilds/" +
                    serverId + "/bans/" + userId + "?delete-message-days=1").
                    headers(headers).
                    asJson();
            //  Ignore
            return true;
        } catch (Exception e) {
            LOGGER.warn("Exception when trying to ban " + userId, e);
            return false;
        }
    }

    private void vanish(MessageContext context, String args) {
        Message message = context.getMessage();
        if (args.isEmpty()) {
            return;
        }

        try {
            final int cap = 100;
            final int capPages = 10;
            String[] vals = args.split(" ");
            String userId;
            int numMsgs;
            if (vals.length == 2) {
                User user = findUser(context, vals[0]);
                userId = user.getId();
                if (user == NO_USER) {
                    userId = vals[0];
                }
                numMsgs = Math.max(1, Math.min(cap, Integer.parseInt(vals[1])));
            } else if (vals.length == 1) {
                userId = "";
                numMsgs = Math.max(1, Math.min(cap, Integer.parseInt(vals[0])));
            } else {
                userId = "";
                numMsgs = 10;
            }

            int limit = numMsgs;
            String before = message.getId();
            //  Limit search to 10 pages (500 msgs)
            //  Instead of deleting them when we find them, we build a list instead
            List<String> messagesToDelete = new ArrayList<>(limit);
            for (int k = 0; k < capPages && limit > 0; k++) {
                Map<String, String> headers = new HashMap<>();
                headers.put(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.getMimeType());
                headers.put(HttpHeaders.AUTHORIZATION, apiClient.getToken());
                Map<String, Object> queryParams = new HashMap<>();
                queryParams.put("limit", 50);
                queryParams.put("before", before);
                HttpResponse<JsonNode> response = Unirest.get(ApiConst.CHANNELS_ENDPOINT +
                        message.getChannelId() + "/messages").
                        headers(headers).
                        queryString(queryParams).
                        asJson();
                JSONArray ret = response.getBody().getArray();
                if (userId.isEmpty()) {
                    for (int i = 0; i < ret.length() && limit > 0; i++) {
                        JSONObject msg = ret.getJSONObject(i);
                        String mid = msg.getString("id");
                        before = mid;
                        messagesToDelete.add(mid);
                        limit--;
                    }
                } else {
                    for (int i = 0; i < ret.length() && limit > 0; i++) {
                        JSONObject msg = ret.getJSONObject(i);
                        String mid = msg.getString("id");
                        JSONObject msgUsr = msg.getJSONObject("author");
                        String uid = msgUsr.getString("id");
                        before = mid;
                        if (userId.equals(uid)) {
                            messagesToDelete.add(mid);
                            limit--;
                        }
                    }
                }
            }
            LOGGER.info("Deleting {} messages", messagesToDelete.size());
            //  Using bulk delete endpoint
            apiClient.bulkDeleteMessages(context.getChannel().getId(),
                    messagesToDelete.toArray(new String[messagesToDelete.size()]));
        } catch (Exception e) {
            LOGGER.warn("Unable to get messages", e);
        }
    }

    private void timeout(MessageContext context, String args) {
        Channel channel = context.getChannel();
        if (!args.isEmpty()) {
            String[] split = args.split(" ", 2);
            String uid = split[0];
            if (uid.length() > 4) {
                if (uid.startsWith("<@!")) {
                    uid = uid.substring(3, uid.length() - 1);
                } else if (uid.startsWith("<@")) {
                    uid = uid.substring(2, uid.length() - 1);
                }
                if (!uid.matches("[0-9]+")) {
                    apiClient.sendMessage(loc.localize("commands.mod.stoptimeout.response.not_id"), channel);
                    return;
                }
                Server server = context.getServer();
                String serverId = server.getId();
                User user = apiClient.getUserById(uid, server);
                if (user == NO_USER) {
                    user = new User("UNKNOWN", uid, "", null);
                }
                final User theUser = user;
                if (split.length == 2) {
                    if (bot.getConfig().isAdmin(user.getId())) {
                        apiClient.sendMessage("```API error: Server returned HTTP: 403 Forbidden. Check bot " +
                                "permissions```", channel);
                        return;
                    }

                    Duration duration = parseDuration(split[1]);
                    if (applyTimeout(context.getAuthor(), channel, server, user, duration)) {
                        return;
                    }
                } else if (split.length == 1) {
                    if (isUserTimedOut(user, server)) {
                        ServerTimeout timeout = SafeNav.of(serverStorage.get(serverId)).
                                next(TempServerConfig::getServerTimeouts).
                                next(ServerTimeoutStorage::getTimeouts).
                                next(m -> m.get(theUser.getId())).get();
                        //  Timeout cannot be null since we just checked
                        User timeoutIssuer = apiClient.getUserById(timeout.getIssuedByUserId(), server);
                        apiClient.sendMessage(loc.localize("commands.mod.timeout.response.check",
                                user.getUsername(), user.getId(),
                                formatDuration(Duration.between(Instant.now(), timeout.getEndTime())),
                                formatInstant(timeout.getEndTime()),
                                timeoutIssuer.getUsername(), timeout.getIssuedByUserId()),
                                channel);
                    } else {
                        apiClient.sendMessage(loc.localize("commands.mod.timeout.response.check.not_found",
                                user.getUsername(), user.getId()),
                                channel);
                    }
                    return;
                } else {
                    LOGGER.warn("Split length not 1 or 2, was {}: '{}'", split.length, args);
                }
            } else {
                LOGGER.warn("UID/mention not long enough: '{}'", args);
            }
        } else {
            LOGGER.warn("Args was empty");
        }
        apiClient.sendMessage(loc.localize("commands.mod.timeout.response.invalid"),
                channel);
    }

    public boolean applyTimeout(User issuingUser, Channel noticeChannel, Server server, User user, Duration duration) {
        String serverId = server.getId();
        if (duration != null && !duration.isNegative() && !duration.isZero()) {
            ServerTimeout timeout = new ServerTimeout(duration,
                    Instant.now(), user.getId(), serverId,
                    user.getUsername(), issuingUser.getId());
            TempServerConfig serverConfig = serverStorage.get(serverId);
            if (serverConfig == null) {
                serverConfig = new TempServerConfig(serverId);
                serverStorage.put(serverId, serverConfig);
            }
            ServerTimeoutStorage storage = serverConfig.getServerTimeouts();
            if (storage == null) {
                storage = new ServerTimeoutStorage();
                serverConfig.setServerTimeouts(storage);
            }
            if (applyTimeoutRole(user, server, noticeChannel)) {
                storage.getTimeouts().put(user.getId(), timeout);
                ScheduledFuture future = timeoutService.schedule(() ->
                        onTimeoutExpire(user, server), duration.getSeconds(), TimeUnit.SECONDS);
                timeout.setTimerFuture(future);
                saveServerConfig(serverConfig);
                String durationStr = formatDuration(duration);
                String instantStr = formatInstant(timeout.getEndTime());
                String msg = loc.localize("commands.mod.timeout.response",
                        user.getUsername(), user.getId(),
                        durationStr,
                        instantStr);
                apiClient.sendMessage(msg, noticeChannel);
                LOGGER.info("[{}] '{}': Timing out {} ({}) for {} (until {}), issued by {} ({})",
                        serverId, server.getName(),
                        user.getUsername(), user.getId(),
                        durationStr, instantStr,
                        issuingUser.getUsername(), issuingUser.getId());

            }
            //  No else with error - applyTimeoutRole does that for us
            return true;
        } else {
            LOGGER.warn("Invalid duration format");
        }
        return false;
    }

    private Duration parseDuration(String s) {
        //  Remove spaces
        s = s.replaceAll("\\s", "").toLowerCase();
        long seconds = 0;
        StringBuilder timeBuilder = new StringBuilder();
        char[] chars = s.toCharArray();
        for (char c : chars) {
            if (c >= '0' && c <= '9') {
                timeBuilder.append(c);
            } else {
                int multi;
                switch (c) {
                    case 'd':
                        multi = 86400;
                        break;
                    case 'h':
                        multi = 3600;
                        break;
                    case 'm':
                        multi = 60;
                        break;
                    case 's':
                        multi = 1;
                        break;
                    default:
                        multi = 0;
                        break;
                }
                seconds += Long.parseLong(timeBuilder.toString()) * multi;
                timeBuilder.setLength(0);
            }
        }
        //  Remaining time defaults to seconds
        if (timeBuilder.length() != 0) {
            seconds += Long.parseLong(timeBuilder.toString());
        }
        return Duration.ofSeconds(seconds);
    }

    private void stopTimeout(MessageContext context, String args) {
        if (args.isEmpty()) {
            apiClient.sendMessage(loc.localize("commands.mod.stoptimeout.response.invalid"),
                    context.getChannel());
            return;
        }
        String uid = args;
        if (uid.length() > 4) {
            if (uid.startsWith("<@")) {
                uid = uid.substring(2, uid.length() - 1);
            }
            Server server = context.getServer();
            User user = apiClient.getUserById(uid, server);
            if (user == NO_USER) {
                user = new User("UNKNOWN", uid, "", null);
            }
            LOGGER.info("{} ({}) is attempting to cancel timeout for {} ({}) in {} ({})",
                    context.getAuthor().getUsername(), context.getAuthor().getId(),
                    user.getUsername(), user.getId(),
                    server.getName(), server.getId());
            cancelTimeout(user, server, context.getChannel());
        } else {
            apiClient.sendMessage(loc.localize("commands.mod.stoptimeout.response.invalid"),
                    context.getChannel());
        }
    }

    private void setTimeoutRole(MessageContext context, String args) {
        DiscordApiClient apiClient = context.getApiClient();
        if (args.isEmpty()) {
            apiClient.sendMessage(loc.localize("commands.mod.settimeoutrole.response.missing"),
                    context.getChannel());
            return;
        }
        Role role = apiClient.getRole(args, context.getServer());
        if (role == NO_ROLE) {
            apiClient.sendMessage(loc.localize("commands.mod.settimeoutrole.response.not_found",
                    args),
                    context.getChannel());
            return;
        }
        String serverId = context.getServer().getId();
        TempServerConfig serverConfig = serverStorage.get(serverId);
        if (serverConfig == null) {
            serverConfig = new TempServerConfig(serverId);
            serverStorage.put(serverId, serverConfig);
        }
        ServerTimeoutStorage storage = serverConfig.getServerTimeouts();
        if (storage == null) {
            storage = new ServerTimeoutStorage();
            serverConfig.setServerTimeouts(storage);
        }
        storage.setTimeoutRoleId(role.getId());
        apiClient.sendMessage(loc.localize("commands.mod.settimeoutrole.response",
                role.getName(), role.getId()),
                context.getChannel());
        saveServerConfig(serverConfig);
    }

    private void find(MessageContext context, String args) {
        DiscordApiClient apiClient = context.getApiClient();
        Channel channel = context.getChannel();
        Pattern pattern;
        try {
            pattern = Pattern.compile(args);
        } catch (PatternSyntaxException pse) {
            apiClient.sendMessage(loc.localize("commands.admin.find.response.invalid"),
                    channel);
            return;
        }
        Predicate<String> matcher = pattern.asPredicate();
        List<User> results = context.getServer().getMembers().stream().
                map(Member::getUser).
                filter(u -> matcher.test(u.getUsername())).
                collect(Collectors.toList());
        int size = results.size();
        if (size > 20) {
            apiClient.sendMessage(loc.localize("commands.admin.find.response.oversize",
                    size),
                    channel);
            return;
        }
        StringJoiner resultJoiner = new StringJoiner(", ");
        results.stream().
                map(this::userToResult).
                forEach(resultJoiner::add);
        apiClient.sendMessage(loc.localize("commands.admin.find.response.format",
                size, resultJoiner.toString()),
                channel);
    }

    private String userToResult(User user) {
        return loc.localize("commands.admin.find.response.entry",
                user.getUsername(), user.getId(), user.getDiscriminator());
    }

    @Subscribe
    public void onMemberJoinedServer(MemberChangeEvent memberChangeEvent) {
        if (memberChangeEvent.getMemberChange() == MemberChange.ADDED) {
            User user = memberChangeEvent.getMember().getUser();
            Server server = memberChangeEvent.getServer();
            if (isUserTimedOut(user, server)) {
                refreshTimeoutOnEvade(user, server);
            } else if (doesTimeoutEntryExistForUser(user, server)) {
                onTimeoutExpire(user, server);
            }
        }
    }

    private void refreshTimeoutOnEvade(User user, Server server) {
        ServerTimeout timeout = SafeNav.of(serverStorage.get(server.getId())).
                next(TempServerConfig::getServerTimeouts).
                next(ServerTimeoutStorage::getTimeouts).
                next(timeouts -> timeouts.get(user.getId())).
                get();
        if (timeout == null) {
            LOGGER.warn("Attempted to refresh a timeout on a user who was not timed out! {} ({})",
                    user.getUsername(), user.getId());
            return;
        }
        LOGGER.info("User {} ({}) attempted to evade a timeout on {} ({})!",
                user.getUsername(), user.getId(),
                server.getName(), server.getId());
        Channel channel = apiClient.getChannelById(server.getId(), server);
        apiClient.sendMessage(loc.localize("listener.mod.timeout.on_evasion",
                user.getId(), formatDuration(Duration.between(Instant.now(), timeout.getEndTime())),
                formatInstant(timeout.getEndTime())),
                channel);
        applyTimeoutRole(user, server, channel);
    }

    /**
     * Applies the timeout role to the given user. This does NOT create or manage any storage/persistence, it only
     * sets the user's roles
     *
     * @param user              The user to add to the timeout role
     * @param server            The server on which to add the user to the timeout role
     * @param invocationChannel The channel to send messages on error
     */
    public boolean applyTimeoutRole(User user, Server server, Channel invocationChannel) {
        String serverId = server.getId();
        TempServerConfig serverConfig = serverStorage.get(serverId);
        if (serverConfig == null) {
            serverConfig = new TempServerConfig(serverId);
            serverStorage.put(serverId, serverConfig);
        }
        ServerTimeoutStorage storage = serverConfig.getServerTimeouts();
        String serverName = server.getName();
        if (storage != null && storage.getTimeoutRoleId() != null) {
            String timeoutRoleId = storage.getTimeoutRoleId();
            Role timeoutRole = apiClient.getRole(timeoutRoleId, server);
            if (timeoutRole != NO_ROLE) {
                //  Add role to user
                Set<Role> userRoles = apiClient.getMemberRoles(apiClient.getUserMember(user, server), server);
                //  Push the ban role to the front
                LinkedHashSet<String> newRoles = new LinkedHashSet<>(userRoles.size() + 1);
                newRoles.add(timeoutRoleId);
                userRoles.stream().map(Role::getId).
                        forEach(newRoles::add);
                //  Update
                apiClient.updateRoles(user, server, newRoles);
                return userRoles.size() < newRoles.size();
            } else {
                LOGGER.warn("Timeout role ID {} for server {} ({}) does not exist",
                        timeoutRoleId, serverName, serverId);
                apiClient.sendMessage(loc.localize("message.mod.timeout.bad_role", timeoutRoleId),
                        invocationChannel);
            }
        } else {
            storage = new ServerTimeoutStorage();
            serverConfig.setServerTimeouts(storage);
            serverStorage.put(serverId, serverConfig);
            LOGGER.warn("Timeout role for server {} ({}) is not configured",
                    serverName, serverId);
            apiClient.sendMessage(loc.localize("message.mod.timeout.not_configured"), invocationChannel);
        }
        return false;
    }

    public boolean isUserTimedOut(User user, Server server) {
        return isUserTimedOut(user.getId(), server.getId());
    }

    public boolean isUserTimedOut(String userId, String serverId) {
        ServerTimeoutStorage storage = SafeNav.of(serverStorage.get(serverId)).
                get(TempServerConfig::getServerTimeouts);
        if (storage != null) {
            ServerTimeout timeout = storage.getTimeouts().get(userId);
            if (timeout != null) {
                Instant now = Instant.now();
                return timeout.getEndTime().compareTo(now) > 0;
            }
        }
        return false;
    }

    public boolean doesTimeoutEntryExistForUser(User user, Server server) {
        return doesTimeoutEntryExistForUser(user.getId(), server.getId());
    }

    public boolean doesTimeoutEntryExistForUser(String userId, String serverId) {
        ServerTimeoutStorage storage = SafeNav.of(serverStorage.get(serverId)).
                get(TempServerConfig::getServerTimeouts);
        if (storage != null) {
            ServerTimeout timeout = storage.getTimeouts().get(userId);
            if (timeout != null) {
                return true;
            }
        }
        return false;
    }

    public void cancelTimeout(User user, Server server, Channel invocationChannel) {
        String serverId = server.getId();
        TempServerConfig serverConfig = serverStorage.get(serverId);
        if (serverConfig == null) {
            serverConfig = new TempServerConfig(serverId);
            serverStorage.put(serverId, serverConfig);
        }
        ServerTimeoutStorage storage = serverConfig.getServerTimeouts();
        removeTimeoutRole(user, server, apiClient.getChannelById(serverId));
        if (storage != null) {
            ServerTimeout timeout = storage.getTimeouts().remove(user.getId());
            saveServerConfig(serverConfig);
            if (timeout != null) {
                SafeNav.of(timeout.getTimerFuture()).ifPresent(f -> f.cancel(true));
                LOGGER.info("Cancelling timeout for {} ({}) in {} ({})",
                        user.getUsername(), user.getId(),
                        server.getName(), serverId);
                apiClient.sendMessage(loc.localize("commands.mod.stoptimeout.response",
                        user.getUsername(), user.getId()),
                        invocationChannel);
                return;
            }
        }
        LOGGER.warn("Unable to cancel: cannot find server or timeout entry for {} ({}) in {} ({})",
                user.getUsername(), user.getId(),
                server.getName(), server.getId());
        apiClient.sendMessage(loc.localize("commands.mod.stoptimeout.response.not_found",
                user.getUsername(), user.getId()),
                invocationChannel);
    }

    public void onTimeoutExpire(User user, Server server) {
        String serverId = server.getId();
        TempServerConfig serverConfig = serverStorage.get(serverId);
        if (serverConfig == null) {
            serverConfig = new TempServerConfig(serverId);
            serverStorage.put(serverId, serverConfig);
        }
        ServerTimeoutStorage storage = serverConfig.getServerTimeouts();
        if (storage != null) {
            ServerTimeout timeout = storage.getTimeouts().remove(user.getId());
            if (timeout != null) {
                saveServerConfig(serverConfig);
                LOGGER.info("Expiring timeout for {} ({}) in {} ({})",
                        user.getUsername(), user.getId(),
                        server.getName(), server.getId());
                if (apiClient.getUserById(user.getId(), server) != NO_USER) {
                    apiClient.sendMessage(loc.localize("message.mod.timeout.expire",
                            user.getId()),
                            server.getId());
                }
                removeTimeoutRole(user, server, apiClient.getChannelById(server.getId()));
                return;
            }
        }
        LOGGER.warn("Unable to expire: find server or timeout entry for {} ({}) in {} ({})",
                user.getUsername(), user.getId(),
                server.getName(), server.getId());
    }

    /**
     * Removes the timeout role from the given user. This does NOT create or manage any storage/persistence, it only
     * sets the user's roles
     *
     * @param user              The user to remove the timeout role
     * @param server            The server on which to remove the user from the timeout role
     * @param invocationChannel The channel to send messages on error
     */
    public boolean removeTimeoutRole(User user, Server server, Channel invocationChannel) {
        String serverId = server.getId();
        TempServerConfig serverConfig = serverStorage.get(serverId);
        if (serverConfig == null) {
            serverConfig = new TempServerConfig(serverId);
            serverStorage.put(serverId, serverConfig);
        }
        ServerTimeoutStorage storage = serverConfig.getServerTimeouts();
        String serverName = server.getName();
        if (storage != null && storage.getTimeoutRoleId() != null) {
            String timeoutRoleId = storage.getTimeoutRoleId();
            Role timeoutRole = apiClient.getRole(timeoutRoleId, server);
            if (timeoutRole != NO_ROLE) {
                //  Get roles
                Set<Role> userRoles = apiClient.getMemberRoles(apiClient.getUserMember(user, server), server);
                //  Delete the ban role
                LinkedHashSet<String> newRoles = new LinkedHashSet<>(userRoles.size() - 1);
                userRoles.stream().map(Role::getId).
                        filter(s -> !timeoutRoleId.equals(s)).
                        forEach(newRoles::add);
                //  Update
                apiClient.updateRoles(user, server, newRoles);
                return userRoles.size() == newRoles.size();
            } else {
                LOGGER.warn("Timeout role ID {} for server {} ({}) does not exist",
                        timeoutRoleId, serverName, serverId);
                apiClient.sendMessage(loc.localize("message.mod.timeout.bad_role", timeoutRoleId),
                        invocationChannel);
            }
        } else {
            storage = new ServerTimeoutStorage();
            serverConfig.setServerTimeouts(storage);
            serverStorage.put(serverId, serverConfig);
            LOGGER.warn("Timeout role for server {} ({}) is not configured",
                    storage.getTimeoutRoleId(), serverName, serverId);
            apiClient.sendMessage(loc.localize("message.mod.timeout.not_configured"), invocationChannel);
        }
        return false;
    }


    public void saveServerConfig(TempServerConfig storage) {
        try {
            Files.createDirectories(serverStorageDir);
        } catch (IOException e) {
            LOGGER.warn("Unable to create server storage directory", e);
            return;
        }
        Path serverStorageFile = serverStorageDir.resolve(storage.getServerId() + ".json");
        try (BufferedWriter writer = Files.newBufferedWriter(serverStorageFile, UTF_8, CREATE, TRUNCATE_EXISTING)) {
            gson.toJson(storage, writer);
            writer.flush();
        } catch (IOException e) {
            LOGGER.warn("Unable to write server storage file for " + storage.getServerId(), e);
            return;
        }
        LOGGER.info("Saved server {}", storage.getServerId());
    }

    public void loadServerConfigFiles() {
        if (!Files.exists(serverStorageDir)) {
            LOGGER.info("Server storage directory doesn't exist, not loading anything");
            return;
        }
        try (Stream<Path> files = Files.list(serverStorageDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json")).
                    forEach(this::loadServerConfig);
        } catch (IOException e) {
            LOGGER.warn("Unable to load server storage files", e);
            return;
        }
    }

    public void loadServerConfig(Path path) {
        boolean purge = false;
        TempServerConfig config;
        ServerTimeoutStorage storage;
        try (Reader reader = Files.newBufferedReader(path, UTF_8)) {
            config = gson.fromJson(reader, TempServerConfig.class);
            serverStorage.put(config.getServerId(), config);
            storage = config.getServerTimeouts();
            if (storage != null) {
                Server server = apiClient.getServerByID(config.getServerId());
                if (server == NO_SERVER) {
                    LOGGER.warn("Rejecting {} server storage file: server not found", config.getServerId());
                    return;
                }
                LOGGER.info("Loaded {} ({}) server storage file",
                        server.getName(), server.getId(), storage.getTimeoutRoleId());
                //  Prune expired entries
                for (Iterator<Map.Entry<String, ServerTimeout>> iter = storage.getTimeouts().entrySet().iterator();
                     iter.hasNext(); ) {
                    Map.Entry<String, ServerTimeout> e = iter.next();
                    ServerTimeout timeout = e.getValue();
                    String userId = timeout.getUserId();
                    User user = apiClient.getUserById(userId, server);
                    if (!isUserTimedOut(userId, server.getId())) {
                        //  Purge!
                        purge = true;
                        if (user == NO_USER) {
                            LOGGER.info("Ending timeout for departed user {} ({}) in {} ({})",
                                    timeout.getLastUsername(), userId,
                                    server.getName(), server.getId());
//                            apiClient.sendMessage(loc.localize("message.mod.timeout.expire.not_found",
//                                    user.getId()),
//                                    server.getId());
                            //  Don't need to remove the timeout role because leaving does that for us
                        } else {
                            //  Duplicated from onTimeoutExpire except without remove since we're removing in an iter
                            LOGGER.info("Expiring timeout for {} ({}) in {} ({})",
                                    user.getUsername(), user.getId(),
                                    server.getName(), server.getId());
                            //  Only send message if they still have the role
                            if (removeTimeoutRole(user, server, apiClient.getChannelById(server.getId()))) {
//                                apiClient.sendMessage(loc.localize("message.mod.timeout.expire",
//                                        user.getId()),
//                                        server.getId());
                            }
                        }
                        SafeNav.of(timeout.getTimerFuture()).ifPresent(f -> f.cancel(true));
                        iter.remove();
                    } else {
                        //  Start our futures
                        Duration duration = Duration.between(Instant.now(), timeout.getEndTime());
                        ScheduledFuture future = timeoutService.schedule(() ->
                                onTimeoutExpire(user, server), duration.getSeconds(), TimeUnit.SECONDS);
                        timeout.setTimerFuture(future);
                    }
                }
            }
        } catch (IOException | JsonParseException e) {
            LOGGER.warn("Unable to load server storage file " + path.toString(), e);
            return;
        }

        if (purge) {
            saveServerConfig(config);
        }
    }

    public void onReady() {
        //  Load configs
        loadServerConfigFiles();

        //  Reapply timeouts that may have dropped during downtime
        //  Unnecessary and causes other failures?
//        serverStorage.forEach((sid, conf) -> {
//            ServerTimeoutStorage st = conf.getServerTimeouts();
//            st.getTimeouts().forEach((uid, t) -> {
//                if (isUserTimedOut(uid, sid)) {
//                    //  Check if the user still has timeout role
//                    Server server = apiClient.getServerByID(sid);
//                    Set<Role> roles = apiClient.getMemberRoles(apiClient.getUserMember(uid, server), server);
//                    Set<String> roleIds = roles.stream().map(Role::getId).collect(Collectors.toSet());
//                    if (!roleIds.contains(st.getTimeoutRoleId())) {
//                        refreshTimeoutOnEvade(apiClient.getUserById(uid, server), server);
//                    }
//                }
//            });
//        });
    }

    private String formatInstant(Instant instant) {
        ZonedDateTime dateTime = ZonedDateTime.ofInstant(instant, ZoneId.systemDefault());
        return DATE_TIME_FORMATTER.format(dateTime);
    }

    private String formatDuration(Duration duration) {
        StringJoiner joiner = new StringJoiner(", ");
        long days = duration.toDays();
        if (days > 0) {
            long years = days / 365;
            if (years > 0) {
                long centuries = years / 100;
                if (centuries > 0) {
                    long millenia = centuries / 10;
                    joiner.add(loc.localize("time.millenia", millenia));
                    centuries = centuries % 10;
                    if (centuries > 0) {
                        joiner.add(loc.localize("time.centuries", centuries));
                    }
                }
                years = years % 100;
                if (years > 0) {
                    joiner.add(loc.localize("time.years", years));
                }
            }
            days = days % 365;
            joiner.add(loc.localize("time.days", days));
        }
        long hours = duration.toHours() % 24L;
        if (hours > 0) {
            joiner.add(loc.localize("time.hours", hours));
        }
        long minutes = duration.toMinutes() % 60L;
        if (minutes > 0) {
            joiner.add(loc.localize("time.minutes", minutes));
        }
        long seconds = duration.getSeconds() % 60L;
        if (seconds > 0) {
            joiner.add(loc.localize("time.seconds", seconds));
        }
        return joiner.toString();
    }

    public Map<String, TempServerConfig> getServerStorage() {
        return serverStorage;
    }
}
