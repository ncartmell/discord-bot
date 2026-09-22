package org.example;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static net.dv8tion.jda.api.interactions.commands.OptionType.*;

public class DiscordBot extends ListenerAdapter {

    private final BungieClient bungie = new BungieClient();
    private final ManifestCache manifest = new ManifestCache(bungie);
    /** Name lookups for the tens of thousands of hashes the API deals in. */
    private final Manifest names = new Manifest(bungie, manifest);
    private final Store store = new Store();
    private final Ghost ghost = new Ghost(bungie, names, store);
    private BackgroundThread watcher;

    /**
     * Whether to answer {@code !name} messages as well as slash commands.
     *
     * <p>Off by default: reading message text needs the privileged MESSAGE_CONTENT intent,
     * and requesting an intent that has not been enabled in the developer portal stops the
     * bot logging in at all. Turning this on is a deliberate two-step choice.
     */
    private static final boolean PREFIX_COMMANDS = Config.flag("ENABLE_PREFIX_COMMANDS");

    public static void main(String[] args) {
        DiscordBot bot = new DiscordBot();

        EnumSet<GatewayIntent> intents = PREFIX_COMMANDS
                ? EnumSet.of(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT,
                             GatewayIntent.DIRECT_MESSAGES)
                : EnumSet.noneOf(GatewayIntent.class);

        JDA jda = JDABuilder.createLight(Config.require("DISCORD_BOT_TOKEN"), intents)
                .addEventListeners(bot)
                .build();

        // These commands might take a few minutes to be active after creation/update/delete
        jda.updateCommands().addCommands(
                Commands.slash("ban", "Ban a user from this server. Requires permission to ban users.")
                        .addOptions(new OptionData(USER, "user", "The user to ban") // USER type allows to include members of the server or other users by id
                                .setRequired(true)) // This command requires a parameter
                        .addOptions(new OptionData(INTEGER, "del_days", "Delete messages from the past days.") // This is optional
                                .setRequiredRange(0, 7)) // Only allow values between 0 and 7 (inclusive)
                        .addOptions(new OptionData(STRING, "reason", "The ban reason to use (default: Banned by <user>)")) // optional reason
                        .setGuildOnly(true) // This way the command can only be executed from a guild, and not the DMs
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.BAN_MEMBERS)), // Only members with the BAN_MEMBERS permission are going to see this command

                // Simple reply commands
                Commands.slash("say", "Makes the bot say what you tell it to")
                        .addOption(STRING, "content", "What the bot should say", true), // you can add required options like this too

