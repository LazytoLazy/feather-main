package com.feathermc.punish;

import com.feathermc.FeatherMC;
import com.feathermc.utils.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * "/feathermc punish <player>" GUI.
 *
 * Main screen: six severity categories (config: punish.categories), styled
 * as colored dye buttons - by default Minor/Medium/Large Mute on the left
 * and Minor/Medium/Large Server Infractions on the right, with a book in
 * the middle showing the target's punishment history.
 *
 * Clicking a category opens its "ladder" - the list of configured duration
 * tiers for that category - with the tier FeatherMC would recommend next
 * (based on how many times this player has already been hit with that
 * category, tracked in punish_history.yml) highlighted. Staff can click
 * any tier, not just the recommended one.
 *
 * Picking a tier opens a final confirmation screen:
 *   - MUTE categories -> Yes / No
 *   - BAN categories   -> Normal / IP  (normal account ban vs. IP ban)
 */
public class PunishSystem implements Listener {

    private final FeatherMC plugin;
    private final NamespacedKey categoryKey;
    private final NamespacedKey tierKey;
    private final NamespacedKey controlKey;
    private final File historyFile;
    private final org.bukkit.configuration.file.YamlConfiguration historyData;

    /** One in-progress punish session per staff member (which player/category/tier they're on). */
    private static class Session {
        String target;
        String categoryId;
        int tierIndex;
    }

    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Set<Inventory> mainInventories = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Inventory> ladderInventories = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Inventory> confirmInventories = Collections.newSetFromMap(new WeakHashMap<>());

    public PunishSystem(FeatherMC plugin) {
        this.plugin = plugin;
        this.categoryKey = new NamespacedKey(plugin, "punish_category");
        this.tierKey = new NamespacedKey(plugin, "punish_tier");
        this.controlKey = new NamespacedKey(plugin, "punish_control");
        this.historyFile = new File(plugin.getDataFolder(), "punish_history.yml");
        if (!historyFile.exists()) {
            try {
                historyFile.getParentFile().mkdirs();
                historyFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create punish_history.yml: " + e.getMessage());
            }
        }
        this.historyData = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(historyFile);
    }

    private String msg(String key, String... placeholders) {
        return plugin.getMessages().rawFormatted(key, placeholders);
    }

    public void handle(CommandSender sender, String[] args) {
        if (!sender.hasPermission("feathermc.punish")) {
            plugin.getMessages().send(sender, "general.no-permission");
            return;
        }
        if (args.length < 1) {
            plugin.getMessages().send(sender, "punish.usage");
            return;
        }
        if (!(sender instanceof Player p)) {
            plugin.getMessages().send(sender, "general.player-only");
            return;
        }
        open(p, args[0]);
    }

    // ---------------------------------------------------------------
    // History tracking
    // ---------------------------------------------------------------

    private UUID resolveTarget(String name) {
        return Bukkit.getOfflinePlayer(name).getUniqueId();
    }

    private int getOffenseCount(String targetName, String categoryId) {
        UUID id = resolveTarget(targetName);
        return historyData.getInt("history." + id + "." + categoryId, 0);
    }

