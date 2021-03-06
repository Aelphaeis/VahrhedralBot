package co.phoenixlab.discord.commands;

import co.phoenixlab.common.localization.LocaleStringProvider;
import co.phoenixlab.common.localization.Localizer;
import co.phoenixlab.discord.Command;
import co.phoenixlab.discord.CommandDispatcher;
import co.phoenixlab.discord.MessageContext;
import co.phoenixlab.discord.VahrhedralBot;
import co.phoenixlab.discord.api.ApiConst;
import co.phoenixlab.discord.api.DiscordApiClient;
import co.phoenixlab.discord.api.entities.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import org.apache.http.HttpHeaders;

import javax.script.*;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static co.phoenixlab.discord.api.DiscordApiClient.NO_USER;
import static co.phoenixlab.discord.commands.CommandUtil.findUser;

public class AdminCommands {

    private final CommandDispatcher dispatcher;
    private Localizer loc;
    private final ScriptEngine scriptEngine;
    private final Gson gson;
    private final VahrhedralBot bot;
    private final Map<String, Object> storage;
    public static final String TRIPLE_BACKTICK = "```";

    public AdminCommands(VahrhedralBot bot) {
        this.bot = bot;
        dispatcher = new CommandDispatcher(bot, "");
        loc = bot.getLocalizer();
        scriptEngine = new ScriptEngineManager().getEngineByName("nashorn");
        storage = new HashMap<>();
        gson = new GsonBuilder().
                setPrettyPrinting().
                disableInnerClassSerialization().
                create();
    }

    public CommandDispatcher getAdminCommandDispatcher() {
        return dispatcher;
    }

    public void registerAdminCommands() {
        CommandDispatcher d = dispatcher;
        if (!bot.getConfig().isSelfBot()) {
            d.registerAlwaysActiveCommand("commands.admin.blacklist", this::adminBlacklist);
            d.registerAlwaysActiveCommand("commands.admin.pardon", this::adminPardon);
            d.registerAlwaysActiveCommand("commands.admin.telegram", this::adminTelegram);
        }
        d.registerAlwaysActiveCommand("commands.admin.start", this::adminStart);
        d.registerAlwaysActiveCommand("commands.admin.stop", this::adminStop);
        d.registerAlwaysActiveCommand("commands.admin.status", this::adminStatus);
        d.registerAlwaysActiveCommand("commands.admin.kill", this::adminKill);
        d.registerAlwaysActiveCommand("commands.admin.restart", this::adminRestart);
        d.registerAlwaysActiveCommand("commands.admin.raw", this::adminRaw);
        d.registerAlwaysActiveCommand("commands.admin.prefix", this::adminPrefix);
        d.registerAlwaysActiveCommand("commands.admin.eval", this::eval);
        d.registerAlwaysActiveCommand("commands.admin.find", this::find);
        d.registerAlwaysActiveCommand("commands.admin.playing", this::updateNowPlaying);
        d.registerAlwaysActiveCommand("commands.admin.integrity", this::checkIntegrity);
    }

    private void checkIntegrity(MessageContext context, String s) {
        DiscordApiClient apiClient = context.getApiClient();
        StringBuilder reportBuilder = new StringBuilder();
        reportBuilder.append("**__INTEGRITY REPORT__**\n");

        reportBuilder.append("**Server Map**\n");
        boolean ok = true;
        for (Map.Entry<String, Server> entry : apiClient.getServerMap().entrySet()) {
            String id = entry.getKey();
            Server server = entry.getValue();
            if (server == null) {
                reportBuilder.append(id).append(" Null server\n");
                ok = false;
                continue;
            }
            if (!id.equals(server.getId())) {
                reportBuilder.append(id).append(" Server ID does not match key: ").append(server.getId()).append("\n");
                ok = false;
            }
            ok &= checkServer(reportBuilder, id, server);
        }
        if (ok) {
            reportBuilder.append(":ok:\n");
        }
        ok = true;
        reportBuilder.append("**Server List**\n");
        for (Server server : apiClient.getServers()) {
            if (server == null) {
                reportBuilder.append("Null server\n");
                ok = false;
                continue;
            }
            ok &= checkServer(reportBuilder, server.getId(), server);
        }
        if (ok) {
            reportBuilder.append(":ok:");
        }

        apiClient.sendMessage(reportBuilder.toString(), context.getChannel());
    }

