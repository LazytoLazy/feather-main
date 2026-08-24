package com.feathermc.economy;

import com.feathermc.FeatherMC;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A small, self-contained virtual-currency ledger for "eggs".
 *
 * Vault only allows one plugin to register the primary Economy service at a
 * time, and that slot should stay free for the server's main currency
 * (used by the Auction House and Order System via Vault). Eggs are a
 * second, separate currency FeatherMC manages itself - same deposit/
 * withdraw/balance shape you'd expect from Vault, just not registered
 * through it. Balances persist to plugins/FeatherMC/eggs.yml.
 */
public class EggEconomy {

    private final FeatherMC plugin;
    private final File file;
    private final YamlConfiguration data;
    private final Map<UUID, Double> balances = new HashMap<>();

    public EggEconomy(FeatherMC plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "eggs.yml");
        if (!file.exists()) {
            try {
                file.getParentFile().mkdirs();
                file.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create eggs.yml: " + e.getMessage());
            }
        }
        this.data = YamlConfiguration.loadConfiguration(file);
        load();
    }

    private void load() {
        balances.clear();
        if (data.getConfigurationSection("balances") == null) return;
        for (String key : data.getConfigurationSection("balances").getKeys(false)) {
            balances.put(UUID.fromString(key), data.getDouble("balances." + key));
        }
    }

    public void save() {
        for (Map.Entry<UUID, Double> e : balances.entrySet()) {
            data.set("balances." + e.getKey(), e.getValue());
        }
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save eggs.yml: " + e.getMessage());
        }
    }

    public double getBalance(UUID id) {
        return balances.computeIfAbsent(id, k -> (double) plugin.getConfig().getInt("eggs.starting-balance", 0));
    }

    public double getBalance(OfflinePlayer p) {
        return getBalance(p.getUniqueId());
    }

    public boolean has(OfflinePlayer p, double amount) {
        return getBalance(p) >= amount;
    }

    public void deposit(OfflinePlayer p, double amount) {
        balances.put(p.getUniqueId(), getBalance(p) + amount);
        save();
    }

    /** Returns false (and makes no change) if the player can't cover the withdrawal. */
    public boolean withdraw(OfflinePlayer p, double amount) {
        double bal = getBalance(p);
        if (bal < amount) return false;
        balances.put(p.getUniqueId(), bal - amount);
        save();
        return true;
    }

    public void setBalance(OfflinePlayer p, double amount) {
        balances.put(p.getUniqueId(), amount);
        save();
    }

    public String symbol() {
        return plugin.getConfig().getString("eggs.symbol", "eggs");
    }

    public String format(double amount) {
        if (amount == Math.floor(amount)) {
            return (long) amount + " " + symbol();
        }
        return String.format("%.2f %s", amount, symbol());
    }
}