    private void incrementOffenseCount(String targetName, String categoryId) {
        UUID id = resolveTarget(targetName);
        int count = getOffenseCount(targetName, categoryId) + 1;
        historyData.set("history." + id + "." + categoryId, count);
        try {
            historyData.save(historyFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save punish_history.yml: " + e.getMessage());
        }
    }

    private void showHistory(Player viewer, String targetName) {
        ConfigurationSection categories = plugin.getConfig().getConfigurationSection("punish.categories");
        UUID id = resolveTarget(targetName);
        ConfigurationSection targetHistory = historyData.getConfigurationSection("history." + id);

        plugin.getMessages().send(viewer, "punish.history-title", "player", targetName);
        if (categories == null || targetHistory == null || targetHistory.getKeys(false).isEmpty()) {
            plugin.getMessages().send(viewer, "punish.history-empty", "player", targetName);
            return;
        }
        for (String catId : categories.getKeys(false)) {
            int count = targetHistory.getInt(catId, 0);
            if (count <= 0) continue;
            String catName = categories.getString(catId + ".name", catId);
            plugin.getMessages().send(viewer, "punish.history-line", "category", catName, "count", String.valueOf(count));
        }
    }

    // ---------------------------------------------------------------
    // Main screen
    // ---------------------------------------------------------------

    public void open(Player viewer, String targetName) {
        ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("punish");
        if (cfg == null) return;

        int size = cfg.getInt("gui-size", 27);
        String title = cfg.getString("gui-title", "&c&lPunish %player%").replace("%player%", targetName);
        Inventory inv = Bukkit.createInventory(null, size, ItemBuilder.colorize(title));

        inv.setItem(13, new ItemBuilder(Material.WRITTEN_BOOK)
                .name(msg("punish.gui-history-button"))
                .lore(List.of(msg("punish.gui-history-button-lore", "player", targetName)))
                .build());
        ItemMeta bookMeta = inv.getItem(13).getItemMeta();
        bookMeta.getPersistentDataContainer().set(controlKey, PersistentDataType.STRING, "history");
        inv.getItem(13).setItemMeta(bookMeta);

        ConfigurationSection categories = cfg.getConfigurationSection("categories");
        if (categories != null) {
            for (String catId : categories.getKeys(false)) {
                ConfigurationSection cat = categories.getConfigurationSection(catId);
                if (cat == null) continue;
                int slot = cat.getInt("slot", 0);
                Material mat = Material.matchMaterial(cat.getString("material", "GRAY_DYE"));
                if (mat == null) mat = Material.GRAY_DYE;
                String name = cat.getString("name", catId);

                List<Map<?, ?>> tiers = cat.getMapList("tiers");
                int offenseCount = getOffenseCount(targetName, catId);
                int recommendedIndex = Math.min(offenseCount, Math.max(tiers.size() - 1, 0));
                String nextDuration = tiers.isEmpty() ? "-" : String.valueOf(tiers.get(recommendedIndex).get("duration"));

                List<String> lore = new ArrayList<>();
                lore.add(msg("punish.gui-next-offense", "duration", nextDuration));
                lore.add("");
                for (Map<?, ?> tier : tiers) {
                    lore.add("&7- Duration: &e" + tier.get("duration"));
                }
                lore.add("");
                lore.add(msg("punish.gui-click-to-choose"));

                ItemStack item = new ItemBuilder(mat).name(name).lore(lore).build();
                ItemMeta meta = item.getItemMeta();
                meta.getPersistentDataContainer().set(categoryKey, PersistentDataType.STRING, catId);
                item.setItemMeta(meta);

                if (slot >= 0 && slot < size) inv.setItem(slot, item);
            }
        }

        Session session = new Session();
        session.target = targetName;
        sessions.put(viewer.getUniqueId(), session);

        mainInventories.add(inv);
        viewer.openInventory(inv);
    }

    // ---------------------------------------------------------------
    // Ladder screen (pick a duration tier)
    // ---------------------------------------------------------------

    private void openLadder(Player viewer, String categoryId) {
        Session session = sessions.get(viewer.getUniqueId());
        if (session == null) return;
        session.categoryId = categoryId;

        ConfigurationSection cat = plugin.getConfig().getConfigurationSection("punish.categories." + categoryId);
        if (cat == null) return;

        int size = plugin.getConfig().getInt("punish.ladder-size", 27);
        String catName = cat.getString("name", categoryId);
        String title = msg("punish.ladder-title", "category", catName, "player", session.target);
        Inventory inv = Bukkit.createInventory(null, size, ItemBuilder.colorize(title));

        Material mat = Material.matchMaterial(cat.getString("material", "GRAY_DYE"));
        if (mat == null) mat = Material.GRAY_DYE;

        List<Map<?, ?>> tiers = cat.getMapList("tiers");
        int offenseCount = getOffenseCount(session.target, categoryId);
        int recommendedIndex = Math.min(offenseCount, Math.max(tiers.size() - 1, 0));

        int slot = 10;
        for (int i = 0; i < tiers.size() && slot < size - 9; i++, slot++) {
            Map<?, ?> tier = tiers.get(i);
            String duration = String.valueOf(tier.get("duration"));

            List<String> lore = new ArrayList<>();
            lore.add(msg("punish.ladder-entry-lore", "duration", duration));
            if (i == recommendedIndex) lore.add(msg("punish.ladder-recommended"));

            ItemStack item = new ItemBuilder(i == recommendedIndex ? Material.LIME_DYE : mat)
                    .name("&f&l" + duration)
                    .lore(lore)
                    .build();
            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().set(categoryKey, PersistentDataType.STRING, categoryId);
            meta.getPersistentDataContainer().set(tierKey, PersistentDataType.INTEGER, i);
            item.setItemMeta(meta);
            inv.setItem(slot, item);
        }

        ItemStack back = new ItemBuilder(Material.BARRIER).name(msg("punish.ladder-back")).build();
        ItemMeta backMeta = back.getItemMeta();
        backMeta.getPersistentDataContainer().set(controlKey, PersistentDataType.STRING, "back");
        back.setItemMeta(backMeta);
        inv.setItem(size - 5, back);

        ladderInventories.add(inv);
        viewer.openInventory(inv);
    }

    // ---------------------------------------------------------------
    // Confirmation screen
    // ---------------------------------------------------------------

    private void openConfirm(Player viewer, String categoryId, int tierIndex) {
        Session session = sessions.get(viewer.getUniqueId());
        if (session == null) return;
        session.categoryId = categoryId;
        session.tierIndex = tierIndex;

        ConfigurationSection cat = plugin.getConfig().getConfigurationSection("punish.categories." + categoryId);
        if (cat == null) return;
        String type = cat.getString("type", "MUTE").toUpperCase(Locale.ROOT);
        Map<?, ?> tier = cat.getMapList("tiers").get(tierIndex);
        String duration = String.valueOf(tier.get("duration"));

        int size = plugin.getConfig().getInt("punish.confirm-size", 27);

        if (type.equals("MUTE")) {
            String title = msg("punish.confirm-mute-title", "player", session.target);
            Inventory inv = Bukkit.createInventory(null, size, ItemBuilder.colorize(title));

            inv.setItem(size / 2, new ItemBuilder(Material.PAPER)
                    .name("&f&l" + session.target)
                    .lore(List.of(msg("punish.confirm-mute-lore", "player", session.target, "duration", duration)))
                    .build());

            inv.setItem(11, control(Material.LIME_CONCRETE, msg("punish.confirm-yes"), "yes"));
            inv.setItem(15, control(Material.RED_CONCRETE, msg("punish.confirm-no"), "no"));

            confirmInventories.add(inv);
            viewer.openInventory(inv);
        } else {
            String title = msg("punish.confirm-ban-title");
            Inventory inv = Bukkit.createInventory(null, size, ItemBuilder.colorize(title));

            inv.setItem(size / 2, new ItemBuilder(Material.PAPER)
                    .name("&f&l" + session.target)
                    .lore(List.of(msg("punish.confirm-ban-lore", "player", session.target, "duration", duration)))
                    .build());

            inv.setItem(11, control(Material.PAPER, msg("punish.confirm-ban-normal"),
                    msg("punish.confirm-ban-normal-lore"), "normal"));
            inv.setItem(15, control(Material.COMPASS, msg("punish.confirm-ban-ip"),
                    msg("punish.confirm-ban-ip-lore"), "ip"));

            confirmInventories.add(inv);
            viewer.openInventory(inv);
        }
    }

    private ItemStack control(Material mat, String name, String action) {
        return control(mat, name, null, action);
    }

    private ItemStack control(Material mat, String name, String lore, String action) {
        ItemBuilder builder = new ItemBuilder(mat).name(name);
        if (lore != null) builder.lore(List.of(lore));
        ItemStack item = builder.build();
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(controlKey, PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        return item;
    }

    // ---------------------------------------------------------------
    // Click routing
    // ---------------------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        Inventory top = event.getView().getTopInventory();

        if (mainInventories.contains(top)) {
            handleMainClick(event, p);
        } else if (ladderInventories.contains(top)) {
            handleLadderClick(event, p);
        } else if (confirmInventories.contains(top)) {
            handleConfirmClick(event, p);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        mainInventories.remove(event.getInventory());
        ladderInventories.remove(event.getInventory());
        confirmInventories.remove(event.getInventory());
    }

    private void handleMainClick(InventoryClickEvent event, Player p) {
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getView().getTopInventory())) {
            return;
        }
        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        String control = clicked.getItemMeta().getPersistentDataContainer().get(controlKey, PersistentDataType.STRING);
        if ("history".equals(control)) {
            Session session = sessions.get(p.getUniqueId());
            if (session != null) showHistory(p, session.target);
            return;
        }

        String catId = clicked.getItemMeta().getPersistentDataContainer().get(categoryKey, PersistentDataType.STRING);
        if (catId == null) return;
        openLadder(p, catId);
    }

