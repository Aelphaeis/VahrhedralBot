package co.phoenixlab.discord;

import co.phoenixlab.discord.api.entities.Message;
import co.phoenixlab.discord.api.entities.User;
import co.phoenixlab.discord.api.event.MessageReceivedEvent;
import com.google.common.eventbus.Subscribe;

public class EventListener {

    private final VahrhedralBot bot;

    public EventListener(VahrhedralBot bot) {
        this.bot = bot;
    }

    @Subscribe
    public void onMessageRecieved(MessageReceivedEvent messageReceivedEvent) {
        Message message = messageReceivedEvent.getMessage();
        if (message.getContent().startsWith(bot.getConfig().getCommandPrefix())) {
            bot.getMainCommandDispatcher().handleCommand(message);
            return;
        }
        User me = bot.getApiClient().getClientUser();
        for (User user : message.getMentions()) {
            if (me.equals(user)) {
                handleMention(message);
                return;
            }
        }
    }

    private void handleMention(Message message) {
        String otherId = message.getAuthor().getId();
        bot.getApiClient().sendMessage(bot.getLocalizer().localize("message.mention.response", otherId),
                message.getChannelId(), new String[] {otherId});
    }


}
