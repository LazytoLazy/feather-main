package com.feathermc.utils;

import com.feathermc.FeatherMC;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Loads every player-facing message from messages.yml so server owners can
 * reword/translate anything without touching Java. Keys are dotted paths,
 * e.g. "warp.not-found". Placeholders are passed as alternating
 * key/value pairs: msg("warp.created", "warp", warpName).
 */
public class MessageManager {

    private final FeatherMC plugin;
    private final File file;
    private FileConfiguration messages;

    public MessageManager(FeatherMC plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "messages.yml");
        load();
    }

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        messages = YamlConfiguration.loadConfiguration(file);

        // merge in any new keys shipped in newer jar versions without
        // clobbering the server owner's edits to existing keys
        InputStream defStream = plugin.getResource("messages.yml");
        if (defStream != null) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defStream, StandardCharsets.UTF_8));
            messages.setDefaults(defaults);
            messages.options().copyDefaults(true);
            save();
        }
    }

    public void save() {
        try {
            messages.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save messages.yml: " + e.getMessage());
        }
    }

    private String raw(String key) {
        String value = messages.getString(key);
        if (value == null) {
            return "&cMissing message: " + key;
        }
        return value;
    }

    /** Returns the raw legacy-formatted string with placeholders substituted (no color parsing). */
    public String rawFormatted(String key, String... placeholders) {
        String value = raw(key);
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            value = value.replace("%" + placeholders[i] + "%", placeholders[i + 1]);
        }
        return value;
    }

    public Component get(String key, String... placeholders) {
        return ItemBuilder.colorize(rawFormatted(key, placeholders));
    }

    public void send(CommandSender to, String key, String... placeholders) {
        to.sendMessage(get(key, placeholders));
    }
}
