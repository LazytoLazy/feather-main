package com.feathermc.shop;

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
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * Egg shop. Prices are paid/earned from the player's virtual egg balance
 * (FeatherMC's own EggEconomy) rather than a physical item.
 */
public class ShopSystem implements Listener {

    private final FeatherMC plugin;
    private final NamespacedKey itemKey;

    public ShopSystem(FeatherMC plugin) {
        this.plugin = plugin;
        this.itemKey = new NamespacedKey(plugin, "shop_item_id");

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null
                && plugin.getConfig().getBoolean("shop.placeholders.enabled", true)) {
            new EggPlaceholders(plugin).register();
        }
    }

    public void handle(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            plugin.getMessages().send(sender, "general.player-only");
            return;
        }
        if (!p.hasPermission("feathermc.shop.use")) {
            plugin.getMessages().send(p, "general.no-permission");
            return;
        }
        open(p);
    }

    public void open(Player p) {
        String title = plugin.getConfig().getString("shop.gui-title", "&a&lEgg Shop");
        int size = plugin.getConfig().getInt("shop.gui-size", 54);
        Inventory inv = Bukkit.createInventory(null, size, ItemBuilder.colorize(title));

        ConfigurationSection items = plugin.getConfig().getConfigurationSection("shop.items");
        if (items != null) {
            for (String id : items.getKeys(false)) {
                ConfigurationSection sec = items.getConfigurationSection(id);
                if (sec == null) continue;
                int slot = sec.getInt("slot", 0);
                Material mat = Material.matchMaterial(sec.getString("material", "STONE"));
                if (mat == null) mat = Material.STONE;
                int amount = sec.getInt("amount", 1);
                int buy = sec.getInt("buy-price", 0);
                int sell = sec.getInt("sell-price", 0);
                String symbol = plugin.getEggEconomy().symbol();

                ItemStack display = new ItemBuilder(mat, amount)
                        .name(sec.getString("name", id))
                        .lore(List.of(
                                "&7Buy: &a" + buy + " " + symbol + " &7(left-click)",
                                "&7Sell: &c" + sell + " " + symbol + " &7(right-click)"
                        ))
                        .build();
                ItemMeta meta = display.getItemMeta();
                meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, id);
                display.setItemMeta(meta);

                if (slot >= 0 && slot < size) inv.setItem(slot, display);
            }
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
        String id = clicked.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
        if (id == null) return;
        event.setCancelled(true);

        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("shop.items." + id);
        if (sec == null) return;

        Material mat = Material.matchMaterial(sec.getString("material", "STONE"));
        if (mat == null) mat = Material.STONE;
        int amount = sec.getInt("amount", 1);

        if (event.getClick() == ClickType.LEFT) {
            double price = sec.getInt("buy-price", 0);
            if (!plugin.getEggEconomy().has(p, price)) {
                plugin.getMessages().send(p, "shop.not-enough-eggs",
                        "price", plugin.getEggEconomy().format(price),
                        "balance", plugin.getEggEconomy().format(plugin.getEggEconomy().getBalance(p)));
                return;
            }
            plugin.getEggEconomy().withdraw(p, price);
            p.getInventory().addItem(new ItemStack(mat, amount));
            plugin.getMessages().send(p, "shop.purchased",
                    "amount", String.valueOf(amount), "item", mat.name(),
                    "price", String.valueOf((long) price));
        } else if (event.getClick() == ClickType.RIGHT) {
            double price = sec.getInt("sell-price", 0);
            if (!p.getInventory().containsAtLeast(new ItemStack(mat), amount)) {
                plugin.getMessages().send(p, "shop.not-enough-items");
                return;
            }
            p.getInventory().removeItem(new ItemStack(mat, amount));
            plugin.getEggEconomy().deposit(p, price);
            plugin.getMessages().send(p, "shop.sold",
                    "amount", String.valueOf(amount), "item", mat.name(),
                    "price", String.valueOf((long) price));
        }
    }
}
