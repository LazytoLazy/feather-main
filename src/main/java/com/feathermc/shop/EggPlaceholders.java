package com.feathermc.shop;

import com.feathermc.FeatherMC;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.text.NumberFormat;

/**
 * Registers:
 *   %feathermc_eggs%            - raw egg balance (number only)
 *   %feathermc_eggs_formatted%  - comma-formatted egg balance with symbol
 */
public class EggPlaceholders extends PlaceholderExpansion {

    private final FeatherMC plugin;

    public EggPlaceholders(FeatherMC plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "feathermc";
    }

    @Override
    public @NotNull String getAuthor() {
        return "YourName";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return "";

        if (params.equalsIgnoreCase("eggs")) {
            return String.valueOf((long) plugin.getEggEconomy().getBalance(player));
        }
        if (params.equalsIgnoreCase("eggs_formatted")) {
            return NumberFormat.getInstance().format((long) plugin.getEggEconomy().getBalance(player))
                    + " " + plugin.getEggEconomy().symbol();
        }
        return null;
    }
}
