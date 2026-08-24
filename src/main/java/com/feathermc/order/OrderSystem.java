package com.feathermc.order;

import com.feathermc.FeatherMC;
import com.feathermc.utils.EnchantUtil;
import com.feathermc.utils.ItemBuilder;
import com.feathermc.utils.ItemCategory;
import com.feathermc.utils.SortMode;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
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
 * "Sell order" GUI ("/feathermc order"), styled after DonutSMP's /orders.
 *
 * Creating an order no longer requires holding the item: clicking "New
 * Order" opens a searchable browser of every obtainable item/block in the
 * game. If the chosen item is a piece of armor, an extra "Choose
 * Enchantments" screen lets the buyer specify exactly which enchantments
 * (and levels) the delivered armor must have - sellers can only fulfil the
 * order with armor that meets those requirements.
 */
public class OrderSystem implements Listener {

    private final FeatherMC plugin;
    private final NamespacedKey orderKey;
    private final NamespacedKey controlKey;
    private final NamespacedKey materialKey;
    private final NamespacedKey enchantKey;
    private final File file;
    private final org.bukkit.configuration.file.YamlConfiguration data;

    public static class Order {
        final UUID id = UUID.randomUUID();
        UUID buyer;
        String buyerName;
        Material material;
        Map<Enchantment, Integer> enchants = new LinkedHashMap<>();
        double pricePerItem;
        int amountRemaining;
        int amountTotal;
        long expiresAt;
        long createdAt;

        Order(UUID buyer, String buyerName, Material material, double pricePerItem,
              int amount, long expiresAt, long createdAt) {
            this.buyer = buyer;
            this.buyerName = buyerName;
            this.material = material;
            this.pricePerItem = pricePerItem;
            this.amountRemaining = amount;
            this.amountTotal = amount;
            this.expiresAt = expiresAt;
            this.createdAt = createdAt;
        }
    }

    private static class OrderDraft {
        Material material;
        Map<Enchantment, Integer> enchants = new LinkedHashMap<>();
        Integer amount;
        Double price;
    }

    private final Map<UUID, Order> orders = new LinkedHashMap<>();
    private final Map<UUID, Integer> playerPage = new HashMap<>();
    private final Map<UUID, SortMode> playerSort = new HashMap<>();
    private final Map<UUID, ItemCategory> playerFilter = new HashMap<>();
    private final Map<UUID, OrderDraft> drafts = new HashMap<>();
    private final Map<UUID, UUID> deliverTarget = new HashMap<>();

    // item browser state
    private final Map<UUID, Integer> browserPage = new HashMap<>();
    private final Map<UUID, ItemCategory> browserFilter = new HashMap<>();
    private final Map<UUID, String> browserSearch = new HashMap<>();
    private final List<Material> orderableMaterials;

    private final Set<Inventory> mainInventories = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Inventory> draftInventories = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Inventory> deliverInventories = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Inventory> yourOrdersInventories = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Inventory> browserInventories = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Inventory> enchantInventories = Collections.newSetFromMap(new WeakHashMap<>());

    private static final int ITEM_SLOT = 13;
    private static final int AMOUNT_SLOT = 10;
    private static final int PRICE_SLOT = 16;
    private static final int CHANGE_ITEM_SLOT = 19;
    private static final int CHANGE_ENCHANTS_SLOT = 25;
    private static final int CANCEL_SLOT = 20;
    private static final int CONFIRM_SLOT = 24;

    public OrderSystem(FeatherMC plugin) {
        this.plugin = plugin;
        this.orderKey = new NamespacedKey(plugin, "order_id");
        this.controlKey = new NamespacedKey(plugin, "order_control");
        this.materialKey = new NamespacedKey(plugin, "order_material");
        this.enchantKey = new NamespacedKey(plugin, "order_enchant");
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
        this.orderableMaterials = buildOrderableMaterials();
        load();
    }