    private boolean checkServer(StringBuilder reportBuilder, String id, Server server) {
        if (server.getMembers() == null) {
            reportBuilder.append(id).append(" Null server member list\n");
            return false;
        }
        long count = server.getMembers().stream().
                filter(m -> m == null).
                count();
        if (count > 0) {
            reportBuilder.append(id).append(" ").append(count).append(" null members\n");
            return false;
        }
        count = server.getMembers().stream().
                filter(m -> m != null).
                map(Member::getUser).
                filter(u -> u == null).
                count();
        if (count > 0) {
            reportBuilder.append(id).append(" ").append(count).append(" null member users\n");
            return false;
        }
        return true;
    }

    private void adminStart(MessageContext context, String args) {
        DiscordApiClient apiClient = context.getApiClient();
        CommandDispatcher mainDispatcher = context.getBot().getMainCommandDispatcher();
        if (mainDispatcher.active().compareAndSet(false, true)) {
            apiClient.sendMessage(loc.localize("commands.admin.start.response.ok"),
                    context.getChannel());
        } else {
            apiClient.sendMessage(loc.localize("commands.admin.start.response.already_started"),
                    context.getChannel());
        }
    }

    private void adminStop(MessageContext context, String args) {
        DiscordApiClient apiClient = context.getApiClient();
        CommandDispatcher mainDispatcher = context.getBot().getMainCommandDispatcher();
        if (mainDispatcher.active().compareAndSet(true, false)) {
            apiClient.sendMessage(loc.localize("commands.admin.stop.response.ok"),
                    context.getChannel());
        } else {
            apiClient.sendMessage(loc.localize("commands.admin.stop.response.already_stopped"),
                    context.getChannel());
        }
    }

    private void adminStatus(MessageContext context, String args) {
        DiscordApiClient apiClient = context.getApiClient();
        CommandDispatcher mainDispatcher = context.getBot().getMainCommandDispatcher();
        long s = ManagementFactory.getRuntimeMXBean().getUptime() / 1000L;
        String uptime = String.format("%d:%02d:%02d:%02d", s / 86400, (s / 3600) % 24, (s % 3600) / 60, (s % 60));
        MemoryUsage heapMemoryUsage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        String memory = getMemoryInfo(heapMemoryUsage);
        String serverDetail = Integer.toString(apiClient.getServers().size());
        if (args.contains(loc.localize("commands.admin.status.subcommand.servers"))) {
            serverDetail = listServers(apiClient);
        }
        String response = loc.localize("commands.admin.status.response.format",
                loc.localize("commands.admin.status.response.state." +
                        (mainDispatcher.active().get() ? "running" : "stopped")),
                serverDetail,
                uptime,
                memory,
                ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage(),
                ManagementFactory.getOperatingSystemMXBean().getAvailableProcessors());
        apiClient.sendMessage(response, context.getChannel());
    }

    private String listServers(DiscordApiClient apiClient) {
        String serverDetail;
        StringJoiner serverJoiner = new StringJoiner(",\n");
        for (Server server : apiClient.getServers()) {
            serverJoiner.add(loc.localize("commands.admin.status.response.servers.entry", server.getName(), server.getId()));
        }
        serverDetail = loc.localize("commands.admin.status.response.servers.format",
                apiClient.getServers().size(), serverJoiner.toString());
        return serverDetail;
    }

    private String getMemoryInfo(MemoryUsage heapMemoryUsage) {
        return loc.localize("commands.admin.status.response.memory.format",
                heapMemoryUsage.getUsed() / 1048576L,
                heapMemoryUsage.getCommitted() / 1048576L,
                heapMemoryUsage.getMax() / 1048576L);
    }

    private void adminKill(MessageContext context, String args) {
        context.getApiClient().sendMessage(loc.localize("commands.admin.kill.response"),
                context.getChannel(), false);
        context.getBot().shutdown();
    }

    private void adminRestart(MessageContext context, String args) {
        context.getApiClient().sendMessage(loc.localize("commands.admin.restart.response"),
                context.getChannel(), false);
        context.getBot().shutdown(20);
    }