                // Commands without any inputs
                Commands.slash("leave", "Make the bot leave the server")
                        .setGuildOnly(true) // this doesn't make sense in DMs
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED), // only admins should be able to use this command.

                Commands.slash("prune", "Prune messages from this channel")
                        .addOption(INTEGER, "amount", "How many messages to prune (Default 100)") // simple optional argument
                        .setGuildOnly(true)
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MESSAGE_MANAGE)),

                Commands.slash("item", "Look up a Destiny item by its manifest hash")
                        .addOption(STRING, "name", "Item name, or a hash from a light.gg or Armory URL", true),

                Commands.slash("weekly", "Show the milestones currently active this week"),

                Commands.slash("news", "The latest articles from Bungie.net"),

                Commands.slash("profile", "Look up a player's lifetime PvE stats")
                        .addOption(STRING, "name", "Bungie name, e.g. Guardian#1234", true),

                Commands.slash("watch", "Announce the weekly rotation in this channel when it changes")
                        .setGuildOnly(true)
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_CHANNEL)),

                // ---- the ghost ----

                Commands.slash("link", "Connect your Destiny account so I can manage your gear")
                        .addOption(STRING, "code", "The code from the Bungie callback page"),

                Commands.slash("unlink", "Disconnect your Destiny account"),

                Commands.slash("activity", "What you're doing right now"),

                Commands.slash("loadout", "Save and manage gear sets")
                        .addSubcommands(
                                new SubcommandData("save", "Save what you're wearing right now")
                                        .addOption(STRING, "name", "What to call it", true),
                                new SubcommandData("list", "Every set you've saved"),
                                new SubcommandData("show", "What's in a set")
                                        .addOptions(loadoutName()),
                                new SubcommandData("delete", "Forget a set")
                                        .addOptions(loadoutName())),

                Commands.slash("equip", "Put a saved set on, or queue it until you're in orbit")
                        .addOptions(loadoutName()),

                Commands.slash("activityloadout", "Equip the set mapped to the activity you're in"),

                Commands.slash("map", "Bind a set to an activity — run it while you're in there")
                        .addOptions(loadoutName().setName("loadout")
                                .setDescription("The set to bind").setRequired(true))
                        .addOption(STRING, "activity", "Activity name or hash, if you're not in it now"),

                Commands.slash("unmap", "Remove the binding for an activity")
                        .addOption(STRING, "activity", "Activity hash, if you're not in it now"),

                Commands.slash("autoequip", "Whether I act on my own between activities")
                        .addOption(BOOLEAN, "on", "Turn it on or off", true),

                Commands.slash("artifact", "Your seasonal artifact and which perks are active"),

                Commands.slash("snapshot", "Save your current gear into an in-game loadout slot")
                        .addOptions(new OptionData(INTEGER, "slot", "Which of the 20 slots", true)
                                .setRequiredRange(1, 20))
        ).queue();

        // Downloads in the background; commands work meanwhile via per-hash lookups.
        bot.names.loadInBackground();

        new GhostWatcher(jda, bot.ghost, bot.store).start();
    }

    /** A loadout-name option that completes from whatever the caller has saved. */
    private static OptionData loadoutName() {
        return new OptionData(STRING, "name", "Which set", true).setAutoComplete(true);
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event)
    {
        String discordId = event.getUser().getId();
        switch (event.getName())
        {
            case "ban":
                if (guildOnly(event)) return;
                Member member = event.getOption("user").getAsMember(); // the "user" option is required, so it doesn't need a null-check here
                User user = event.getOption("user").getAsUser();
                ban(event, user, member);
                break;
            case "say":
                say(event, event.getOption("content").getAsString()); // content is required so no null-check here
                break;
            case "leave":
                if (guildOnly(event)) return;
                leave(event);
                break;
            case "prune": // 2 stage command with a button prompt
                if (guildOnly(event)) return;
                prune(event);
                break;
            case "item":
                destiny(event, false, () -> {
                    String query = event.getOption("name").getAsString().trim();
                    // A name is what people have; the hash is what the API wants.
                    if (!query.chars().allMatch(Character::isDigit)) {
                        long resolved = names.resolveItem(query);
                        if (resolved == -1) {
                            return Destiny.error("Nothing called `" + query + "`."
                                    + (names.isReady() ? "" : " The manifest is still loading — try again shortly."));
                        }
                        query = String.valueOf(resolved);
                    }
                    return Destiny.item(bungie, manifest, query);
                });
                break;
            case "weekly":
                destiny(event, false, () -> Destiny.weekly(bungie, manifest));
                break;
            case "news":
                destiny(event, false, () -> Destiny.news(bungie));
                break;
            case "profile":
                destiny(event, false, () -> Destiny.profile(bungie, event.getOption("name").getAsString()));
                break;
            case "watch":
                if (guildOnly(event)) return;
                watch(event);
                break;
            case "link":
                OptionMapping code = event.getOption("code");
                // Always ephemeral: the first half carries an authorisation link and the
                // second half a single-use code, and neither belongs in a shared channel.
                if (code == null)
                    destiny(event, true, () -> ghost.beginLink(discordId));
                else
                    destiny(event, true, () -> ghost.completeLink(discordId, code.getAsString()));
                break;
            case "unlink":
                destiny(event, true, () -> ghost.unlink(discordId));
                break;
            case "activity":
                destiny(event, false, () -> ghost.activity(discordId));
                break;
            case "loadout":
                loadout(event, discordId);
                break;
            case "equip":
                // Queues when blocked — asking for a set mid-activity means you want it next.
                destiny(event, false, () ->
                        ghost.equipLoadout(discordId, event.getOption("name").getAsString(), true));
                break;
            case "activityloadout":
                destiny(event, false, () -> ghost.activityLoadout(discordId));
                break;
            case "map":
                destiny(event, false, () -> ghost.mapActivity(discordId,
                        event.getOption("loadout").getAsString(),
                        event.getOption("activity", null, OptionMapping::getAsString)));
                break;
            case "unmap":
                destiny(event, false, () -> ghost.unmapActivity(discordId,
                        event.getOption("activity", null, OptionMapping::getAsString)));
                break;
            case "autoequip":
                destiny(event, true, () -> ghost.autoEquip(discordId, event.getOption("on").getAsBoolean()));
                break;
            case "snapshot":
                destiny(event, false, () -> ghost.snapshot(discordId, event.getOption("slot").getAsInt()));
                break;
            case "artifact":
                destiny(event, false, () -> ghost.artifact(discordId));
                break;
            case "shaders":
                event.reply("I'll shade you").setEphemeral(true).queue();
                break;
            default:
                event.reply("I can't handle that command right now :(").setEphemeral(true).queue();
        }
    }

    private void loadout(SlashCommandInteractionEvent event, String discordId)
    {
        String sub = event.getSubcommandName();
        if (sub == null)
        {
            event.reply("Pick a subcommand.").setEphemeral(true).queue();
            return;
        }
        switch (sub)
        {
            case "save" -> destiny(event, false, () ->
                    ghost.saveLoadout(discordId, event.getOption("name").getAsString()));
            case "list" -> destiny(event, false, () -> ghost.listLoadouts(discordId));
            case "show" -> destiny(event, false, () ->
                    ghost.showLoadout(discordId, event.getOption("name").getAsString()));
            case "delete" -> destiny(event, true, () ->
                    ghost.deleteLoadout(discordId, event.getOption("name").getAsString()));
            default -> event.reply("Unknown subcommand.").setEphemeral(true).queue();
        }
    }

    /** Completes loadout names from the caller's own saved sets. */
    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event)
    {
        if (!event.getFocusedOption().getName().equals("name")
                && !event.getFocusedOption().getName().equals("loadout"))
            return;

        String typed = event.getFocusedOption().getValue().toLowerCase(Locale.ROOT);
        List<Command.Choice> choices = new ArrayList<>();
        Store.User user = store.peek(event.getUser().getId());
        if (user == null)
        {
            event.replyChoices(choices).queue();
            return;
        }
        for (String name : user.loadouts.keySet())
        {
            if (name.startsWith(typed))
            {
                choices.add(new Command.Choice(name, name));
                if (choices.size() == 25) // Discord's limit
                    break;
            }
        }
        event.replyChoices(choices).queue();
    }

    /**
     * Handles {@code !kingsfall} style shorthands when prefix commands are enabled.
     *
     * <p>These always queue if the game refuses, because naming a set is a statement of
     * intent about what you are heading into rather than a comment on where you are.
     */
    @Override
    public void onMessageReceived(MessageReceivedEvent event)
    {
        if (!PREFIX_COMMANDS || event.getAuthor().isBot())
            return;

        String content = event.getMessage().getContentRaw().trim();
        if (!content.startsWith("!") || content.length() < 2)
            return;

        String[] parts = content.substring(1).split("\\s+", 2);
        String verb = parts[0].toLowerCase(Locale.ROOT);
        String argument = parts.length > 1 ? parts[1].trim() : "";
        String discordId = event.getAuthor().getId();
        MessageChannel channel = event.getChannel();

        Thread.ofVirtual().start(() -> {
            try
            {
                MessageEmbed embed = switch (verb)
                {
                    // Print a set, or everything saved when no name is given.
                    case "loadout", "loadouts" -> argument.isEmpty()
                            ? ghost.listLoadouts(discordId)
                            : ghost.showLoadout(discordId, argument);
                    // Save what you are wearing under a name.
                    case "set", "save" -> ghost.saveLoadout(discordId, argument);
                    // Equip by name, or work it out from the activity when unnamed.
                    case "equip" -> argument.isEmpty()
                            ? ghost.activityLoadout(discordId)
                            : ghost.equipLoadout(discordId, argument, true);
                    case "activityloadout" -> ghost.activityLoadout(discordId);
                    case "map" -> ghost.mapActivity(discordId, argument, null);
                    case "activity" -> ghost.activity(discordId);
                    case "artifact" -> ghost.artifact(discordId);
                    // Anything else is treated as a loadout name: !kingsfall
                    default -> ghost.equipLoadout(discordId, verb, true);
                };
                channel.sendMessageEmbeds(embed).queue();
            }
            catch (Exception e)
            {
                // A bare "!something" that matches no saved set is far more likely to be
                // another bot's prefix or a typo than a request to this one, so stay quiet
                // unless the message was clearly aimed here.
                if (!KNOWN_VERBS.contains(verb) && !hasLoadout(discordId, verb))
                    return;
                channel.sendMessageEmbeds(Destiny.error(e.getMessage())).queue();
            }
        });
    }

    /** Prefix words the bot owns, so a failure is worth reporting rather than ignoring. */
    private static final java.util.Set<String> KNOWN_VERBS = java.util.Set.of(
            "loadout", "loadouts", "set", "save", "equip", "activityloadout", "map",
            "activity", "artifact");

    private boolean hasLoadout(String discordId, String name)
    {
        Store.User user = store.peek(discordId);
        return user != null && user.loadouts.containsKey(name.toLowerCase(Locale.ROOT));
    }

    /** Replies and returns true when a guild-only command was used outside a guild. */
    private boolean guildOnly(SlashCommandInteractionEvent event)
    {
        if (event.getGuild() != null)
            return false;
        event.reply("That one only works in a server.").setEphemeral(true).queue();
        return true;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event)
    {
        String[] id = event.getComponentId().split(":"); // this is the custom id we specified in our button
        String authorId = id[0];
        String type = id[1];
        // Check that the button is for the user that clicked it, otherwise just ignore the event (let interaction fail)
        if (!authorId.equals(event.getUser().getId()))
            return;
        event.deferEdit().queue(); // acknowledge the button was clicked, otherwise the interaction will fail

        MessageChannel channel = event.getChannel();
        switch (type)
        {
            case "prune":
                int amount = Integer.parseInt(id[2]);
                event.getChannel().getIterableHistory()
                        .skipTo(event.getMessageIdLong())
                        .takeAsync(amount)
                        .thenAccept(channel::purgeMessages);
                // fallthrough delete the prompt message with our buttons
            case "delete":
                event.getHook().deleteOriginal().queue();
        }
    }

    public void ban(SlashCommandInteractionEvent event, User user, Member member)
    {
        event.deferReply(true).queue(); // Let the user know we received the command before doing anything else
        InteractionHook hook = event.getHook(); // This is a special webhook that allows you to send messages without having permissions in the channel and also allows ephemeral messages
        hook.setEphemeral(true); // All messages here will now be ephemeral implicitly
        if (!event.getMember().hasPermission(Permission.BAN_MEMBERS))
        {
            hook.sendMessage("You do not have the required permissions to ban users from this server.").queue();
            return;
        }

        Member selfMember = event.getGuild().getSelfMember();
        if (!selfMember.hasPermission(Permission.BAN_MEMBERS))
        {
            hook.sendMessage("I don't have the required permissions to ban users from this server.").queue();
            return;
        }

        if (member != null && !selfMember.canInteract(member))
        {
            hook.sendMessage("This user is too powerful for me to ban.").queue();
            return;
        }

        // optional command argument, fall back to 0 if not provided
        int delDays = event.getOption("del_days", 0, OptionMapping::getAsInt); // this last part is a method reference used to "resolve" the option value

        // optional ban reason with a lazy evaluated fallback (supplier)
        String reason = event.getOption("reason",
                () -> "Banned by " + event.getUser().getName(), // used if getOption("reason") is null (not provided)
                OptionMapping::getAsString); // used if getOption("reason") is not null (provided)

        // Ban the user and send a success response
        event.getGuild().ban(user, delDays, TimeUnit.DAYS)
                .reason(reason) // audit-log ban reason (sets X-AuditLog-Reason header)
                .flatMap(v -> hook.sendMessage("Banned user " + user.getName())) // chain a followup message after the ban is executed
                .queue(); // execute the entire call chain
    }

    public void say(SlashCommandInteractionEvent event, String content)
    {
        event.reply(content).queue(); // This requires no permissions!
    }

    public void leave(SlashCommandInteractionEvent event)
    {
        if (!event.getMember().hasPermission(Permission.KICK_MEMBERS))
            event.reply("You do not have permissions to kick me.").setEphemeral(true).queue();
        else
            event.reply("Leaving the server... :wave:") // Yep we received it
                    .flatMap(v -> event.getGuild().leave()) // Leave server after acknowledging the command
                    .queue();
    }

    public void prune(SlashCommandInteractionEvent event)
    {
        OptionMapping amountOption = event.getOption("amount"); // This is configured to be optional so check for null
        int amount = amountOption == null
                ? 100 // default 100
                : (int) Math.min(200, Math.max(2, amountOption.getAsLong())); // enforcement: must be between 2-200
        String userId = event.getUser().getId();
        event.reply("This will delete " + amount + " messages.\nAre you sure?") // prompt the user with a button menu
                .addActionRow(// this means "<style>(<id>, <label>)", you can encode anything you want in the id (up to 100 characters)
                        Button.secondary(userId + ":delete", "Nevermind!"),
                        Button.danger(userId + ":prune:" + amount, "Yes!")) // the first parameter is the component id we use in onButtonInteraction above
                .queue();
    }

    /** Something that builds an embed and may fail talking to Bungie. */
    @FunctionalInterface
    private interface EmbedSupplier {
        MessageEmbed get() throws Exception;
    }

    /**
     * Runs a Bungie-backed command off the event thread.
     *
     * <p>JDA gives you three seconds to acknowledge an interaction, and a manifest lookup
     * can take longer than that, so every one of these defers first and follows up. The
     * work then happens on a separate thread rather than blocking JDA's event loop.
     */
    private void destiny(SlashCommandInteractionEvent event, boolean ephemeral, EmbedSupplier supplier)
    {
        event.deferReply(ephemeral).queue();
        Thread.ofVirtual().start(() -> {
            try
            {
                event.getHook().sendMessageEmbeds(supplier.get()).queue();
            }
            catch (Exception e)
            {
                // Messages from the ghost are already written for a person to read, so they
                // are passed through rather than wrapped in a transport-level apology.
                String message = e.getMessage();
                event.getHook()
                        .sendMessageEmbeds(Destiny.error(
                                message == null || message.isBlank() ? "Something went wrong." : message))
                        .queue();
            }
        });
    }

    /** Starts the milestone watcher in the channel the command was used in. */
    private void watch(SlashCommandInteractionEvent event)
    {
        if (!event.getChannel().getType().isMessage() || event.getChannelType().isThread())
        {
            event.reply("Use this in a normal text channel.").setEphemeral(true).queue();
            return;
        }

        TextChannel channel = event.getChannel().asTextChannel();
        if (watcher != null && watcher.isAlive())
        {
            watcher.interrupt();
        }

        watcher = new BackgroundThread(channel, bungie, manifest);
        watcher.start();
        event.reply("Watching for the weekly reset. I'll post here when the rotation changes.")
                .setEphemeral(true)
                .queue();
    }
}
