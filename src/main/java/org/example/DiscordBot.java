package org.example;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.PermissionOverride;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

import static net.dv8tion.jda.api.interactions.commands.OptionType.*;

public class DiscordBot extends ListenerAdapter {

    private final BungieClient bungie = new BungieClient();
    private final ManifestCache manifest = new ManifestCache(bungie);
    /** Name lookups for the tens of thousands of hashes the API deals in. */
    private final Manifest names = new Manifest(bungie, manifest);
    private final Store store = new Store();
    private final Ghost ghost = new Ghost(bungie, names, store);
    private final Collection collection = new Collection(bungie, ghost);
    private final Stats stats = new Stats(bungie, names, store);
    private final Vendors vendors = new Vendors(bungie, names, store, ghost, collection);
    private final World world = new World(bungie, names, store, ghost);
    private final Weekly weekly = new Weekly(bungie, names);
    private final Progress progress = new Progress(bungie, names, manifest, store, ghost);
    private final Seals seals = new Seals(bungie, names, store, ghost);
    private final Clan clan = new Clan(bungie, store);
    private final Lfg lfg = new Lfg(names, store, stats);

    /**
     * Where the reset and Xûr announcements go.
     *
     * <p>Not final because it needs the JDA instance, which does not exist until the bot
     * has connected.
     */
    private Announcer announcer;

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
                Commands.slash("item", "Look up a Destiny item by its manifest hash")
                        .addOption(STRING, "name", "Item name, or a hash from a light.gg or Armory URL", true),

                Commands.slash("weekly", "What's featured this week, and when it resets"),

                Commands.slash("clan", "Your clan, and this week's engram progress"),

                Commands.slash("lfg", "Post a fireteam others can join")
                        .addOption(STRING, "activity", "What you're running", true)
                        .addOptions(new OptionData(INTEGER, "size", "How many of you (default 6)")
                                .setRequiredRange(2, 12))
                        .addOption(STRING, "note", "Time, requirements, anything else")
                        .addOption(BOOLEAN, "voice", "Open a private voice channel (default true)")
                        .setGuildOnly(true),

                Commands.slash("quests", "Your quest steps, with progress on each"),

                Commands.slash("news", "The latest articles from Bungie.net"),

                Commands.slash("profile", "Look up a player's lifetime PvE stats")
                        .addOption(STRING, "name", "Bungie name, e.g. Guardian#1234", true),

