package com.feathermc.command;

import com.feathermc.FeatherMC;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Single entry point for every FeatherMC feature: "/feathermc <sub> [args]".
 * Routes to each system's own handle(sender, args) method.
 */
public class FeatherMCCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "punish", "warp", "shop", "duel", "ah", "order", "playtime", "eggs", "reload", "help"
    );

    private final FeatherMC plugin;

    public FeatherMCCommand(FeatherMC plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        String[] rest = Arrays.copyOfRange(args, 1, args.length);

        switch (sub) {
            case "punish" -> plugin.getPunishSystem().handle(sender, rest);
            case "warp", "warps" -> plugin.getWarpSystem().handle(sender, rest);
            case "shop", "eggshop" -> plugin.getShopSystem().handle(sender, rest);
            case "duel", "duels" -> plugin.getDuelsManager().handle(sender, rest);
            case "ah", "auctionhouse" -> {
                if (plugin.getAuctionSystem() == null) {
                    plugin.getMessages().send(sender, "auctionhouse.no-economy");
                } else {
                    plugin.getAuctionSystem().handle(sender, rest);
                }
            }
            case "order", "orders" -> {
                if (plugin.getOrderSystem() == null) {
                    plugin.getMessages().send(sender, "ordersystem.no-economy");
                } else {
                    plugin.getOrderSystem().handle(sender, rest);
                }
            }
            case "playtime" -> plugin.getPlaytimeSystem().handle(sender, rest);
            case "eggs" -> plugin.getEggsCommandHandler().handle(sender, rest);
            case "reload" -> handleReload(sender);
            case "help" -> sendHelp(sender);
            default -> plugin.getMessages().send(sender, "general.unknown-subcommand");
        }
        return true;
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("feathermc.reload")) {
            plugin.getMessages().send(sender, "general.no-permission");
            return;
        }
        plugin.reloadConfig();
        plugin.getMessages().load();
        plugin.getMessages().send(sender, "general.reloaded");
    }

    private void sendHelp(CommandSender sender) {
        plugin.getMessages().send(sender, "help.header");
        record Entry(String sub, String desc) {}
        List<Entry> entries = List.of(
                new Entry("punish <player>", "Open the punish GUI"),
                new Entry("warp [name|set <name>|del <name>]", "Warps"),
                new Entry("shop", "Egg shop"),
                new Entry("duel <player|accept|deny|cancel|arenas>", "Duels"),
                new Entry("ah", "Auction house"),
                new Entry("order", "Sell orders"),
                new Entry("playtime", "Playtime rewards"),
                new Entry("eggs [pay|give|take|set]", "Egg balance"),
                new Entry("reload", "Reload config & messages")
        );
        for (Entry e : entries) {
            plugin.getMessages().send(sender, "help.line", "sub", e.sub(), "description", e.desc());
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2) {
            List<String> options = switch (args[0].toLowerCase()) {
                case "warp" -> List.of("set", "del");
                case "duel" -> List.of("accept", "deny", "cancel", "arenas", "pos1", "pos2", "generate");
                case "eggs" -> List.of("pay", "give", "take", "set");
                default -> List.of();
            };
            if (!options.isEmpty()) {
                return options.stream().filter(s -> s.startsWith(args[1].toLowerCase())).collect(Collectors.toList());
            }
            if (args[0].equalsIgnoreCase("punish") || args[0].equalsIgnoreCase("duel")) {
                return null; // let the client suggest online player names
            }
        }
        return new ArrayList<>();
    }
}