    private void adminBlacklist(MessageContext context, String args) {
        DiscordApiClient apiClient = context.getApiClient();
        VahrhedralBot bot = context.getBot();
        if (args.isEmpty()) {
            listBlacklistedUsers(context, apiClient, bot);
            return;
        }
        User user = findUser(context, args, true);
        if (user == NO_USER) {
            apiClient.sendMessage(loc.localize("commands.admin.blacklist.response.not_found"),
                    context.getChannel());
            return;
        }
        if (bot.getConfig().getAdmins().contains(user.getId())) {
            apiClient.sendMessage(loc.localize("commands.admin.blacklist.response.admin"),
                    context.getChannel());
            return;
        }
        bot.getConfig().getBlacklist().add(user.getId());
        bot.saveConfig();
        apiClient.sendMessage(loc.localize("commands.admin.blacklist.response.format",
                user.getUsername()),
                context.getChannel());
    }

    private void listBlacklistedUsers(MessageContext context, DiscordApiClient apiClient, VahrhedralBot bot) {
        StringJoiner joiner = new StringJoiner(", ");
        bot.getConfig().getBlacklist().stream().
                map(apiClient::getUserById).
                filter(user -> user != null).
                map(User::getUsername).
                forEach(joiner::add);
        String res = joiner.toString();
        if (res.isEmpty()) {
            res = loc.localize("commands.admin.blacklist.response.list.none");
        }
        apiClient.sendMessage(loc.localize("commands.admin.blacklist.response.list.format", res),
                context.getChannel());
    }

    private void adminPardon(MessageContext context, String args) {
        DiscordApiClient apiClient = context.getApiClient();
        if (args.isEmpty()) {
            apiClient.sendMessage(loc.localize("commands.admin.pardon.response.no_user"),
                    context.getChannel());
            return;
        }
        User user = findUser(context, args, true);
        if (user == NO_USER) {
            apiClient.sendMessage(loc.localize("commands.admin.pardon.response.not_found"),
                    context.getChannel());
            return;
        }
        boolean removed = context.getBot().getConfig().getBlacklist().remove(user.getId());
        context.getBot().saveConfig();
        if (removed) {
            apiClient.sendMessage(loc.localize("commands.admin.pardon.response.format",
                    user.getUsername()),
                    context.getChannel());
        } else {
            apiClient.sendMessage(loc.localize("commands.admin.pardon.response.not_blacklisted",
                    user.getUsername()),
                    context.getChannel());
        }
    }

    private void makeJoinPOSTRequest(MessageContext context, DiscordApiClient apiClient, String path) {
        try {
            HttpResponse<String> response = Unirest.post(ApiConst.INVITE_ENDPOINT + path).
                    header(HttpHeaders.AUTHORIZATION, apiClient.getToken()).
                    asString();
            if (response.getStatus() != 200) {
                VahrhedralBot.LOGGER.warn("Unable to join using invite link: HTTP {}: {}: {}",
                        response.getStatus(), response.getStatusText(), response.getBody());
                apiClient.sendMessage(loc.localize("commands.admin.join.response.http_error", response.getStatus()),
                        context.getChannel());
            }
        } catch (UnirestException e) {
            VahrhedralBot.LOGGER.warn("Unable to join using invite link", e);
            apiClient.sendMessage(loc.localize("commands.admin.join.response.network_error"),
                    context.getChannel());
        }
    }

    private void adminTelegram(MessageContext context, String s) {
        DiscordApiClient apiClient = context.getApiClient();
        String[] split = s.split(" ", 2);
        if (split.length != 2) {
            apiClient.sendMessage(loc.localize("commands.admin.telegram.response.invalid"),
                    context.getChannel());
            return;
        }
        User[] mentions = context.getMessage().getMentions();
        apiClient.sendMessage(split[1], split[0], Arrays.stream(mentions).
                map(User::getId).
                collect(Collectors.toList()).toArray(new String[mentions.length]));
    }

