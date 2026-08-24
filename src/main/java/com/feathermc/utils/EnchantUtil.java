package com.feathermc.utils;

import org.bukkit.enchantments.Enchantment;

/** Small helpers for showing enchantments in GUIs (Title Case names + roman numerals). */
public class EnchantUtil {

    private static final String[] ROMAN = {
            "0", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"
    };

    public static String roman(int level) {
        if (level <= 0) return "0";
        if (level < ROMAN.length) return ROMAN[level];
        return String.valueOf(level);
    }

    public static String displayName(Enchantment enchantment) {
        String key = enchantment.getKey().getKey(); // e.g. "protection", "sharpness"
        StringBuilder sb = new StringBuilder();
        for (String part : key.split("_")) {
            if (part.isEmpty()) continue;
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }
}