    private void handleLadderClick(InventoryClickEvent event, Player p) {
        event.setCancelled(true);
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getView().getTopInventory())) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        String control = clicked.getItemMeta().getPersistentDataContainer().get(controlKey, PersistentDataType.STRING);
        if ("back".equals(control)) {
            Session session = sessions.get(p.getUniqueId());
            if (session != null) open(p, session.target);
            return;
        }

        String catId = clicked.getItemMeta().getPersistentDataContainer().get(categoryKey, PersistentDataType.STRING);
        Integer tierIndex = clicked.getItemMeta().getPersistentDataContainer().get(tierKey, PersistentDataType.INTEGER);
        if (catId == null || tierIndex == null) return;
        openConfirm(p, catId, tierIndex);
    }

    private void handleConfirmClick(InventoryClickEvent event, Player p) {
        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        String control = clicked.getItemMeta().getPersistentDataContainer().get(controlKey, PersistentDataType.STRING);
        if (control == null) return;

        Session session = sessions.get(p.getUniqueId());
        if (session == null) return;

        if (control.equals("no")) {
            plugin.getMessages().send(p, "punish.cancelled");
            p.closeInventory();
            return;
        }

        ConfigurationSection cat = plugin.getConfig().getConfigurationSection("punish.categories." + session.categoryId);
        if (cat == null) return;
        Map<?, ?> tier = cat.getMapList("tiers").get(session.tierIndex);
        String duration = String.valueOf(tier.get("duration"));
        String reason = plugin.getConfig().getString("punish.default-reason", "No reason specified");

        String cmdTemplate = null;
        String scope = null;

        if (control.equals("yes")) {
            cmdTemplate = String.valueOf(tier.get("command"));
        } else if (control.equals("normal")) {
            cmdTemplate = String.valueOf(tier.get("command-normal"));
            scope = "Normal";
        } else if (control.equals("ip")) {
            cmdTemplate = String.valueOf(tier.get("command-ip"));
            scope = "IP";
        }
        if (cmdTemplate == null) return;

        String finalCmd = cmdTemplate.replace("%player%", session.target).replace("%reason%", reason);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd);

        if (plugin.getConfig().getBoolean("punish.log-to-console", true)) {
            plugin.getLogger().info("[Punish] " + p.getName() + " applied '" + session.categoryId
                    + "' (" + duration + (scope != null ? ", " + scope : "") + ") to " + session.target
                    + " (command: " + finalCmd + ")");
        }

        incrementOffenseCount(session.target, session.categoryId);

        if (scope != null) {
            plugin.getMessages().send(p, "punish.applied-ban", "player", session.target, "duration", duration, "scope", scope);
        } else {
            plugin.getMessages().send(p, "punish.applied-mute", "player", session.target, "duration", duration);
        }

        sessions.remove(p.getUniqueId());
        p.closeInventory();
    }
}
