package com.feathermc.order;

import com.feathermc.FeatherMC;
import com.feathermc.utils.ItemBuilder;
import net.milkbowl.vault.economy.Economy;
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
 * "Sell order" GUI ("/feathermc order"), DonutSMP-style: a player posts a
 * standing order to BUY a material at a fixed price-per-item, funded from
 * their balance up front. Other players open the same menu and click an
 * order while holding matching items to instantly sell into it.
 *
 * Live order counts are capped using whichever LuckPerms-granted
 * permission (ordersystem.slot-permissions) gives the highest slot count.
 */
public class OrderSystem implements Listener {

    private final FeatherMC plugin;
    private final NamespacedKey orderKey;
    private final NamespacedKey controlKey;
    private final File file;
    private final org.bukkit.configuration.file.YamlConfiguration data;

    public static class Order {
        final UUID id = UUID.randomUUID();
        UUID buyer;
        String buyerName;
        Material material;
        double pricePerItem;
        int amountRemaining;
        long expiresAt;

        Order(UUID buyer, String buyerName, Material material, double pricePerItem, int amount, long expiresAt) {
            this.buyer = buyer;
            this.buyerName = buyerName;
            this.material = material;
            this.pricePerItem = pricePerItem;
            this.amountRemaining = amount;
            this.expiresAt = expiresAt;
        }
    }

    private final Map<UUID, Order> orders = new LinkedHashMap<>();
    private final Map<UUID, Integer> playerPage = new HashMap<>();
    private final Set<Inventory> openOrderInventories = Collections.newSetFromMap(new WeakHashMap<>());

    public OrderSystem(FeatherMC plugin) {
        this.plugin = plugin;
        this.orderKey = new NamespacedKey(plugin, "order_id");
        this.controlKey = new NamespacedKey(plugin, "order_control");
        this.file = new File(plugin.getDataFolder(), "orders.yml");
        if (!file.exists()) {
            try {
                file.getParentFile().mkdirs();
                file.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create orders.yml: " + e.getMessage());
            }
        }
        this.data = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        load();
    }

