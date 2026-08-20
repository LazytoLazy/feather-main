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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;

/**
 * "/feathermc punish <player>" - opens a GUI with (by default) 6 configurable
 * punishment options. Each option runs a console command defined in
 * config.yml, with %player% and %reason% substituted.
 */
public class PunishSystem implements Listener {

    private final FeatherMC plugin;
    private final NamespacedKey actionKey;
    private final Map<Inventory, String> openSessions = new HashMap<>();

    public PunishSystem(FeatherMC plugin) {
        this.plugin = plugin;
        this.actionKey = new NamespacedKey(plugin, "punish_action");
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

    public void open(Player viewer, String targetName) {
        ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("punish");
        if (cfg == null) return;

        int size = cfg.getInt("gui-size", 27);
        String title = cfg.getString("gui-title", "&c&lPunish Player").replace("%player%", targetName);
        Inventory inv = Bukkit.createInventory(null, size, ItemBuilder.colorize(title));

        ConfigurationSection options = cfg.getConfigurationSection("options");
        if (options != null) {
            for (String key : options.getKeys(false)) {
                ConfigurationSection opt = options.getConfigurationSection(key);
                if (opt == null) continue;
                int slot = opt.getInt("slot", 0);
                Material mat = Material.matchMaterial(opt.getString("material", "PAPER"));
                if (mat == null) mat = Material.PAPER;

                ItemStack item = new ItemBuilder(mat)
                        .name(opt.getString("name", key))
                        .lore(opt.getStringList("lore"))
                        .build();

                ItemMeta meta = item.getItemMeta();
                meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, key);
                item.setItemMeta(meta);

                if (slot >= 0 && slot < size) {
                    inv.setItem(slot, item);
                }
            }
        }

        openSessions.put(inv, targetName);
        viewer.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory topInv = event.getView().getTopInventory();
        String target = openSessions.get(topInv);
        if (target == null) return;

        event.setCancelled(true);
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(topInv)) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        String action = clicked.getItemMeta().getPersistentDataContainer()
                .get(actionKey, PersistentDataType.STRING);
        if (action == null) return;

        ConfigurationSection opt = plugin.getConfig()
                .getConfigurationSection("punish.options." + action);
        if (opt == null) return;

        String cmdTemplate = opt.getString("command");
        String reason = plugin.getConfig().getString("punish.default-reason", "No reason specified");

        String finalCmd = cmdTemplate
                .replace("%player%", target)
                .replace("%reason%", reason);

        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd);

        if (plugin.getConfig().getBoolean("punish.log-to-console", true)) {
            plugin.getLogger().info("[Punish] " + event.getWhoClicked().getName()
                    + " used action '" + action + "' on " + target + " (command: " + finalCmd + ")");
        }

        if (event.getWhoClicked() instanceof Player p) {
            plugin.getMessages().send(p, "punish.applied", "action", action, "player", target);
            p.closeInventory();
        }
        openSessions.remove(topInv);
    }
}