    private List<Material> buildOrderableMaterials() {
        List<Material> list = new ArrayList<>();
        for (Material m : Material.values()) {
            if (!m.isItem()) continue;
            if (m.isLegacy()) continue;
            if (m.name().startsWith("LEGACY_")) continue;
            if (m == Material.AIR) continue;
            list.add(m);
        }
        list.sort(Comparator.comparing(Enum::name));
        return list;
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
                int total = sec.getInt(key + ".total", amount);
                long expires = sec.getLong(key + ".expiresAt");
                long created = sec.getLong(key + ".createdAt", expires);
                Order o = new Order(buyer, buyerName, mat, price, amount, expires, created);
                o.amountTotal = total;

                ConfigurationSection enchSec = sec.getConfigurationSection(key + ".enchants");
                if (enchSec != null) {
                    for (String enchKey : enchSec.getKeys(false)) {
                        NamespacedKey nk = NamespacedKey.fromString(enchKey.replace(",", ":"));
                        if (nk == null) continue;
                        Enchantment ench = Registry.ENCHANTMENT.get(nk);
                        if (ench != null) {
                            o.enchants.put(ench, enchSec.getInt(enchKey));
                        }
                    }
                }
                orders.put(UUID.fromString(key), o);
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
            data.set(path + ".total", o.amountTotal);
            data.set(path + ".expiresAt", o.expiresAt);
            data.set(path + ".createdAt", o.createdAt);
            for (Map.Entry<Enchantment, Integer> e : o.enchants.entrySet()) {
                // YAML keys can't contain ':', so store namespace/key separated by ','
                String safeKey = e.getKey().getKey().toString().replace(":", ",");
                data.set(path + ".enchants." + safeKey, e.getValue());
            }
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

    private String msg(String key, String... placeholders) {
        return plugin.getMessages().rawFormatted(key, placeholders);
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
    // Main board GUI
    // ---------------------------------------------------------------

    public void open(Player p, int page) {
        int size = plugin.getConfig().getInt("ordersystem.gui-size", 54);
        int perPage = plugin.getConfig().getInt("ordersystem.orders-per-page", 45);

        SortMode sort = playerSort.getOrDefault(p.getUniqueId(), SortMode.RECENT);
        ItemCategory filter = playerFilter.getOrDefault(p.getUniqueId(), ItemCategory.ALL);

        List<Order> all = new ArrayList<>(orders.values());
        all.removeIf(o -> !ItemCategory.of(o.material).matches(filter));
        switch (sort) {
            case PRICE_HIGH -> all.sort((a, b) -> Double.compare(b.pricePerItem, a.pricePerItem));
            case PRICE_LOW -> all.sort(Comparator.comparingDouble(a -> a.pricePerItem));
            case RECENT -> all.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        }

        int totalPages = Math.max(1, (int) Math.ceil(all.size() / (double) perPage));
        page = Math.max(0, Math.min(page, totalPages - 1));
        playerPage.put(p.getUniqueId(), page);

        Inventory inv = Bukkit.createInventory(null, size,
                ItemBuilder.colorize(msg("ordersystem.gui-page-title", "page", String.valueOf(page + 1))));

        int start = page * perPage;
        int end = Math.min(start + perPage, all.size());
        int slot = 0;
        for (int i = start; i < end; i++) {
            Order o = all.get(i);
            boolean own = o.buyer.equals(p.getUniqueId());
            inv.setItem(slot++, buildOrderIcon(o, own));
        }

        int row = size - 9;
        if (page > 0) inv.setItem(row, control(Material.ARROW, msg("ordersystem.gui-prev-page"), null, "prev"));

        inv.setItem(row + 1, control(Material.HOPPER, filter.display(), msg("ordersystem.gui-filter-lore"), "filter"));
        inv.setItem(row + 2, control(Material.CAULDRON, sort.display(), msg("ordersystem.gui-sort-lore"), "sort"));
        inv.setItem(row + 3, control(Material.ANVIL, msg("ordersystem.gui-refresh"), msg("ordersystem.gui-refresh-lore"), "refresh"));
        inv.setItem(row + 4, control(Material.PAPER, msg("ordersystem.gui-new-order-button"), msg("ordersystem.gui-new-order-button-lore"), "new"));
        inv.setItem(row + 5, control(Material.CHEST, msg("ordersystem.gui-your-orders-button"), msg("ordersystem.gui-your-orders-button-lore"), "yours"));

        inv.setItem(row + 6, new ItemBuilder(Material.PAPER)
                .name(msg("ordersystem.gui-page-lore", "page", String.valueOf(page + 1), "pages", String.valueOf(totalPages)))
                .build());

        if (page < totalPages - 1) inv.setItem(row + 8, control(Material.ARROW, msg("ordersystem.gui-next-page"), null, "next"));

        mainInventories.add(inv);
        p.openInventory(inv);
    }

    private ItemStack buildOrderIcon(Order o, boolean own) {
        List<String> loreLines = new ArrayList<>();
        loreLines.add(msg("ordersystem.order-lore-buyer", "buyer", o.buyerName));
        loreLines.add(msg("ordersystem.order-lore-price", "price", fmt(o.pricePerItem)));
        loreLines.add(msg("ordersystem.order-lore-remaining", "remaining", String.valueOf(o.amountRemaining)));
        for (Map.Entry<Enchantment, Integer> e : o.enchants.entrySet()) {
            loreLines.add(msg("ordersystem.order-enchant-line",
                    "enchant", EnchantUtil.displayName(e.getKey()), "level", EnchantUtil.roman(e.getValue())));
        }
        loreLines.add(msg(own ? "ordersystem.order-lore-own" : "ordersystem.order-lore-fulfil"));

        ItemStack display = new ItemBuilder(o.material).name("&f" + o.material.name()).lore(loreLines).build();
        ItemMeta meta = display.getItemMeta();
        for (Map.Entry<Enchantment, Integer> e : o.enchants.entrySet()) {
            meta.addEnchant(e.getKey(), e.getValue(), true);
        }
        meta.getPersistentDataContainer().set(orderKey, PersistentDataType.STRING, o.id.toString());
        display.setItemMeta(meta);
        return display;
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

    private void fillBorder(Inventory inv, int... freeSlots) {
        Set<Integer> free = new HashSet<>();
        for (int s : freeSlots) free.add(s);
        ItemStack pane = new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE).name(" ").build();
        for (int i = 0; i < inv.getSize(); i++) {
            if (free.contains(i)) continue;
            inv.setItem(i, pane);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        Inventory top = event.getView().getTopInventory();

        if (mainInventories.contains(top)) {
            handleMainClick(event, p);
        } else if (draftInventories.contains(top)) {
            handleDraftClick(event, p);
        } else if (deliverInventories.contains(top)) {
            handleDeliverClick(event, p);
        } else if (yourOrdersInventories.contains(top)) {
            handleYourOrdersClick(event, p);
        } else if (browserInventories.contains(top)) {
            handleBrowserClick(event, p);
        } else if (enchantInventories.contains(top)) {
            handleEnchantClick(event, p);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        mainInventories.remove(event.getInventory());
        draftInventories.remove(event.getInventory());
        yourOrdersInventories.remove(event.getInventory());
        browserInventories.remove(event.getInventory());
        enchantInventories.remove(event.getInventory());

        if (deliverInventories.remove(event.getInventory()) && event.getPlayer() instanceof Player p) {
            ItemStack placed = event.getInventory().getItem(ITEM_SLOT);
            if (placed != null && placed.getType() != Material.AIR
                    && placed.getType() != Material.LIGHT_GRAY_STAINED_GLASS_PANE) {
                p.getInventory().addItem(placed);
            }
        }
    }

    private void handleMainClick(InventoryClickEvent event, Player p) {
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getView().getTopInventory())) {
            return;
        }
        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        String control = clicked.getItemMeta().getPersistentDataContainer().get(controlKey, PersistentDataType.STRING);
        if (control != null) {
            int page = playerPage.getOrDefault(p.getUniqueId(), 0);
            switch (control) {
                case "next" -> open(p, page + 1);
                case "prev" -> open(p, page - 1);
                case "refresh" -> open(p, page);
                case "sort" -> {
                    playerSort.put(p.getUniqueId(), playerSort.getOrDefault(p.getUniqueId(), SortMode.RECENT).next());
                    open(p, page);
                }
                case "filter" -> {
                    playerFilter.put(p.getUniqueId(), playerFilter.getOrDefault(p.getUniqueId(), ItemCategory.ALL).next());
                    open(p, 0);
                }
                case "new" -> startNewOrder(p);
                case "yours" -> openYourOrders(p);
            }
            return;
        }

        String idStr = clicked.getItemMeta().getPersistentDataContainer().get(orderKey, PersistentDataType.STRING);
        if (idStr == null) return;
        UUID id = UUID.fromString(idStr);
        Order order = orders.get(id);
        if (order == null) {
            plugin.getMessages().send(p, "ordersystem.not-available");
            return;
        }
        if (order.buyer.equals(p.getUniqueId())) {
            openYourOrders(p);
            return;
        }
        openDeliverGui(p, order);
    }

    private void startNewOrder(Player p) {
        long active = countActiveOrders(p.getUniqueId());
        int maxSlots = getMaxSlots(p);
        if (active >= maxSlots) {
            plugin.getMessages().send(p, "ordersystem.slots-full", "active", String.valueOf(active), "max", String.valueOf(maxSlots));
            return;
        }
        drafts.put(p.getUniqueId(), new OrderDraft());
        browserPage.put(p.getUniqueId(), 0);
        openItemBrowser(p);
    }

    // ---------------------------------------------------------------
    // Item browser (search + category filter across every item/block)
    // ---------------------------------------------------------------

    private void openItemBrowser(Player p) {
        int size = 54;
        int perPage = 45;

        ItemCategory filter = browserFilter.getOrDefault(p.getUniqueId(), ItemCategory.ALL);
        String search = browserSearch.get(p.getUniqueId());

        List<Material> filtered = new ArrayList<>();
        for (Material m : orderableMaterials) {
            if (!ItemCategory.of(m).matches(filter)) continue;
            if (search != null && !search.isEmpty()
                    && !m.name().toLowerCase(Locale.ROOT).replace('_', ' ').contains(search.toLowerCase(Locale.ROOT))) {
                continue;
            }
            filtered.add(m);
        }

        int totalPages = Math.max(1, (int) Math.ceil(filtered.size() / (double) perPage));
        int page = Math.max(0, Math.min(browserPage.getOrDefault(p.getUniqueId(), 0), totalPages - 1));
        browserPage.put(p.getUniqueId(), page);

        Inventory inv = Bukkit.createInventory(null, size, ItemBuilder.colorize(msg("ordersystem.gui-browse-title")));

        int start = page * perPage;
        int end = Math.min(start + perPage, filtered.size());
        int slot = 0;
        for (int i = start; i < end; i++) {
            Material m = filtered.get(i);
            ItemStack item = new ItemBuilder(m).name("&f" + m.name()).build();
            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().set(materialKey, PersistentDataType.STRING, m.name());
            item.setItemMeta(meta);
            inv.setItem(slot++, item);
        }

        int row = size - 9;
        if (page > 0) inv.setItem(row, control(Material.ARROW, msg("ordersystem.gui-prev-page"), null, "prev"));

        inv.setItem(row + 1, control(Material.HOPPER, filter.display(), msg("ordersystem.gui-filter-lore"), "filter"));

        if (search != null && !search.isEmpty()) {
            inv.setItem(row + 2, control(Material.COMPASS,
                    msg("ordersystem.gui-browse-search-active", "query", search),
                    msg("ordersystem.gui-browse-search-active-lore"), "search"));
        } else {
            inv.setItem(row + 2, control(Material.COMPASS, msg("ordersystem.gui-browse-search-button"),
                    msg("ordersystem.gui-browse-search-lore"), "search"));
        }

        inv.setItem(row + 3, control(Material.BARRIER, msg("ordersystem.gui-browse-back"), null, "back"));

        inv.setItem(row + 6, new ItemBuilder(Material.PAPER)
                .name(msg("ordersystem.gui-page-lore", "page", String.valueOf(page + 1), "pages", String.valueOf(totalPages)))
                .build());

        if (page < totalPages - 1) inv.setItem(row + 8, control(Material.ARROW, msg("ordersystem.gui-next-page"), null, "next"));

        browserInventories.add(inv);
        p.openInventory(inv);
    }

    private void handleBrowserClick(InventoryClickEvent event, Player p) {
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getView().getTopInventory())) {
            return;
        }
        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        String control = clicked.getItemMeta().getPersistentDataContainer().get(controlKey, PersistentDataType.STRING);
        if (control != null) {
            switch (control) {
                case "next" -> {
                    browserPage.merge(p.getUniqueId(), 1, Integer::sum);
                    openItemBrowser(p);
                }
                case "prev" -> {
                    browserPage.merge(p.getUniqueId(), -1, Integer::sum);
                    openItemBrowser(p);
                }
                case "filter" -> {
                    browserFilter.put(p.getUniqueId(), browserFilter.getOrDefault(p.getUniqueId(), ItemCategory.ALL).next());
                    browserPage.put(p.getUniqueId(), 0);
                    openItemBrowser(p);
                }
                case "search" -> {
                    String current = browserSearch.get(p.getUniqueId());
                    if (current != null && !current.isEmpty()) {
                        browserSearch.remove(p.getUniqueId());
                        browserPage.put(p.getUniqueId(), 0);
                        openItemBrowser(p);
                    } else {
                        browserInventories.remove(event.getView().getTopInventory());
                        p.closeInventory();
                        plugin.getMessages().send(p, "ordersystem.prompt-search");
                        plugin.getChatInput().awaitInput(p, input -> onSearchEntered(p, input));
                    }
                }
                case "back" -> {
                    drafts.remove(p.getUniqueId());
                    browserInventories.remove(event.getView().getTopInventory());
                    p.closeInventory();
                }
            }
            return;
        }

        String matName = clicked.getItemMeta().getPersistentDataContainer().get(materialKey, PersistentDataType.STRING);
        if (matName == null) return;
        Material material = Material.valueOf(matName);

        OrderDraft draft = drafts.get(p.getUniqueId());
        if (draft == null) {
            draft = new OrderDraft();
            drafts.put(p.getUniqueId(), draft);
        }
        draft.material = material;
        draft.enchants.clear();

        if (ItemCategory.of(material) == ItemCategory.ARMOR) {
            openEnchantSelector(p);
        } else {
            renderNewOrderDraft(p, draft);
        }
    }

