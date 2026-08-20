package com.feathermc.playtime;

import com.feathermc.FeatherMC;
import com.feathermc.utils.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Tracks cumulative playtime per player and shows a milestone-rewards GUI
 * via "/feathermc playtime". Milestones run every
 * playtime.reward-interval-hours starting at playtime.start-hour up to
 * playtime.max-hour (defaults: every 2h, 1 -> 1000).
 */
public class PlaytimeSystem implements Listener {

    private final FeatherMC plugin;
    private final NamespacedKey milestoneKey;
    private final File file;
    private final org.bukkit.configuration.file.YamlConfiguration data;

    private final Map<UUID, Long> totalMinutes = new HashMap<>();
    private final Map<UUID, Long> sessionStart = new HashMap<>();
    private final Map<UUID, Set<Integer>> claimedMilestones = new HashMap<>();

    public PlaytimeSystem(FeatherMC plugin) {
        this.plugin = plugin;
        this.milestoneKey = new NamespacedKey(plugin, "playtime_milestone");
        this.file = new File(plugin.getDataFolder(), "playtime.yml");
        if (!file.exists()) {
            try {
                file.getParentFile().mkdirs();
                file.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create playtime.yml: " + e.getMessage());
            }
        }
        this.data = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        load();

        int autosaveMinutes = plugin.getConfig().getInt("playtime.autosave-interval-minutes", 5);
        Bukkit.getScheduler().runTaskTimer(plugin, this::saveAll,
                autosaveMinutes * 60L * 20L, autosaveMinutes * 60L * 20L);
    }

    private void load() {
        totalMinutes.clear();
        claimedMilestones.clear();
        if (data.getConfigurationSection("players") == null) return;
        for (String key : data.getConfigurationSection("players").getKeys(false)) {
            UUID id = UUID.fromString(key);
            totalMinutes.put(id, data.getLong("players." + key + ".minutes", 0));
            List<Integer> claimed = data.getIntegerList("players." + key + ".claimed");
            claimedMilestones.put(id, new HashSet<>(claimed));
        }
    }

    public void saveAll() {
        for (UUID id : totalMinutes.keySet()) {
            String path = "players." + id;
            data.set(path + ".minutes", getLiveMinutes(id));
            data.set(path + ".claimed", new ArrayList<>(claimedMilestones.getOrDefault(id, Set.of())));
        }
        try {
            data.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save playtime.yml: " + ex.getMessage());
        }
    }

    public long getLiveMinutes(UUID id) {
        long base = totalMinutes.getOrDefault(id, 0L);
        Long start = sessionStart.get(id);
        if (start == null) return base;
        long sessionMinutes = (System.currentTimeMillis() - start) / 60000L;
        return base + sessionMinutes;
    }

    public double getLiveHours(UUID id) {
        return getLiveMinutes(id) / 60.0;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        sessionStart.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        totalMinutes.putIfAbsent(event.getPlayer().getUniqueId(), 0L);
        claimedMilestones.putIfAbsent(event.getPlayer().getUniqueId(), new HashSet<>());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Long start = sessionStart.remove(id);
        if (start != null) {
            long minutes = (System.currentTimeMillis() - start) / 60000L;
            totalMinutes.merge(id, minutes, Long::sum);
        }
        saveAll();
    }

    private List<Integer> milestoneHours() {
        int interval = plugin.getConfig().getInt("playtime.reward-interval-hours", 2);
        int start = plugin.getConfig().getInt("playtime.start-hour", 1);
        int max = plugin.getConfig().getInt("playtime.max-hour", 1000);
        List<Integer> hours = new ArrayList<>();
        for (int h = start; h <= max; h += interval) {
            hours.add(h);
        }
        return hours;
    }

    public void handle(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            plugin.getMessages().send(sender, "general.player-only");
            return;
        }
        if (!p.hasPermission("feathermc.playtime.use")) {
            plugin.getMessages().send(p, "general.no-permission");
            return;
        }
        open(p);
    }

    public void open(Player p) {
        String title = plugin.getConfig().getString("playtime.gui-title", "&d&lPlaytime Rewards");
        int size = plugin.getConfig().getInt("playtime.gui-size", 54);
        Inventory inv = Bukkit.createInventory(null, size, ItemBuilder.colorize(title));

        double liveHours = getLiveHours(p.getUniqueId());
        Set<Integer> claimed = claimedMilestones.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());

        int slot = 0;
        for (int hour : milestoneHours()) {
            if (slot >= size) break;

            boolean reached = liveHours >= hour;
            boolean isClaimed = claimed.contains(hour);

            Material mat = isClaimed ? Material.LIME_DYE : (reached ? Material.CHEST : Material.GRAY_DYE);
            String name = (isClaimed ? "&a&l" : reached ? "&e&l" : "&7&l") + hour + " Hours";
            String status = isClaimed ? "&aClaimed" : reached ? "&eClick to claim!" : "&7Locked";

            ItemStack item = new ItemBuilder(mat)
                    .name(name)
                    .lore(List.of("&7Reach " + hour + " hours played", status))
                    .build();

            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().set(milestoneKey, PersistentDataType.INTEGER, hour);
            item.setItemMeta(meta);

            inv.setItem(slot++, item);
        }
        p.openInventory(inv);
        plugin.getMessages().send(p, "playtime.hours-played", "hours", String.format("%.1f", liveHours));
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        if (event.getClickedInventory() == null
                || !event.getClickedInventory().equals(event.getView().getTopInventory())) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        Integer hour = clicked.getItemMeta().getPersistentDataContainer().get(milestoneKey, PersistentDataType.INTEGER);
        if (hour == null) return;
        event.setCancelled(true);

        Set<Integer> claimed = claimedMilestones.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
        if (claimed.contains(hour)) {
            plugin.getMessages().send(p, "playtime.already-claimed");
            return;
        }
        if (getLiveHours(p.getUniqueId()) < hour) {
            plugin.getMessages().send(p, "playtime.not-reached", "hours", String.valueOf(hour));
            return;
        }

        List<String> commands = plugin.getConfig().contains("playtime.milestone-overrides." + hour)
                ? plugin.getConfig().getStringList("playtime.milestone-overrides." + hour)
                : plugin.getConfig().getStringList("playtime.reward-commands.default");

        for (String cmd : commands) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
        }

        claimed.add(hour);
        plugin.getMessages().send(p, "playtime.claimed", "hours", String.valueOf(hour));
        saveAll();
        open(p);
    }
}
