package com.feathermc.economy;

import com.feathermc.FeatherMC;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Handles the "/feathermc eggs" subcommand:
 *   /feathermc eggs                        - check your own balance
 *   /feathermc eggs <player>                - check someone else's balance
 *   /feathermc eggs pay <player> <amount>   - send eggs to another player
 *   /feathermc eggs give <player> <amount>  - admin: add eggs   (feathermc.eggs.admin)
 *   /feathermc eggs take <player> <amount>  - admin: remove eggs
 *   /feathermc eggs set <player> <amount>   - admin: set balance
 */
public class EggsCommandHandler {

    private final FeatherMC plugin;

    public EggsCommandHandler(FeatherMC plugin) {
        this.plugin = plugin;
    }

    public void handle(CommandSender sender, String[] args) {
        if (!sender.hasPermission("feathermc.eggs.use")) {
            plugin.getMessages().send(sender, "general.no-permission");
            return;
        }

        if (args.length == 0) {
            if (!(sender instanceof Player p)) {
                plugin.getMessages().send(sender, "general.player-only");
                return;
            }
            plugin.getMessages().send(p, "eggs.balance-self",
                    "balance", plugin.getEggEconomy().format(plugin.getEggEconomy().getBalance(p)));
            return;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "pay" -> {
                if (!(sender instanceof Player p)) {
                    plugin.getMessages().send(sender, "general.player-only");
                    return;
                }
                if (args.length < 3) {
                    plugin.getMessages().send(p, "eggs.usage-pay");
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    plugin.getMessages().send(p, "general.player-not-found");
                    return;
                }
                double amount = parseAmount(p, args[2]);
                if (Double.isNaN(amount)) return;
                if (!plugin.getEggEconomy().has(p, amount)) {
                    plugin.getMessages().send(p, "eggs.not-enough");
                    return;
                }
                plugin.getEggEconomy().withdraw(p, amount);
                plugin.getEggEconomy().deposit(target, amount);
                plugin.getMessages().send(p, "eggs.paid", "amount", fmt(amount), "player", target.getName());
                plugin.getMessages().send(target, "eggs.received", "amount", fmt(amount), "player", p.getName());
            }
            case "give", "take", "set" -> {
                if (!sender.hasPermission("feathermc.eggs.admin")) {
                    plugin.getMessages().send(sender, "general.no-permission");
                    return;
                }
                if (args.length < 3) {
                    plugin.getMessages().send(sender, "eggs.usage-admin");
                    return;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                double amount = parseAmount(sender, args[2]);
                if (Double.isNaN(amount)) return;

                switch (sub) {
                    case "give" -> {
                        plugin.getEggEconomy().deposit(target, amount);
                        plugin.getMessages().send(sender, "eggs.admin-give", "amount", fmt(amount), "player", args[1]);
                    }
                    case "take" -> {
                        plugin.getEggEconomy().withdraw(target, amount);
                        plugin.getMessages().send(sender, "eggs.admin-take", "amount", fmt(amount), "player", args[1]);
                    }
                    case "set" -> {
                        plugin.getEggEconomy().setBalance(target, amount);
                        plugin.getMessages().send(sender, "eggs.admin-set", "amount", fmt(amount), "player", args[1]);
                    }
                }
            }
            default -> {
                // treat the argument as a player name to check their balance
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
                plugin.getMessages().send(sender, "eggs.balance-other",
                        "player", args[0],
                        "balance", plugin.getEggEconomy().format(plugin.getEggEconomy().getBalance(target)));
            }
        }
    }

    private String fmt(double amount) {
        return amount == Math.floor(amount) ? String.valueOf((long) amount) : String.valueOf(amount);
    }

    private double parseAmount(CommandSender sender, String raw) {
        try {
            double amount = Double.parseDouble(raw);
            if (amount <= 0) {
                plugin.getMessages().send(sender, "eggs.invalid-amount");
                return Double.NaN;
            }
            return amount;
        } catch (NumberFormatException e) {
            plugin.getMessages().send(sender, "general.invalid-number");
            return Double.NaN;
        }
    }
}