    public void adminRaw(MessageContext context, String args) {
        String channel = null;
        String raw = null;
        if (args.startsWith("cid=")) {
            String[] split = args.split(" ", 2);
            if (split.length == 2) {
                channel = split[0].substring("cid=".length());
                raw = split[1];
            }
        }
        if (channel == null || raw == null) {
            channel = context.getChannel().getId();
            raw = args;
        }
        try {
            OutboundMessage outboundMessage = new Gson().fromJson(raw, OutboundMessage.class);
            context.getApiClient().sendMessage(outboundMessage.getContent(),
                    channel,
                    outboundMessage.getMentions());
        } catch (JsonParseException e) {
            context.getApiClient().sendMessage(loc.localize("commands.admin.raw.response.invalid"),
                    context.getChannel());
        }
    }

    private void adminPrefix(MessageContext context, String s) {
        DiscordApiClient apiClient = context.getApiClient();
        VahrhedralBot bot = context.getBot();
        if (s.isEmpty()) {
            apiClient.sendMessage(loc.localize("commands.admin.prefix.response.get",
                    bot.getConfig().getCommandPrefix()),
                    context.getChannel());
        } else {
            context.getBot().getMainCommandDispatcher().setCommandPrefix(s);
            bot.getConfig().setCommandPrefix(s);
            bot.saveConfig();
            apiClient.sendMessage(loc.localize("commands.admin.prefix.response.set",
                    bot.getConfig().getCommandPrefix()),
                    context.getChannel());
        }
    }

    private void makeSandwich(MessageContext context, String args) {
        DiscordApiClient apiClient = context.getApiClient();
        if (loc.localize("commands.admin.sandwich.magic_word").equalsIgnoreCase(args) ||
                new Random().nextBoolean()) {
            apiClient.sendMessage(loc.localize("commands.admin.sandwich.response"),
                    context.getChannel());
        } else {
            apiClient.sendMessage(loc.localize("commands.admin.sandwich.response.magic"),
                    context.getChannel());
        }
    }

