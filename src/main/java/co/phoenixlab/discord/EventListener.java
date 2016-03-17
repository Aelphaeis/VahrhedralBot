package co.phoenixlab.discord;

import co.phoenixlab.discord.api.DiscordApiClient;
import co.phoenixlab.discord.api.entities.Channel;
import co.phoenixlab.discord.api.entities.Message;
import co.phoenixlab.discord.api.entities.Server;
import co.phoenixlab.discord.api.entities.User;
import co.phoenixlab.discord.api.event.LogInEvent;
import co.phoenixlab.discord.api.event.MemberChangeEvent;
import co.phoenixlab.discord.api.event.MessageReceivedEvent;
import co.phoenixlab.discord.api.event.ServerJoinLeaveEvent;
import com.google.common.eventbus.Subscribe;

import java.util.*;
import java.util.function.Consumer;

public class EventListener {

    private final VahrhedralBot bot;

    private Map<String, Consumer<Message>> messageListeners;

    public Map<String, Consumer<MemberChangeEvent>> memberChangeEventListener;

    public Map<String, String> joinMessageRedirect;

    public Set<String> ignoredServers;

    public EventListener(VahrhedralBot bot) {
        this.bot = bot;
        messageListeners = new HashMap<>();
        memberChangeEventListener = new HashMap<>();
        joinMessageRedirect = new HashMap<>();
        ignoredServers = new HashSet<>();
        messageListeners.put("mention-bot", message -> {
            User me = bot.getApiClient().getClientUser();
            for (User user : message.getMentions()) {
                if (me.equals(user)) {
                    handleMention(message);
                    return;
                }
            }
        });
    }

    @Subscribe
    public void onMessageRecieved(MessageReceivedEvent messageReceivedEvent) {
        Message message = messageReceivedEvent.getMessage();
        if (bot.getConfig().getBlacklist().contains(message.getAuthor().getId())) {
            return;
        }

        Channel channel = bot.getApiClient().getChannelById(message.getChannelId());
        if (channel != DiscordApiClient.NO_CHANNEL && channel.getParent() != null) {
            if (ignoredServers.contains(channel.getParent().getId())) {
                return;
            }
        }
        if (message.getContent().startsWith(bot.getConfig().getCommandPrefix())) {
            bot.getMainCommandDispatcher().handleCommand(message);
            return;
        }
        messageListeners.values().forEach(c -> c.accept(message));
    }

    private void handleMention(Message message) {
        if (!bot.getMainCommandDispatcher().active().get()) {
            return;
        }
        String otherId = message.getAuthor().getId();
        bot.getApiClient().sendMessage(bot.getLocalizer().localize("message.mention.response",
                message.getAuthor().getUsername()),
                message.getChannelId(), new String[]{otherId});
    }

    @Subscribe
    public void onServerJoinLeave(ServerJoinLeaveEvent event) {
        if (!bot.getMainCommandDispatcher().active().get()) {
            return;
        }
        if (event.isJoin()) {
            Server server = event.getServer();
            //  Default channel has same ID as server
            Channel channel = bot.getApiClient().getChannelById(server.getId());
            if (channel != DiscordApiClient.NO_CHANNEL) {
                bot.getApiClient().sendMessage(bot.getLocalizer().localize("message.on_join.response",
                        bot.getApiClient().getClientUser().getUsername()),
                        channel.getId());
            }
        }
    }

    @Subscribe
    public void onMemberChangeEvent(MemberChangeEvent event) {
        if (!bot.getMainCommandDispatcher().active().get()) {
            return;
        }
        Server server = event.getServer();
        //  Default channel has same ID as server
        Channel channel = bot.getApiClient().getChannelById(server.getId());
        if (channel == DiscordApiClient.NO_CHANNEL) {
            return;
        }
        String key;
        if (event.getMemberChange() == MemberChangeEvent.MemberChange.ADDED) {
            key = "message.new_member.response";
        } else if (event.getMemberChange() == MemberChangeEvent.MemberChange.DELETED) {
            key = "message.member_quit.response";
        } else {
            return;
        }
        String cid = channel.getId();
        if (joinMessageRedirect.containsKey(server.getId())) {
            cid = joinMessageRedirect.get(server.getId());
        }
        bot.getApiClient().sendMessage(bot.getLocalizer().localize(key,
                event.getMember().getUser().getUsername(),
                event.getMember().getUser().getId()),
                cid);
        memberChangeEventListener.values().forEach(c -> c.accept(event));
    }

    public boolean isJoin(MemberChangeEvent event) {
        return event.getMemberChange() == MemberChangeEvent.MemberChange.ADDED;
    }

    public void registerMessageListner(String name, Consumer<Message> listener) {
        messageListeners.put(Objects.requireNonNull(name), Objects.requireNonNull(listener));
    }

    public void deleteMessageListener(String name) {
        messageListeners.remove(Objects.requireNonNull(name));
    }

    @Subscribe
    public void onLogIn(LogInEvent logInEvent) {
        bot.getCommands().onLogIn(logInEvent);
    }

}