    private void load() {
        orders.clear();
        ConfigurationSection sec = data.getConfigurationSection("orders");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            try {
                UUID buyer = UUID.fromString(sec.getString(key + ".buyer"));
                String buyerName = sec.getString(key + ".buyerName", "Unknown");
                Material mat = Material.valueOf(sec.getString(key + ".material"));
                double price = sec.getDouble(key + ".price");
                int amount = sec.getInt(key + ".amount");
                long expires = sec.getLong(key + ".expiresAt");
                orders.put(UUID.fromString(key), new Order(buyer, buyerName, mat, price, amount, expires));
            } catch (Exception ignored) {
            }
        }
    }

    private void save() {
        data.set("orders", null);
        for (Order o : orders.values()) {
            String path = "orders." + o.id;
            data.set(path + ".buyer", o.buyer.toString());
            data.set(path + ".buyerName", o.buyerName);
            data.set(path + ".material", o.material.name());
            data.set(path + ".price", o.pricePerItem);
            data.set(path + ".amount", o.amountRemaining);
            data.set(path + ".expiresAt", o.expiresAt);
        }
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save orders.yml: " + e.getMessage());
        }
    }

    private Economy economy() {
        var rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        return rsp == null ? null : rsp.getProvider();
    }

    public int getMaxSlots(Player p) {
        ConfigurationSection perms = plugin.getConfig().getConfigurationSection("ordersystem.slot-permissions");
        int best = plugin.getConfig().getInt("ordersystem.default-slots", 1);
        if (perms != null) {
            for (String perm : perms.getKeys(false)) {
                if (p.hasPermission(perm)) {
                    best = Math.max(best, perms.getInt(perm));
                }
            }
        }
        return best;
    }

    public long countActiveOrders(UUID buyer) {
        return orders.values().stream().filter(o -> o.buyer.equals(buyer)).count();
    }

    public void handle(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            plugin.getMessages().send(sender, "general.player-only");
            return;
        }
        if (!p.hasPermission("feathermc.order.use")) {
            plugin.getMessages().send(p, "general.no-permission");
            return;
        }
        open(p, 0);
    }

    // ---------------------------------------------------------------
    // GUI
    // ---------------------------------------------------------------

    public void open(Player p, int page) {
        int size = plugin.getConfig().getInt("ordersystem.gui-size", 54);
        int perPage = plugin.getConfig().getInt("ordersystem.orders-per-page", 45);

        List<Order> all = new ArrayList<>(orders.values());
        int totalPages = Math.max(1, (int) Math.ceil(all.size() / (double) perPage));
        page = Math.max(0, Math.min(page, totalPages - 1));
        playerPage.put(p.getUniqueId(), page);

        Inventory inv = Bukkit.createInventory(null, size,
                ItemBuilder.colorize(plugin.getMessages().rawFormatted("ordersystem.gui-title")));

        int start = page * perPage;
        int end = Math.min(start + perPage, all.size());
        int slot = 0;
        for (int i = start; i < end; i++) {
            Order o = all.get(i);
            ItemStack display = new ItemBuilder(o.material)
                    .name("&f" + o.material.name())
                    .lore(List.of(
                            "&7Buyer: &f" + o.buyerName,
                            "&7Price each: &a" + fmt(o.pricePerItem),
                            "&7Remaining: &e" + o.amountRemaining,
                            "&eHold matching items and click to sell in"
                    ))
                    .build();
            ItemMeta meta = display.getItemMeta();
            meta.getPersistentDataContainer().set(orderKey, PersistentDataType.STRING, o.id.toString());
            display.setItemMeta(meta);
            inv.setItem(slot++, display);
        }

        int controlRow = size - 9;
        if (page > 0) {
            inv.setItem(controlRow, control(Material.ARROW, plugin.getMessages().rawFormatted("ordersystem.gui-prev-page"), "prev"));
        }
        ItemStack createButton = new ItemBuilder(Material.EMERALD)
                .name(plugin.getMessages().rawFormatted("ordersystem.gui-create-button"))
                .lore(List.of(plugin.getMessages().rawFormatted("ordersystem.gui-create-lore")))
                .build();
        ItemMeta createMeta = createButton.getItemMeta();
        createMeta.getPersistentDataContainer().set(controlKey, PersistentDataType.STRING, "create");
        createButton.setItemMeta(createMeta);
        inv.setItem(controlRow + 4, createButton);

        inv.setItem(controlRow + 5, new ItemBuilder(Material.PAPER).name("&7Page " + (page + 1) + "/" + totalPages).build());

        if (page < totalPages - 1) {
            inv.setItem(controlRow + 8, control(Material.ARROW, plugin.getMessages().rawFormatted("ordersystem.gui-next-page"), "next"));
        }

        openOrderInventories.add(inv);
        p.openInventory(inv);
    }

    private ItemStack control(Material mat, String name, String action) {
        ItemStack item = new ItemBuilder(mat).name(name).build();
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(controlKey, PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        if (!openOrderInventories.contains(event.getView().getTopInventory())) return;
        if (event.getClickedInventory() == null
                || !event.getClickedInventory().equals(event.getView().getTopInventory())) return;

        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        String control = clicked.getItemMeta().getPersistentDataContainer().get(controlKey, PersistentDataType.STRING);
        if (control != null) {
            int page = playerPage.getOrDefault(p.getUniqueId(), 0);
            switch (control) {
                case "next" -> open(p, page + 1);
                case "prev" -> open(p, page - 1);
                case "create" -> beginCreateFlow(p);
            }
            return;
        }

        String idStr = clicked.getItemMeta().getPersistentDataContainer().get(orderKey, PersistentDataType.STRING);
        if (idStr == null) return;
        fulfil(p, UUID.fromString(idStr));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        openOrderInventories.remove(event.getInventory());
    }

    private void beginCreateFlow(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) {
            plugin.getMessages().send(p, "ordersystem.no-item-held");
            return;
        }
        long active = countActiveOrders(p.getUniqueId());
        int maxSlots = getMaxSlots(p);
        if (active >= maxSlots) {
            plugin.getMessages().send(p, "ordersystem.slots-full", "active", String.valueOf(active), "max", String.valueOf(maxSlots));
            return;
        }

        Material material = hand.getType();
        p.closeInventory();
        plugin.getMessages().send(p, "ordersystem.prompt-price-amount");
        plugin.getChatInput().awaitInput(p, input -> {
            if (input == null) {
                plugin.getMessages().send(p, "ordersystem.cancelled-input");
                return;
            }
            createOrder(p, material, input);
        });
    }

    private void createOrder(Player p, Material material, String rawInput) {
        String[] parts = rawInput.trim().split("\\s+");
        if (parts.length < 2) {
            plugin.getMessages().send(p, "ordersystem.invalid-input");
            return;
        }

        double price;
        int amount;
        try {
            price = Double.parseDouble(parts[0]);
            amount = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            plugin.getMessages().send(p, "ordersystem.invalid-input");
            return;
        }

        double min = plugin.getConfig().getDouble("ordersystem.min-price", 1.0);
        double max = plugin.getConfig().getDouble("ordersystem.max-price", 1000000.0);
        if (price < min || price > max || amount <= 0) {
            plugin.getMessages().send(p, "ordersystem.price-range");
            return;
        }

        long active = countActiveOrders(p.getUniqueId());
        int maxSlots = getMaxSlots(p);
        if (active >= maxSlots) {
            plugin.getMessages().send(p, "ordersystem.slots-full", "active", String.valueOf(active), "max", String.valueOf(maxSlots));
            return;
        }

        double totalCost = price * amount;
        Economy econ = economy();
        if (econ == null) {
            plugin.getMessages().send(p, "ordersystem.no-economy");
            return;
        }
        if (!econ.has(p, totalCost)) {
            plugin.getMessages().send(p, "ordersystem.cant-afford", "cost", fmt(totalCost));
            return;
        }
        econ.withdrawPlayer(p, totalCost);

        long durationHours = plugin.getConfig().getLong("ordersystem.order-duration-hours", 72);
        long expiresAt = System.currentTimeMillis() + durationHours * 3600_000L;

        Order order = new Order(p.getUniqueId(), p.getName(), material, price, amount, expiresAt);
        orders.put(order.id, order);
        save();

        plugin.getMessages().send(p, "ordersystem.created",
                "amount", String.valueOf(amount), "item", material.name(), "price", fmt(price),
                "active", String.valueOf(active + 1), "max", String.valueOf(maxSlots));
    }

    private void fulfil(Player p, UUID id) {
        Order order = orders.get(id);
        if (order == null) {
            plugin.getMessages().send(p, "ordersystem.not-available");
            return;
        }

        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() != order.material) {
            plugin.getMessages().send(p, "ordersystem.wrong-item", "item", order.material.name());
            return;
        }

        int sellAmount = Math.min(hand.getAmount(), order.amountRemaining);
        if (sellAmount <= 0) {
            plugin.getMessages().send(p, "ordersystem.order-full");
            return;
        }

        Economy econ = economy();
        if (econ == null) {
            plugin.getMessages().send(p, "ordersystem.no-economy");
            return;
        }

        double payout = sellAmount * order.pricePerItem;
        hand.setAmount(hand.getAmount() - sellAmount);
        econ.depositPlayer(p, payout);
        order.amountRemaining -= sellAmount;

        plugin.getMessages().send(p, "ordersystem.sold",
                "amount", String.valueOf(sellAmount), "item", order.material.name(), "payout", fmt(payout));

        Player buyer = Bukkit.getPlayer(order.buyer);
        if (buyer != null) {
            buyer.getInventory().addItem(new ItemStack(order.material, sellAmount));
        } else {
            p.getWorld().dropItemNaturally(p.getLocation(), new ItemStack(order.material, sellAmount));
        }

        if (order.amountRemaining <= 0) {
            orders.remove(id);
            if (buyer != null) plugin.getMessages().send(buyer, "ordersystem.filled-notify", "item", order.material.name());
        }
        save();
    }

    public void purgeExpired() {
        long now = System.currentTimeMillis();
        Iterator<Order> it = orders.values().iterator();
        boolean changed = false;
        while (it.hasNext()) {
            Order o = it.next();
            if (o.expiresAt <= now) {
                Player buyer = Bukkit.getPlayer(o.buyer);
                Economy econ = economy();
                if (econ != null) {
                    double refund = o.pricePerItem * o.amountRemaining;
                    if (buyer != null) {
                        econ.depositPlayer(buyer, refund);
                        plugin.getMessages().send(buyer, "ordersystem.expired-refund");
                    } else {
                        econ.depositPlayer(Bukkit.getOfflinePlayer(o.buyer), refund);
                    }
                }
                it.remove();
                changed = true;
            }
        }
        if (changed) save();
    }

    private String fmt(double amount) {
        return amount == Math.floor(amount) ? String.valueOf((long) amount) : String.format("%.2f", amount);
    }
}