    private void updateNowPlaying(MessageContext context, String args) {
        DiscordApiClient apiClient = context.getApiClient();
        if (args.isEmpty()) {
            apiClient.updateNowPlaying(null);
        } else {
            apiClient.updateNowPlaying(args);
        }
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
        List<User> results;
        if (context.getMessage().isPrivateMessage()) {
            Stream<User> userStream = Stream.empty();
            for (Server server : apiClient.getServers()) {
                userStream = Stream.concat(userStream, server.getMembers().stream().
                        map(Member::getUser).
                        filter(u -> matcher.test(u.getUsername())));
            }
            results = userStream.distinct().
                    collect(Collectors.toList());
        } else {
            results = context.getServer().getMembers().stream().
                    map(Member::getUser).
                    filter(u -> matcher.test(u.getUsername())).
                    collect(Collectors.toList());
        }
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


    private void eval(MessageContext context, String args) {
        DiscordApiClient apiClient = context.getApiClient();
        if (!args.startsWith(TRIPLE_BACKTICK) || !args.endsWith(TRIPLE_BACKTICK)) {
            HttpResponse<String> response;
            try {
                response = Unirest.get(args).
                        asString();
            } catch (UnirestException e) {
                VahrhedralBot.LOGGER.warn("Unable to evaluate script", e);
                String msg = e.getLocalizedMessage();
                if (msg == null) {
                    msg = e.getClass().getSimpleName();
                }
                apiClient.sendMessage(loc.localize("commands.admin.eval.response.exception", msg),
                        context.getChannel());
                return;
            }
            if (response.getStatus() != 200) {
                VahrhedralBot.LOGGER.warn("Unable to evaluate script: HTTP {}: {}",
                        response.getStatus(), response.getStatusText());
                apiClient.sendMessage(loc.localize("commands.admin.eval.response.http_error",
                        response.getStatus(), response.getStatusText()), context.getChannel());
                return;
            }
            args = response.getBody();
        } else {
            args = args.substring(TRIPLE_BACKTICK.length(),
                    args.length() - TRIPLE_BACKTICK.length());
        }
        try {
            ScriptHelper helper = new ScriptHelper(context);
            ScriptContext scriptContext = new SimpleScriptContext();
            Bindings bindings = scriptContext.getBindings(ScriptContext.ENGINE_SCOPE);
            bindings.put("_", helper);
            bindings.put("ctx", context);
            bindings.put("msg", context.getMessage());
            bindings.put("cid", context.getChannel());
            bindings.put("author", context.getMessage().getAuthor());
            bindings.put("bot", context.getBot());
            bindings.put("loc", loc);
            bindings.put("api", context.getApiClient());
            Object ret = scriptEngine.eval(args, scriptContext);
            if (!helper.suppressOutput) {
                String retStr = "null";
                if (ret != null) {
                    if (helper.asJson) {
                        retStr = gson.toJson(ret);
                    } else {
                        retStr = ret.toString();
                    }
                }
                apiClient.sendMessage(TRIPLE_BACKTICK + retStr + TRIPLE_BACKTICK,
                        context.getChannel());
            }
        } catch (Exception | StackOverflowError e) {
            VahrhedralBot.LOGGER.warn("Unable to evaluate script", e);
            String msg = e.getLocalizedMessage();
            if (msg == null) {
                msg = e.getClass().getSimpleName();
            }
            apiClient.sendMessage(TRIPLE_BACKTICK + msg + TRIPLE_BACKTICK, context.getChannel());
        }
    }

    public class ScriptHelper {

        private final MessageContext context;
        public boolean suppressOutput;
        public boolean asJson;

        public ScriptHelper(MessageContext context) {
            this.context = context;
            suppressOutput = false;
        }

        public Future<Message> sendMessage(String content) {
            return sendMessageCid(content, context.getChannel().getId());
        }

        public Future<Message> sendMessageCid(String content, String cid) {
            return context.getApiClient().sendMessage(content, cid);
        }

        public void suppress() {
            suppressOutput = true;
        }

        public void asJson() {
            asJson = true;
        }

        public Object field(Object object, String fieldName) throws Exception {
            if (fieldName.contains(".")) {
                String[] split = fieldName.split("\\.");
                Object ret = object;
                for (int i = 0; i < split.length; i++) {
                    ret = field(ret, split[i]);
                }
                return ret;
            }
            Field field = object.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(object);
        }

        public Method method(Object object, String methodName, String[] argSimpleClassNames) {
            for (Method method : object.getClass().getDeclaredMethods()) {
                if (method.getName().equals(methodName) &&
                        method.getParameterCount() == argSimpleClassNames.length) {
                    Class<?>[] parameterTypes = method.getParameterTypes();
                    for (int i = 0; i < argSimpleClassNames.length; i++) {
                        if (!argSimpleClassNames[i].equals(parameterTypes[i].getSimpleName())) {
                            return null;
                        }
                    }
                    method.setAccessible(true);
                    return method;
                }
            }
            return null;
        }

        public String fields(Object object) {
            Field[] fields = object.getClass().getDeclaredFields();
            StringJoiner j = new StringJoiner("\n");
            for (Field field : fields) {
                j.add(field.getType().getName() + " " + field.getName());
            }
            return j.toString();
        }

        public String methods(Object object) {
            Method[] methods = object.getClass().getDeclaredMethods();
            StringJoiner j = new StringJoiner("\n");
            for (Method method : methods) {
                j.add(method.toString());
            }
            return j.toString();
        }

        public String newCommand(String cmd, String desc, String args, Command command) {
            String namespace = "temp.js." + cmd;
            loc.addLocaleStringProvider(new TempCommandLocaleProvider(namespace, cmd, desc, desc, args));
            bot.getMainCommandDispatcher().registerCommand(namespace, command);
            return String.format("Command `%s` registered in namespace `%s`", cmd, namespace);
        }

        public void store(String key, Object object) {
            storage.put(key, object);
        }

        public Object get(String key) {
            return storage.get(key);
        }

        public Object delete(String key) {
            return storage.remove(key);
        }

        public long time() {
            return System.currentTimeMillis();
        }
    }

    private class TempCommandLocaleProvider implements LocaleStringProvider {

        private final Map<String, String> storage;

        public TempCommandLocaleProvider(String namespace, String cmd, String help,
                                         String detailedHelp, String arguments) {
            storage = new HashMap<>(4);
            storage.put(namespace + ".command", cmd);
            storage.put(namespace + ".help", help);
            storage.put(namespace + ".detailed_help", detailedHelp);
            storage.put(namespace + ".arguments", arguments);
        }

        @Override
        public void setActiveLocale(Locale locale) {
            //  Ignore
        }

        @Override
        public String get(String key) {
            return storage.get(key);
        }

        @Override
        public boolean contains(String key) {
            return storage.containsKey(key);
        }
    }
}