                Commands.slash("watch", "Announce the weekly reset and Xûr's arrival in this channel")
                        .setGuildOnly(true)
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_CHANNEL)),

                Commands.slash("unwatch", "Stop announcing in this server")
                        .setGuildOnly(true)
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_CHANNEL)),

                // ---- the ghost ----

                Commands.slash("link", "Connect your Destiny account so I can manage your gear")
                        .addOption(STRING, "code", "The code from the Bungie callback page"),

                Commands.slash("unlink", "Disconnect your Destiny account"),

                Commands.slash("activity", "What you're doing right now"),

                Commands.slash("character", "Your characters, and which one I'm acting on")
                        .addOptions(new OptionData(STRING, "class", "Pin one, or go back to automatic")
                                .addChoice("Titan", "Titan")
                                .addChoice("Hunter", "Hunter")
                                .addChoice("Warlock", "Warlock")
                                .addChoice("Follow my last login", "auto")),

                Commands.slash("ranks", "Your reputation ranks and season pass"),

                Commands.slash("checklist", "What's still outstanding this week"),

                Commands.slash("vault", "How full your vault is"),

                Commands.slash("seals", "The titles you've earned"),

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

                Commands.slash("postmaster", "What's waiting in your postmaster"),

                Commands.slash("xur", "What Xûr is selling this weekend"),

                Commands.slash("vendor", "What a vendor is selling you")
                        .addOption(STRING, "name", "Vendor name, e.g. Banshee-44", true),

                Commands.slash("recent", "The last few activities played")
                        .addOptions(new OptionData(INTEGER, "count", "How many to show")
                                .setRequiredRange(1, 15))
                        .addOptions(player()),

                Commands.slash("pgcr", "The full breakdown of an activity")
                        .addOption(STRING, "instance", "Instance id — defaults to the last activity")
                        .addOptions(player()),

                Commands.slash("clears", "How many times an activity has been completed")
                        .addOption(STRING, "activity", "Activity name, e.g. Vault of Glass", true)
                        .addOptions(player()),

                Commands.slash("weapon", "Kills with one weapon")
                        .addOption(STRING, "name", "Weapon name", true)
                        .addOptions(player()),

                Commands.slash("topweapons", "The most used weapons on an account")
                        .addOptions(new OptionData(INTEGER, "count", "How many to show")
                                .setRequiredRange(1, 20))
                        .addOptions(player()),

                Commands.slash("destination", "What's available on a destination")
                        .addOption(STRING, "name", "e.g. The Moon — omit for the list"),

                Commands.slash("bounties", "Your bounties and quest steps, with progress"),

                Commands.slash("fireteam", "Who you're playing with right now"),

                Commands.slash("currencies", "Glimmer and the rest"),

                Commands.slash("lock", "Lock or unlock every item in a saved set")
                        .addOptions(loadoutName().setName("set").setDescription("Which set")
                                .setRequired(true))
                        .addOption(BOOLEAN, "locked", "True to lock, false to unlock"),

                Commands.slash("snapshot", "Save your current gear into an in-game loadout slot")
                        .addOptions(new OptionData(INTEGER, "slot", "Which of the 20 slots", true)
                                .setRequiredRange(1, 20))
        ).queue();

        // Downloads in the background; commands work meanwhile via per-hash lookups.
        bot.names.loadInBackground();

        // The announcement watchers poll from startup and post to whichever channels have
        // opted in, so /watch is a registration rather than something that starts a thread.
        bot.announcer = new Announcer(jda);
        new BackgroundThread(bot.announcer, bot.weekly).start();
        new XurWatcher(bot.bungie, bot.vendors, bot.announcer).start();
        new GhostWatcher(jda, bot.ghost, bot.store).start();
    }

    /** A loadout-name option that completes from whatever the caller has saved. */
    private static OptionData loadoutName() {
        return new OptionData(STRING, "name", "Which set", true).setAutoComplete(true);
    }

    /**
     * The optional "someone else" option on the stats commands.
     *
     * <p>Called {@code player} rather than {@code name} so it cannot be confused with the
     * loadout and item options that already use that word — including by the autocomplete
     * handler, which keys off the option name.
     */
    private static OptionData player() {
        return new OptionData(STRING, "player", "Bungie name, e.g. Guardian#1234 — defaults to you");
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event)
    {
        String discordId = event.getUser().getId();
        switch (event.getName())
        {
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
                destiny(event, false, weekly::rotators);
                break;
            case "clan":
                destiny(event, false, () -> clan.clan(discordId));
                break;
            case "lfg":
                if (guildOnly(event)) return;
                lfg(event, discordId);
                break;
            case "quests":
                destiny(event, false, () -> ghost.quests(discordId));
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
            case "unwatch":
                if (guildOnly(event)) return;
                event.reply(announcer != null && announcer.unwatch(event.getGuild().getId())
                                ? "Stopped announcing here."
                                : "I wasn't announcing in this server.")
                        .setEphemeral(true).queue();
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
            case "character":
                destiny(event, false, () -> ghost.character(discordId,
                        event.getOption("class", null, OptionMapping::getAsString)));
                break;
            case "ranks":
                destiny(event, false, () -> progress.ranks(discordId));
                break;
            case "checklist":
                destiny(event, false, () -> progress.checklist(discordId));
                break;
            case "vault":
                destiny(event, false, () -> ghost.vault(discordId));
                break;
            case "seals":
                destiny(event, false, () -> seals.seals(discordId));
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
            case "postmaster":
                postmaster(event, discordId);
                break;
            case "xur":
                destiny(event, false, () -> vendors.xur(discordId));
                break;
            case "vendor":
                destiny(event, false, () ->
                        vendors.vendor(discordId, event.getOption("name").getAsString()));
                break;
            case "recent":
                destiny(event, false, () -> stats.recent(discordId, player(event),
                        event.getOption("count", 5, OptionMapping::getAsInt)));
                break;
            case "pgcr":
                destiny(event, false, () -> stats.pgcr(discordId, player(event),
                        event.getOption("instance", null, OptionMapping::getAsString)));
                break;
            case "clears":
                destiny(event, false, () -> stats.clears(discordId, player(event),
                        event.getOption("activity").getAsString()));
                break;
            case "weapon":
                destiny(event, false, () -> stats.weapon(discordId, player(event),
                        event.getOption("name").getAsString()));
                break;
            case "topweapons":
                destiny(event, false, () -> stats.topWeapons(discordId, player(event),
                        event.getOption("count", 10, OptionMapping::getAsInt)));
                break;
            case "destination":
                destiny(event, false, () -> world.destination(discordId,
                        event.getOption("name", null, OptionMapping::getAsString)));
                break;
            case "bounties":
                destiny(event, false, () -> ghost.bounties(discordId));
                break;
            case "fireteam":
                destiny(event, false, () -> ghost.fireteam(discordId));
                break;
            case "currencies":
                destiny(event, true, () -> ghost.currencies(discordId));
                break;
            case "lock":
                destiny(event, false, () -> ghost.lockSet(discordId,
                        event.getOption("set").getAsString(),
                        event.getOption("locked", true, OptionMapping::getAsBoolean)));
                break;
            default:
                event.reply("I can't handle that command right now :(").setEphemeral(true).queue();
        }
    }

    /** The Bungie name someone asked about, or null when they meant themselves. */
    private static String player(SlashCommandInteractionEvent event)
    {
        return event.getOption("player", null, OptionMapping::getAsString);
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
        // /equip and /loadout call it "name", /map calls it "loadout", /lock calls it "set".
        String option = event.getFocusedOption().getName();
        if (!option.equals("name") && !option.equals("loadout") && !option.equals("set"))
            return;
        // /item and /weapon also take a "name", but theirs is an item, not a saved set.
        if (event.getName().equals("item") || event.getName().equals("weapon"))
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
     * Handles {@code !equip kingsfall} style commands when prefix commands are enabled.
     *
     * <p>Every one takes an explicit verb. Equips always queue if the game refuses, because
     * naming a set is a statement of intent about what you are heading into rather than a
     * comment on where you are.
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
        if (!KNOWN_VERBS.contains(verb))
            return;
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
                    case "postmaster" -> null;   // handled below, it carries a menu
                    case "xur" -> vendors.xur(discordId);
                    case "vendor" -> vendors.vendor(discordId, argument);
                    // The prefix forms are always about the caller; looking someone else up
                    // is a slash-command option, where it can be named and described.
                    case "recent" -> stats.recent(discordId, null, number(argument, 5, 15));
                    case "pgcr" -> stats.pgcr(discordId, null, argument.isEmpty() ? null : argument);
                    case "clears" -> stats.clears(discordId, null, argument);
                    case "weapon" -> stats.weapon(discordId, null, argument);
                    case "topweapons" -> stats.topWeapons(discordId, null, number(argument, 10, 20));
                    case "destination", "destinations" -> world.destination(discordId, argument);
                    case "bounties" -> ghost.bounties(discordId);
                    case "quests" -> ghost.quests(discordId);
                    case "clan" -> clan.clan(discordId);
                    case "weekly", "rotators" -> weekly.rotators();
                    case "fireteam" -> ghost.fireteam(discordId);
                    case "currencies" -> ghost.currencies(discordId);
                    case "character", "characters" -> ghost.character(discordId,
                            argument.isEmpty() ? null : argument);
                    case "ranks" -> progress.ranks(discordId);
                    case "checklist", "todo" -> progress.checklist(discordId);
                    case "vault" -> ghost.vault(discordId);
                    case "seals", "titles" -> seals.seals(discordId);
                    case "lock" -> ghost.lockSet(discordId, argument, true);
                    case "unlock" -> ghost.lockSet(discordId, argument, false);
                    // No bare "!name" shorthand: every command is an explicit verb, so the
                    // bot never has to guess whether "!roll" was meant for it or another bot.
                    default -> null;
                };
                if (verb.equals("postmaster"))
                {
                    Ghost.PostmasterView view = ghost.postmaster(discordId);
                    var message = channel.sendMessageEmbeds(view.embed());
                    StringSelectMenu menu = pullMenu(discordId, view);
                    if (menu != null)
                        message = message.setComponents(ActionRow.of(menu));
                    message.queue();
                    return;
                }
                if (embed == null)
                    return;
                channel.sendMessageEmbeds(embed).queue();
            }
            catch (Exception e)
            {
                channel.sendMessageEmbeds(Destiny.error(e.getMessage())).queue();
            }
        });
    }

    /**
     * The prefix words this bot answers to.
     *
     * <p>Anything else is ignored outright. Without an explicit list, a bare {@code !name}
     * shorthand means guessing whether {@code !roll} was aimed here or at another bot in the
     * channel, and guessing wrong in either direction is worse than requiring a verb.
     */
    private static final java.util.Set<String> KNOWN_VERBS = java.util.Set.of(
            "loadout", "loadouts", "set", "save", "equip", "activityloadout", "map",
            "activity", "artifact", "postmaster", "xur", "vendor", "recent", "pgcr",
            "clears", "weapon", "topweapons", "bounties", "fireteam", "currencies",
            "lock", "unlock", "destination", "destinations", "quests", "clan",
            "weekly", "rotators", "character", "characters", "ranks", "checklist",
            "todo", "vault", "seals", "titles");

    /** Parses a count from a prefix argument, falling back when it is missing or nonsense. */
    private static int number(String argument, int fallback, int limit)
    {
        try
        {
            return Math.max(1, Math.min(limit, Integer.parseInt(argument.trim())));
        }
        catch (RuntimeException e)
        {
            return fallback;
        }
    }

    /**
     * Answers {@code /postmaster} with the contents plus a menu to pull things back out.
     *
     * <p>Sent as a select rather than a row of buttons because the postmaster holds 21
     * items, which is more than Discord's five-per-row buttons handle tidily and exactly
     * within a select's 25-option limit.
     */
    private void postmaster(SlashCommandInteractionEvent event, String discordId)
    {
        event.deferReply(false).queue();
        Thread.ofVirtual().start(() -> {
            try
            {
                Ghost.PostmasterView view = ghost.postmaster(discordId);
                var reply = event.getHook().sendMessageEmbeds(view.embed());
                StringSelectMenu menu = pullMenu(discordId, view);
                if (menu != null)
                    reply = reply.setComponents(ActionRow.of(menu));
                reply.queue();
            }
            catch (Exception e)
            {
                String message = e.getMessage();
                event.getHook().sendMessageEmbeds(Destiny.error(
                        message == null || message.isBlank() ? "Something went wrong." : message)).queue();
            }
        });
    }

    /** Builds the pull menu, or null when there is nothing to offer. */
    private StringSelectMenu pullMenu(String discordId, Ghost.PostmasterView view)
    {
        if (view.items().isEmpty())
            return null;

        StringSelectMenu.Builder menu = StringSelectMenu.create(discordId + ":pm")
                .setPlaceholder("Pull something out");
        for (Ghost.PostmasterItem item : view.items())
        {
            // Discord caps a select at 25 options; the postmaster holds 21, so this only
            // bites if Bungie ever raises the limit.
            if (menu.getOptions().size() == 25)
                break;
            String value = item.itemHash() + "|"
                    + (item.instanceId() == null ? "-" : item.instanceId()) + "|"
                    + item.quantity() + "|" + (item.sideEffects() ? "1" : "0");
            menu.addOption(trim(item.label(), 100), value,
                    item.sideEffects() ? "Pulling this may destroy something" : null);
        }
        return menu.build();
    }

    /** Handles a choice from the postmaster menu. */
    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event)
    {
        String[] id = event.getComponentId().split(":");
        if (id.length < 2 || !id[1].equals("pm"))
            return;
        if (!id[0].equals(event.getUser().getId()))
        {
            event.reply("That's not your postmaster.").setEphemeral(true).queue();
            return;
        }

        String[] parts = event.getValues().get(0).split("\\|");
        long itemHash = Long.parseLong(parts[0]);
        String instanceId = parts[1].equals("-") ? null : parts[1];
        int quantity = Integer.parseInt(parts[2]);
        boolean sideEffects = parts[3].equals("1");

        if (sideEffects)
        {
            // Bungie flags these as potentially destructive, so make it a deliberate choice
            // rather than something a stray click does.
            event.reply("Bungie flags pulling **" + names.itemName(itemHash)
                            + "** as something that could destroy an item. Pull it anyway?")
                    .addActionRow(
                            Button.secondary(id[0] + ":delete", "Cancel"),
                            Button.danger(id[0] + ":pmpull:" + itemHash + ":"
                                    + (instanceId == null ? "-" : instanceId) + ":" + quantity,
                                    "Pull it"))
                    .setEphemeral(true)
                    .queue();
            return;
        }

        event.deferReply(false).queue();
        pull(event.getHook(), event.getUser().getId(), itemHash, instanceId, quantity);
    }

    /** Runs a postmaster pull off the event thread and reports the outcome. */
    private void pull(InteractionHook hook, String discordId, long itemHash,
                      String instanceId, int quantity)
    {
        Thread.ofVirtual().start(() -> {
            try
            {
                hook.sendMessageEmbeds(
                        ghost.pullFromPostmaster(discordId, itemHash, instanceId, quantity)).queue();
            }
            catch (Exception e)
            {
                String message = e.getMessage();
                hook.sendMessageEmbeds(Destiny.error(
                        message == null || message.isBlank() ? "Something went wrong." : message)).queue();
            }
        });
    }

    private static String trim(String value, int limit)
    {
        return value.length() <= limit ? value : value.substring(0, limit - 1) + "\u2026";
    }

    /**
     * Posts a fireteam listing.
     *
     * <p>Deferred because building it reads the poster's power and clear count from Bungie,
     * which is two calls and well past the three seconds JDA allows for an acknowledgement.
     */
    private void lfg(SlashCommandInteractionEvent event, String discordId)
    {
        event.deferReply(false).queue();
        Guild guild = event.getGuild();
        String memberName = event.getUser().getEffectiveName();
        String activity = event.getOption("activity").getAsString();
        int size = event.getOption("size", 6, OptionMapping::getAsInt);
        String note = event.getOption("note", null, OptionMapping::getAsString);
        boolean wantVoice = event.getOption("voice", true, OptionMapping::getAsBoolean);

        Thread.ofVirtual().start(() -> {
            Lfg.Listing listing = lfg.create(discordId, memberName, activity, size, note);
            event.getHook()
                    .sendMessageEmbeds(lfg.render(listing))
                    .setComponents(lfg.controls(listing))
                    .queue(message -> {
                        lfg.attach(listing.id, guild.getId(), message.getChannelId(), message.getId());
                        if (wantVoice)
                            openVoice(guild, listing, message.getChannelId());
                    });
        });
    }

    /**
     * Opens a private voice channel for a listing.
     *
     * <p>Private by denying the everyone-role both visibility and connect, then granting them
     * back per member. Failure here is not fatal: the listing is the point and a server that
     * has not given the bot Manage Channels should still get a working board, so this reports
     * quietly rather than breaking the post.
     */
    private void openVoice(Guild guild, Lfg.Listing listing, String textChannelId)
    {
        if (!guild.getSelfMember().hasPermission(Permission.MANAGE_CHANNEL))
        {
            System.err.println("No Manage Channels permission in " + guild.getId()
                    + "; skipping the voice channel.");
            return;
        }

        // Sit it beside the channel the post went to, so it appears where people are looking.
        Category parent = guild.getTextChannelById(textChannelId) == null ? null
                : guild.getTextChannelById(textChannelId).getParentCategory();

        var action = guild.createVoiceChannel(voiceName(listing))
                .addPermissionOverride(guild.getPublicRole(), null,
                        EnumSet.of(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT))
                .addPermissionOverride(guild.getSelfMember(),
                        EnumSet.of(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT,
                                Permission.MANAGE_CHANNEL, Permission.MANAGE_PERMISSIONS), null);
        if (parent != null)
            action = action.setParent(parent);

        action.queue(channel -> {
            lfg.attachVoice(listing.id, channel.getId());
            // Everyone already on the listing — at creation that is just the owner.
            for (Lfg.Member member : listing.members)
                grantVoice(guild, channel.getId(), member.discordId);
            refresh(guild, listing);
        }, error -> System.err.println("Could not create a voice channel: " + error.getMessage()));
    }

    private static String voiceName(Lfg.Listing listing)
    {
        String name = listing.activity;
        // Discord truncates long channel names; keep it recognisable instead.
        return (name.length() > 24 ? name.substring(0, 24) : name) + " · lfg";
    }

    private void grantVoice(Guild guild, String voiceChannelId, String discordId)
    {
        VoiceChannel channel = guild.getVoiceChannelById(voiceChannelId);
        if (channel == null)
            return;
        guild.retrieveMemberById(discordId).queue(
                member -> channel.upsertPermissionOverride(member)
                        .grant(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT)
                        .queue(null, e -> System.err.println("Voice grant failed: " + e.getMessage())),
                e -> System.err.println("Unknown member " + discordId));
    }

    /** Removes someone's access, and disconnects them if they are sitting in there. */
    private void revokeVoice(Guild guild, String voiceChannelId, String discordId)
    {
        VoiceChannel channel = guild.getVoiceChannelById(voiceChannelId);
        if (channel == null)
            return;
        guild.retrieveMemberById(discordId).queue(member -> {
            PermissionOverride override = channel.getPermissionOverride(member);
            if (override != null)
                override.delete().queue(null, e -> { });
            // Leaving the listing should not leave you sitting in its voice channel.
            if (member.getVoiceState() != null && member.getVoiceState().getChannel() != null
                    && voiceChannelId.equals(member.getVoiceState().getChannel().getId()))
                guild.kickVoiceMember(member).queue(null, e -> { });
        }, e -> { });
    }

    private void closeVoice(Guild guild, Lfg.Listing listing)
    {
        if (listing.voiceChannelId == null)
            return;
        VoiceChannel channel = guild.getVoiceChannelById(listing.voiceChannelId);
        if (channel != null)
            channel.delete().reason("LFG listing closed").queue(null, e -> { });
    }

    /** Redraws a listing's message in place. */
    private void refresh(Guild guild, Lfg.Listing listing)
    {
        if (listing.channelId == null || listing.messageId == null)
            return;
        TextChannel channel = guild.getTextChannelById(listing.channelId);
        if (channel == null)
            return;
        channel.editMessageEmbedsById(listing.messageId, lfg.render(listing))
                .setComponents(lfg.controls(listing))
                .queue(null, e -> { });
    }

    /** Handles the Join, Leave and Close buttons on a listing. */
    private void onLfgButton(ButtonInteractionEvent event)
    {
        String[] parts = event.getComponentId().split(":");
        if (parts.length < 3)
            return;
        String id = parts[1];
        String action = parts[2];
        String discordId = event.getUser().getId();
        Guild guild = event.getGuild();

        Lfg.Listing listing = lfg.get(id);
        if (listing == null || guild == null)
        {
            event.reply("That listing is gone.").setEphemeral(true).queue();
            return;
        }

        // Joining reads two Bungie endpoints for the newcomer, so acknowledge first.
        event.deferEdit().queue();
        Thread.ofVirtual().start(() -> {
            String problem = switch (action)
            {
                case "join" -> lfg.join(id, discordId, event.getUser().getEffectiveName());
                case "leave" -> lfg.leave(id, discordId);
                case "close" -> lfg.close(id, discordId);
                default -> "Unknown action.";
            };

            if (problem != null)
            {
                event.getHook().sendMessage(problem).setEphemeral(true).queue();
                return;
            }

            if (listing.voiceChannelId != null)
            {
                if (action.equals("join"))
                    grantVoice(guild, listing.voiceChannelId, discordId);
                else if (action.equals("leave"))
                    revokeVoice(guild, listing.voiceChannelId, discordId);
            }
            if (listing.closed)
                closeVoice(guild, listing);

            event.getHook().editOriginalEmbeds(lfg.render(listing))
                    .setComponents(lfg.controls(listing))
                    .queue(null, e -> { });
        });
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
        if (event.getComponentId().startsWith("lfg:"))
        {
            // LFG buttons are for anyone in the channel, not just whoever posted, so they
            // are handled before the owner check below.
            onLfgButton(event);
            return;
        }

        String[] id = event.getComponentId().split(":"); // this is the custom id we specified in our button
        String authorId = id[0];
        String type = id[1];
        // Check that the button is for the user that clicked it, otherwise just ignore the event (let interaction fail)
        if (!authorId.equals(event.getUser().getId()))
            return;
        event.deferEdit().queue(); // acknowledge the button was clicked, otherwise the interaction will fail

        switch (type)
        {
            case "delete":
                // The cancel half of the postmaster's "this might destroy something" prompt.
                event.getHook().deleteOriginal().queue();
                break;
            case "pmpull":
                // Confirmed pull of an item Bungie flagged as destructive.
                pull(event.getHook(), event.getUser().getId(), Long.parseLong(id[2]),
                        id[3].equals("-") ? null : id[3], Integer.parseInt(id[4]));
        }
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

    /**
     * Points this server's announcements at the channel the command was used in.
     *
     * <p>Registration only — the watchers themselves run from startup. One channel per
     * server, so running this somewhere else moves the announcements rather than adding a
     * second copy.
     */
    private void watch(SlashCommandInteractionEvent event)
    {
        if (!event.getChannel().getType().isMessage() || event.getChannelType().isThread())
        {
            event.reply("Use this in a normal text channel.").setEphemeral(true).queue();
            return;
        }
        if (announcer == null)
        {
            event.reply("Still starting up — try again in a moment.").setEphemeral(true).queue();
            return;
        }

        announcer.watch(event.getGuild().getId(), event.getChannel().getId());
        event.reply("Announcing here: the weekly reset, and when Xûr arrives."
                        + "\n\n`/unwatch` to stop.")
                .setEphemeral(true)
                .queue();
    }
}
