package com.feathermc.warp;

import com.feathermc.FeatherMC;
import com.feathermc.utils.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * "/feathermc warp <name>", "/feathermc warp" (GUI), "/feathermc warp set <name>",
 * "/feathermc warp del <name>". Warps persist to plugins/FeatherMC/warps.yml
 */
public class WarpSystem implements Listener {

    private final FeatherMC plugin;
    private final File file;
    private final FileConfiguration data;
    private final NamespacedKey warpKey;
    private final Map<String, Location> warps = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, BukkitTask> pendingTeleports = new HashMap<>();
    private final Map<String, Location> teleportStartLoc = new HashMap<>();

    public WarpSystem(FeatherMC plugin) {
        this.plugin = plugin;
        this.warpKey = new NamespacedKey(plugin, "warp_name");
        this.file = new File(plugin.getDataFolder(), "warps.yml");
        if (!file.exists()) {
            try {
                file.getParentFile().mkdirs();
                file.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create warps.yml: " + e.getMessage());
            }
        }
        this.data = YamlConfiguration.loadConfiguration(file);
        loadWarps();
    }

    private void loadWarps() {
        warps.clear();
        if (data.getConfigurationSection("warps") == null) return;
        for (String name : data.getConfigurationSection("warps").getKeys(false)) {
            String path = "warps." + name;
            String worldName = data.getString(path + ".world");
            if (worldName == null || Bukkit.getWorld(worldName) == null) continue;
            Location loc = new Location(
                    Bukkit.getWorld(worldName),
                    data.getDouble(path + ".x"),
                    data.getDouble(path + ".y"),
                    data.getDouble(path + ".z"),
                    (float) data.getDouble(path + ".yaw"),
                    (float) data.getDouble(path + ".pitch")
            );
            warps.put(name, loc);
        }
    }

    private void saveWarps() {
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save warps.yml: " + e.getMessage());
        }
    }

    public void setWarp(String name, Location loc) {
        warps.put(name, loc);
        String path = "warps." + name;
        data.set(path + ".world", loc.getWorld().getName());
        data.set(path + ".x", loc.getX());
        data.set(path + ".y", loc.getY());
        data.set(path + ".z", loc.getZ());
        data.set(path + ".yaw", loc.getYaw());
        data.set(path + ".pitch", loc.getPitch());
        saveWarps();
    }

    public void delWarp(String name) {
        warps.remove(name);
        data.set("warps." + name, null);
        saveWarps();
    }

    public void handle(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player p)) {
                plugin.getMessages().send(sender, "general.player-only");
                return;
            }
            openWarpsGui(p);
            return;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("set")) {
            if (!sender.hasPermission("feathermc.warp.set")) {
                plugin.getMessages().send(sender, "general.no-permission");
                return;
            }
            if (!(sender instanceof Player p)) {
                plugin.getMessages().send(sender, "general.player-only");
                return;
            }
            if (args.length < 2) {
                plugin.getMessages().send(p, "warp.usage-set");
                return;
            }
            setWarp(args[1], p.getLocation());
            plugin.getMessages().send(p, "warp.created", "warp", args[1]);
            return;
        }

        if (sub.equals("del") || sub.equals("delete")) {
            if (!sender.hasPermission("feathermc.warp.set")) {
                plugin.getMessages().send(sender, "general.no-permission");
                return;
            }
            if (args.length < 2) {
                plugin.getMessages().send(sender, "warp.usage-del");
                return;
            }
            if (!warps.containsKey(args[1])) {
                plugin.getMessages().send(sender, "warp.not-found");
                return;
            }
            delWarp(args[1]);
            plugin.getMessages().send(sender, "warp.deleted", "warp", args[1]);
            return;
        }

        // otherwise treat args[0] as a warp name to teleport to
        if (!(sender instanceof Player p)) {
            plugin.getMessages().send(sender, "general.player-only");
            return;
        }
        teleportToWarp(p, args[0]);
    }

    private void teleportToWarp(Player p, String name) {
        Location loc = warps.get(name);
        if (loc == null) {
            plugin.getMessages().send(p, "warp.not-found");
            return;
        }
        int delay = plugin.getConfig().getInt("warps.teleport-delay-seconds", 3);
        if (delay <= 0) {
            p.teleport(loc);
            plugin.getMessages().send(p, "warp.teleported", "warp", name);
            return;
        }

        plugin.getMessages().send(p, "warp.teleporting", "warp", name, "seconds", String.valueOf(delay));
        teleportStartLoc.put(p.getName(), p.getLocation());
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pendingTeleports.remove(p.getName());
            teleportStartLoc.remove(p.getName());
            if (p.isOnline()) {
                p.teleport(loc);
                plugin.getMessages().send(p, "warp.teleported", "warp", name);
            }
        }, delay * 20L);
        BukkitTask old = pendingTeleports.put(p.getName(), task);
        if (old != null) old.cancel();
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getConfig().getBoolean("warps.cancel-on-move", true)) return;
        Player p = event.getPlayer();
        BukkitTask task = pendingTeleports.get(p.getName());
        if (task == null) return;
        Location start = teleportStartLoc.get(p.getName());
        if (start == null) return;
        if (event.getTo() == null) return;
        if (start.getBlockX() != event.getTo().getBlockX()
                || start.getBlockY() != event.getTo().getBlockY()
                || start.getBlockZ() != event.getTo().getBlockZ()) {
            task.cancel();
            pendingTeleports.remove(p.getName());
            teleportStartLoc.remove(p.getName());
            plugin.getMessages().send(p, "warp.cancelled-moved");
        }
    }

    private void openWarpsGui(Player p) {
        String title = plugin.getConfig().getString("warps.gui-title", "&b&lWarps");
        int size = plugin.getConfig().getInt("warps.gui-size", 54);
        Inventory inv = Bukkit.createInventory(null, size, ItemBuilder.colorize(title));

        int slot = 0;
        for (String name : warps.keySet()) {
            if (slot >= size) break;
            ItemStack item = new ItemBuilder(Material.COMPASS)
                    .name("&b" + name)
                    .lore(java.util.List.of("&7Click to teleport"))
                    .build();
            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().set(warpKey, PersistentDataType.STRING, name);
            item.setItemMeta(meta);
            inv.setItem(slot++, item);
        }
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        if (event.getClickedInventory() == null
                || !event.getClickedInventory().equals(event.getView().getTopInventory())) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        String name = clicked.getItemMeta().getPersistentDataContainer().get(warpKey, PersistentDataType.STRING);
        if (name == null) return;
        event.setCancelled(true);
        p.closeInventory();
        teleportToWarp(p, name);
    }
}