    private void onSearchEntered(Player p, String input) {
        if (input == null || input.equalsIgnoreCase("cancel")) {
            openItemBrowser(p);
            return;
        }
        if (input.equalsIgnoreCase("clear")) {
            browserSearch.remove(p.getUniqueId());
        } else {
            browserSearch.put(p.getUniqueId(), input.trim());
        }
        browserPage.put(p.getUniqueId(), 0);
        openItemBrowser(p);
    }

    // ---------------------------------------------------------------
    // Enchantment selector (armor only)
    // ---------------------------------------------------------------

    private List<Enchantment> applicableEnchants(Material material) {
        List<Enchantment> list = new ArrayList<>();
        ItemStack sample = new ItemStack(material);
        for (Enchantment e : Registry.ENCHANTMENT) {
            if (e.canEnchantItem(sample)) {
                list.add(e);
            }
        }
        list.sort(Comparator.comparing(e -> EnchantUtil.displayName(e)));
        return list;
    }

    private void openEnchantSelector(Player p) {
        OrderDraft draft = drafts.get(p.getUniqueId());
        if (draft == null || draft.material == null) return;

        List<Enchantment> applicable = applicableEnchants(draft.material);

        int size = 54;
        Inventory inv = Bukkit.createInventory(null, size, ItemBuilder.colorize(msg("ordersystem.gui-enchant-title")));

        int slot = 0;
        for (Enchantment ench : applicable) {
            if (slot >= 45) break;
            int level = draft.enchants.getOrDefault(ench, 0);
            String lore = level > 0
                    ? msg("ordersystem.gui-enchant-level-lore", "level", EnchantUtil.roman(level))
                    : msg("ordersystem.gui-enchant-level-none-lore");

            ItemStack item = new ItemBuilder(level > 0 ? Material.ENCHANTED_BOOK : Material.BOOK)
                    .name((level > 0 ? "&d" : "&7") + EnchantUtil.displayName(ench))
                    .lore(List.of(lore))
                    .build();
            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().set(enchantKey, PersistentDataType.STRING, ench.getKey().toString());
            item.setItemMeta(meta);
            inv.setItem(slot++, item);
        }

        int row = size - 9;
        inv.setItem(row, control(Material.BARRIER, msg("ordersystem.gui-enchant-back"), null, "back"));
        inv.setItem(row + 4, control(Material.RED_DYE, msg("ordersystem.gui-enchant-clear"),
                msg("ordersystem.gui-enchant-clear-lore"), "clear"));
        inv.setItem(row + 8, control(Material.LIME_CONCRETE, msg("ordersystem.gui-enchant-continue"),
                msg("ordersystem.gui-enchant-continue-lore"), "continue"));

        enchantInventories.add(inv);
        p.openInventory(inv);
    }

