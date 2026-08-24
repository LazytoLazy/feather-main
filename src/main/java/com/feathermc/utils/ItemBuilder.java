package com.feathermc.utils;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.List;

/**
 * Small fluent builder for GUI items. Uses legacy '&' color codes from config
 * and converts them to Adventure Components under the hood.
 */
public class ItemBuilder {

    private final ItemStack stack;
    private final ItemMeta meta;

    public ItemBuilder(Material material) {
        this(material, 1);
    }

    public ItemBuilder(Material material, int amount) {
        this.stack = new ItemStack(material, amount);
        this.meta = stack.getItemMeta();
    }

    public static Component colorize(String legacy) {
        return LegacyComponentSerializer.legacyAmpersand()
                .deserialize(legacy)
                .decoration(TextDecoration.ITALIC, false);
    }

    public ItemBuilder name(String legacyName) {
        if (legacyName != null) {
            meta.displayName(colorize(legacyName));
        }
        return this;
    }

    public ItemBuilder lore(List<String> legacyLore) {
        if (legacyLore == null) return this;
        List<Component> lore = new ArrayList<>();
        for (String line : legacyLore) {
            lore.add(colorize(line));
        }
        meta.lore(lore);
        return this;
    }

    public ItemBuilder flags(ItemFlag... flags) {
        meta.addItemFlags(flags);
        return this;
    }

    public ItemBuilder tag(NamespacedKey key, String value) {
        meta.getPersistentDataContainer().set(key, org.bukkit.persistence.PersistentDataType.STRING, value);
        return this;
    }

    public ItemStack build() {
        stack.setItemMeta(meta);
        return stack;
    }
}
