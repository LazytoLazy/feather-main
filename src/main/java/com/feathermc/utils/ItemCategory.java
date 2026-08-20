package com.feathermc.utils;

import org.bukkit.Material;

/**
 * Rough item categories used to power the Auction House / Order System
 * category filter (the "hopper" button in the DonutSMP-style GUI).
 */
public enum ItemCategory {
    ALL("&f&lAll Items"),
    BLOCKS("&7&lBlocks"),
    WEAPONS("&c&lWeapons"),
    TOOLS("&6&lTools"),
    ARMOR("&b&lArmor"),
    FOOD("&a&lFood"),
    MISC("&d&lMisc");

    private final String display;

    ItemCategory(String display) {
        this.display = display;
    }

    public String display() {
        return display;
    }

    public ItemCategory next() {
        ItemCategory[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }

    public static ItemCategory of(Material m) {
        String name = m.name();
        if (name.endsWith("_SWORD") || name.equals("BOW") || name.equals("CROSSBOW")
                || name.equals("TRIDENT") || name.equals("MACE")) {
            return WEAPONS;
        }
        if (name.endsWith("_PICKAXE") || name.endsWith("_SHOVEL") || name.endsWith("_HOE")
                || name.endsWith("_AXE") || name.equals("FISHING_ROD") || name.equals("SHEARS")
                || name.equals("FLINT_AND_STEEL")) {
            return TOOLS;
        }
        if (name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE") || name.endsWith("_LEGGINGS")
                || name.endsWith("_BOOTS") || name.equals("SHIELD") || name.equals("ELYTRA")
                || name.endsWith("_HORSE_ARMOR") || name.equals("TURTLE_HELMET")) {
            return ARMOR;
        }
        if (m.isEdible()) {
            return FOOD;
        }
        if (m.isBlock()) {
            return BLOCKS;
        }
        return MISC;
    }

    public boolean matches(ItemCategory filter) {
        return filter == ALL || filter == this;
    }
}