    private void handleEnchantClick(InventoryClickEvent event, Player p) {
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getView().getTopInventory())) {
            return;
        }
        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        OrderDraft draft = drafts.get(p.getUniqueId());
        if (draft == null) return;

        String control = clicked.getItemMeta().getPersistentDataContainer().get(controlKey, PersistentDataType.STRING);
        if (control != null) {
            switch (control) {
                case "back" -> openItemBrowser(p);
                case "clear" -> {
                    draft.enchants.clear();
                    openEnchantSelector(p);
                }
                case "continue" -> renderNewOrderDraft(p, draft);
            }
            return;
        }

        String enchKeyStr = clicked.getItemMeta().getPersistentDataContainer().get(enchantKey, PersistentDataType.STRING);
        if (enchKeyStr == null) return;
        NamespacedKey nk = NamespacedKey.fromString(enchKeyStr);
        if (nk == null) return;
        Enchantment ench = Registry.ENCHANTMENT.get(nk);
        if (ench == null) return;

        int current = draft.enchants.getOrDefault(ench, 0);
        boolean rightClick = event.isRightClick();
        int max = ench.getMaxLevel();
        int next;
        if (rightClick) {
            next = current <= 0 ? max : current - 1;
        } else {
            next = current >= max ? 0 : current + 1;
        }
        if (next <= 0) {
            draft.enchants.remove(ench);
        } else {
            draft.enchants.put(ench, next);
        }
        openEnchantSelector(p);
    }

    // ---------------------------------------------------------------
    // Draft screen (amount / price / confirm)
    // ---------------------------------------------------------------

    private void renderNewOrderDraft(Player p, OrderDraft draft) {
        Inventory inv = Bukkit.createInventory(null, 27, ItemBuilder.colorize(msg("ordersystem.gui-new-order-title")));
        fillBorder(inv, ITEM_SLOT, AMOUNT_SLOT, PRICE_SLOT, CHANGE_ITEM_SLOT, CHANGE_ENCHANTS_SLOT, CANCEL_SLOT, CONFIRM_SLOT);

        List<String> itemLore = new ArrayList<>();
        itemLore.add(msg("ordersystem.gui-item-icon-lore", "item", draft.material.name()));
        for (Map.Entry<Enchantment, Integer> e : draft.enchants.entrySet()) {
            itemLore.add(msg("ordersystem.order-enchant-line", "enchant", EnchantUtil.displayName(e.getKey()), "level", EnchantUtil.roman(e.getValue())));
        }
        ItemStack icon = new ItemBuilder(draft.material).name("&f" + draft.material.name()).lore(itemLore).build();
        ItemMeta iconMeta = icon.getItemMeta();
        for (Map.Entry<Enchantment, Integer> e : draft.enchants.entrySet()) {
            iconMeta.addEnchant(e.getKey(), e.getValue(), true);
        }
        icon.setItemMeta(iconMeta);
        inv.setItem(ITEM_SLOT, icon);

        inv.setItem(AMOUNT_SLOT, control(Material.HOPPER, msg("ordersystem.gui-amount-button"),
                draft.amount == null ? msg("ordersystem.gui-amount-unset-lore") : msg("ordersystem.gui-amount-set-lore", "amount", String.valueOf(draft.amount)),
                "amount"));
        inv.setItem(PRICE_SLOT, control(Material.GOLD_INGOT, msg("ordersystem.gui-price-button"),
                draft.price == null ? msg("ordersystem.gui-price-unset-lore") : msg("ordersystem.gui-price-set-lore", "price", fmt(draft.price)),
                "price"));

        inv.setItem(CHANGE_ITEM_SLOT, control(Material.COMPASS, msg("ordersystem.gui-change-item"),
                msg("ordersystem.gui-change-item-lore"), "change-item"));
        if (ItemCategory.of(draft.material) == ItemCategory.ARMOR) {
            inv.setItem(CHANGE_ENCHANTS_SLOT, control(Material.ENCHANTED_BOOK, msg("ordersystem.gui-change-enchants"),
                    msg("ordersystem.gui-change-enchants-lore"), "change-enchants"));
        }

        inv.setItem(CANCEL_SLOT, control(Material.RED_CONCRETE, msg("ordersystem.gui-order-cancel"),
                msg("ordersystem.gui-order-cancel-lore"), "cancel"));

        String confirmLore = draft.amount != null && draft.price != null
                ? msg("ordersystem.gui-total-lore", "total", fmt(draft.amount * draft.price))
                : msg("ordersystem.gui-order-confirm-lore");
        inv.setItem(CONFIRM_SLOT, control(Material.LIME_CONCRETE, msg("ordersystem.gui-order-confirm"), confirmLore, "confirm"));

        draftInventories.add(inv);
        p.openInventory(inv);
    }

    private void handleDraftClick(InventoryClickEvent event, Player p) {
        event.setCancelled(true);
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getView().getTopInventory())) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        String control = clicked.getItemMeta().getPersistentDataContainer().get(controlKey, PersistentDataType.STRING);
        if (control == null) return;

        OrderDraft draft = drafts.get(p.getUniqueId());
        if (draft == null) return;

        switch (control) {
            case "cancel" -> {
                drafts.remove(p.getUniqueId());
                draftInventories.remove(event.getView().getTopInventory());
                p.closeInventory();
                plugin.getMessages().send(p, "ordersystem.cancelled-input");
            }
            case "change-item" -> {
                draftInventories.remove(event.getView().getTopInventory());
                browserPage.put(p.getUniqueId(), 0);
                openItemBrowser(p);
            }
            case "change-enchants" -> {
                draftInventories.remove(event.getView().getTopInventory());
                openEnchantSelector(p);
            }
            case "amount" -> {
                draftInventories.remove(event.getView().getTopInventory());
                p.closeInventory();
                plugin.getMessages().send(p, "ordersystem.prompt-amount");
                plugin.getChatInput().awaitInput(p, input -> onAmountEntered(p, input));
            }
            case "price" -> {
                draftInventories.remove(event.getView().getTopInventory());
                p.closeInventory();
                plugin.getMessages().send(p, "ordersystem.prompt-price");
                plugin.getChatInput().awaitInput(p, input -> onPriceEntered(p, input));
            }
            case "confirm" -> confirmOrder(p, draft);
        }
    }

    private void onAmountEntered(Player p, String input) {
        OrderDraft draft = drafts.get(p.getUniqueId());
        if (draft == null) return;
        if (input == null) {
            plugin.getMessages().send(p, "ordersystem.cancelled-input");
            renderNewOrderDraft(p, draft);
            return;
        }
        try {
            int amount = Integer.parseInt(input.trim());
            if (amount <= 0) throw new NumberFormatException();
            draft.amount = amount;
        } catch (NumberFormatException e) {
            plugin.getMessages().send(p, "ordersystem.invalid-amount");
        }
        renderNewOrderDraft(p, draft);
    }

    private void onPriceEntered(Player p, String input) {
        OrderDraft draft = drafts.get(p.getUniqueId());
        if (draft == null) return;
        if (input == null) {
            plugin.getMessages().send(p, "ordersystem.cancelled-input");
            renderNewOrderDraft(p, draft);
            return;
        }
        try {
            double price = Double.parseDouble(input.trim());
            double min = plugin.getConfig().getDouble("ordersystem.min-price", 1.0);
            double max = plugin.getConfig().getDouble("ordersystem.max-price", 1000000.0);
            if (price < min || price > max) {
                plugin.getMessages().send(p, "ordersystem.price-range");
            } else {
                draft.price = price;
            }
        } catch (NumberFormatException e) {
            plugin.getMessages().send(p, "ordersystem.invalid-price");
        }
        renderNewOrderDraft(p, draft);
    }

    private void confirmOrder(Player p, OrderDraft draft) {
        if (draft.amount == null) {
            plugin.getMessages().send(p, "ordersystem.set-amount-first");
            return;
        }
        if (draft.price == null) {
            plugin.getMessages().send(p, "ordersystem.set-price-first");
            return;
        }

        long active = countActiveOrders(p.getUniqueId());
        int maxSlots = getMaxSlots(p);
        if (active >= maxSlots) {
            plugin.getMessages().send(p, "ordersystem.slots-full", "active", String.valueOf(active), "max", String.valueOf(maxSlots));
            return;
        }

        double totalCost = draft.amount * draft.price;
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
        long now = System.currentTimeMillis();
        Order order = new Order(p.getUniqueId(), p.getName(), draft.material, draft.price, draft.amount,
                now + durationHours * 3600_000L, now);
        order.enchants.putAll(draft.enchants);
        orders.put(order.id, order);
        save();

        drafts.remove(p.getUniqueId());
        p.closeInventory();
        plugin.getMessages().send(p, "ordersystem.created",
                "amount", String.valueOf(draft.amount), "item", draft.material.name(), "price", fmt(draft.price),
                "active", String.valueOf(active + 1), "max", String.valueOf(maxSlots));
    }

    // ---------------------------------------------------------------
    // Your Orders screen
    // ---------------------------------------------------------------

    private void openYourOrders(Player p) {
        int size = 54;
        Inventory inv = Bukkit.createInventory(null, size, ItemBuilder.colorize(msg("ordersystem.gui-your-orders-title")));

        List<Order> mine = orders.values().stream().filter(o -> o.buyer.equals(p.getUniqueId())).toList();
        if (mine.isEmpty()) {
            inv.setItem(22, new ItemBuilder(Material.BARRIER).name(msg("ordersystem.your-orders-empty")).build());
        } else {
            int slot = 0;
            for (Order o : mine) {
                if (slot >= size) break;
                List<String> lore = new ArrayList<>();
                lore.add(msg("ordersystem.your-orders-entry-lore", "remaining", String.valueOf(o.amountRemaining), "price", fmt(o.pricePerItem)));
                for (Map.Entry<Enchantment, Integer> e : o.enchants.entrySet()) {
                    lore.add(msg("ordersystem.order-enchant-line", "enchant", EnchantUtil.displayName(e.getKey()), "level", EnchantUtil.roman(e.getValue())));
                }
                lore.add(msg("ordersystem.your-orders-cancel-lore"));

                ItemStack item = new ItemBuilder(o.material).name("&f" + o.material.name()).lore(lore).build();
                ItemMeta meta = item.getItemMeta();
                for (Map.Entry<Enchantment, Integer> e : o.enchants.entrySet()) {
                    meta.addEnchant(e.getKey(), e.getValue(), true);
                }
                meta.getPersistentDataContainer().set(orderKey, PersistentDataType.STRING, o.id.toString());
                item.setItemMeta(meta);
                inv.setItem(slot++, item);
            }
        }

        yourOrdersInventories.add(inv);
        p.openInventory(inv);
    }

    private void handleYourOrdersClick(InventoryClickEvent event, Player p) {
        event.setCancelled(true);
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getView().getTopInventory())) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        String idStr = clicked.getItemMeta().getPersistentDataContainer().get(orderKey, PersistentDataType.STRING);
        if (idStr == null) return;

        UUID id = UUID.fromString(idStr);
        Order o = orders.get(id);
        if (o == null || !o.buyer.equals(p.getUniqueId())) {
            plugin.getMessages().send(p, "ordersystem.not-found");
            return;
        }

        Economy econ = economy();
        if (econ != null) {
            econ.depositPlayer(p, o.pricePerItem * o.amountRemaining);
        }
        orders.remove(id);
        save();
        plugin.getMessages().send(p, "ordersystem.cancelled");
        openYourOrders(p);
    }

    // ---------------------------------------------------------------
    // Deliver Items screen (fulfilling someone else's order)
    // ---------------------------------------------------------------

    private void openDeliverGui(Player p, Order order) {
        Inventory inv = Bukkit.createInventory(null, 27, ItemBuilder.colorize(msg("ordersystem.gui-deliver-title")));
        fillBorder(inv, ITEM_SLOT, 11, 15);

        inv.setItem(ITEM_SLOT, new ItemBuilder(Material.LIGHT_GRAY_STAINED_GLASS_PANE)
                .name(msg("ordersystem.gui-deliver-slot-empty"))
                .lore(List.of(msg("ordersystem.gui-deliver-slot-lore", "item", order.material.name())))
                .build());

        inv.setItem(11, control(Material.RED_CONCRETE, msg("ordersystem.gui-deliver-cancel"),
                msg("ordersystem.gui-deliver-cancel-lore"), "cancel"));
        inv.setItem(15, control(Material.LIME_CONCRETE, msg("ordersystem.gui-deliver-confirm"),
                msg("ordersystem.gui-deliver-confirm-lore"), "confirm"));

        deliverTarget.put(p.getUniqueId(), order.id);
        deliverInventories.add(inv);
        p.openInventory(inv);
    }

    private void handleDeliverClick(InventoryClickEvent event, Player p) {
        Inventory top = event.getView().getTopInventory();
        boolean clickedTop = event.getClickedInventory() != null && event.getClickedInventory().equals(top);

        if (!clickedTop) return;
        if (event.getSlot() == ITEM_SLOT) {
            ItemStack cursor = event.getCursor();
            if (cursor != null && cursor.getType() != Material.AIR
                    && top.getItem(ITEM_SLOT) != null
                    && top.getItem(ITEM_SLOT).getType() == Material.LIGHT_GRAY_STAINED_GLASS_PANE) {
                top.setItem(ITEM_SLOT, null);
            }
            return;
        }

        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        String control = clicked.getItemMeta().getPersistentDataContainer().get(controlKey, PersistentDataType.STRING);
        if (control == null) return;

        UUID orderId = deliverTarget.get(p.getUniqueId());
        Order order = orderId == null ? null : orders.get(orderId);

        if (control.equals("cancel")) {
            ItemStack placed = top.getItem(ITEM_SLOT);
            if (placed != null && placed.getType() != Material.AIR
                    && placed.getType() != Material.LIGHT_GRAY_STAINED_GLASS_PANE) {
                p.getInventory().addItem(placed);
            }
            deliverInventories.remove(top);
            p.closeInventory();
            return;
        }

        if (control.equals("confirm")) {
            deliverInventories.remove(top);
            ItemStack placed = top.getItem(ITEM_SLOT);
            p.closeInventory();

            if (order == null) {
                plugin.getMessages().send(p, "ordersystem.not-available");
                if (placed != null && placed.getType() != Material.AIR
                        && placed.getType() != Material.LIGHT_GRAY_STAINED_GLASS_PANE) {
                    p.getInventory().addItem(placed);
                }
                return;
            }
            if (placed == null || placed.getType() == Material.AIR
                    || placed.getType() == Material.LIGHT_GRAY_STAINED_GLASS_PANE) {
                plugin.getMessages().send(p, "ordersystem.deliver-nothing");
                return;
            }
            if (placed.getType() != order.material) {
                plugin.getMessages().send(p, "ordersystem.deliver-wrong-item", "item", order.material.name());
                p.getInventory().addItem(placed);
                return;
            }
            if (!meetsEnchantRequirements(placed, order.enchants)) {
                plugin.getMessages().send(p, "ordersystem.deliver-wrong-enchant");
                p.getInventory().addItem(placed);
                return;
            }

            fulfil(p, order, placed);
        }
    }

    private boolean meetsEnchantRequirements(ItemStack placed, Map<Enchantment, Integer> required) {
        if (required.isEmpty()) return true;
        ItemMeta meta = placed.getItemMeta();
        if (meta == null) return false;
        for (Map.Entry<Enchantment, Integer> req : required.entrySet()) {
            int have = meta.getEnchantLevel(req.getKey());
            if (have < req.getValue()) return false;
        }
        return true;
    }

    private void fulfil(Player p, Order order, ItemStack placed) {
        int sellAmount = Math.min(placed.getAmount(), order.amountRemaining);
        int excess = placed.getAmount() - sellAmount;

        Economy econ = economy();
        if (econ == null) {
            plugin.getMessages().send(p, "ordersystem.no-economy");
            p.getInventory().addItem(placed);
            return;
        }

        double payout = sellAmount * order.pricePerItem;
        econ.depositPlayer(p, payout);
        order.amountRemaining -= sellAmount;

        plugin.getMessages().send(p, "ordersystem.sold",
                "amount", String.valueOf(sellAmount), "item", order.material.name(), "payout", fmt(payout));

        if (excess > 0) {
            ItemStack leftover = placed.clone();
            leftover.setAmount(excess);
            p.getInventory().addItem(leftover);
            plugin.getMessages().send(p, "ordersystem.deliver-excess-returned");
        }

        Player buyer = Bukkit.getPlayer(order.buyer);
        ItemStack delivered = placed.clone();
        delivered.setAmount(sellAmount);
        if (buyer != null) {
            buyer.getInventory().addItem(delivered);
        } else {
            p.getWorld().dropItemNaturally(p.getLocation(), delivered);
        }

        if (order.amountRemaining <= 0) {
            orders.remove(order.id);
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
