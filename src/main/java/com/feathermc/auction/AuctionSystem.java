package com.feathermc.auction;

import com.feathermc.FeatherMC;
import com.feathermc.utils.ItemBuilder;
import com.feathermc.utils.ItemCategory;
import com.feathermc.utils.SortMode;
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
 * Auction House GUI ("/feathermc ah"), styled after DonutSMP's /ah:
 * a paginated grid of listings with a bottom control bar (refresh / sort /
 * category filter / sell / page arrows), and a two-step "place item ->
 * confirm -> type price in chat -> confirm again" listing flow.
 *
 * Live-listing counts are capped using whichever LuckPerms-granted
 * permission (auctionhouse.slot-permissions in config.yml) gives the
 * player the highest number of slots.
 */
public class AuctionSystem implements Listener {

    private final FeatherMC plugin;
    private final NamespacedKey listingKey;
    private final NamespacedKey controlKey;
    private final File file;
    private final org.bukkit.configuration.file.YamlConfiguration data;

    public static class Listing {
        final UUID id = UUID.randomUUID();
        UUID seller;
        String sellerName;
        ItemStack item;
        double price;
        long expiresAt;
        long listedAt;

        Listing(UUID seller, String sellerName, ItemStack item, double price, long expiresAt, long listedAt) {
            this.seller = seller;
            this.sellerName = sellerName;
            this.item = item;
            this.price = price;
            this.expiresAt = expiresAt;
            this.listedAt = listedAt;
        }
    }

    /** Tracks a player's in-progress "list an item" flow between GUI screens. */
    private static class SellDraft {
        ItemStack item;
        double price;
    }

    private final Map<UUID, Listing> listings = new LinkedHashMap<>();
    private final Map<UUID, Integer> playerPage = new HashMap<>();
    private final Map<UUID, SortMode> playerSort = new HashMap<>();
    private final Map<UUID, ItemCategory> playerFilter = new HashMap<>();
    private final Map<UUID, SellDraft> sellDrafts = new HashMap<>();

    private final Set<Inventory> mainInventories = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Inventory> sellInventories = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Inventory> confirmInventories = Collections.newSetFromMap(new WeakHashMap<>());
    private static final int ITEM_SLOT = 13;

    public AuctionSystem(FeatherMC plugin) {
        this.plugin = plugin;
        this.listingKey = new NamespacedKey(plugin, "ah_listing_id");
        this.controlKey = new NamespacedKey(plugin, "ah_control");
        this.file = new File(plugin.getDataFolder(), "auctions.yml");
        if (!file.exists()) {
            try {
                file.getParentFile().mkdirs();
                file.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create auctions.yml: " + e.getMessage());
            }
        }
        this.data = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        load();
    }

    private void load() {
        listings.clear();
        ConfigurationSection sec = data.getConfigurationSection("listings");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                UUID seller = UUID.fromString(sec.getString(key + ".seller"));
                String sellerName = sec.getString(key + ".sellerName", "Unknown");
                ItemStack item = sec.getItemStack(key + ".item");
                double price = sec.getDouble(key + ".price");
                long expires = sec.getLong(key + ".expiresAt");
                long listedAt = sec.getLong(key + ".listedAt", expires);
                listings.put(id, new Listing(seller, sellerName, item, price, expires, listedAt));
            } catch (Exception ignored) {
            }
        }
    }

    private void save() {
        data.set("listings", null);
        for (Listing l : listings.values()) {
            String path = "listings." + l.id;
            data.set(path + ".seller", l.seller.toString());
            data.set(path + ".sellerName", l.sellerName);
            data.set(path + ".item", l.item);
            data.set(path + ".price", l.price);
            data.set(path + ".expiresAt", l.expiresAt);
            data.set(path + ".listedAt", l.listedAt);
        }
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save auctions.yml: " + e.getMessage());
        }
    }

    private Economy economy() {
        var rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        return rsp == null ? null : rsp.getProvider();
    }

    public int getMaxSlots(Player p) {
        ConfigurationSection perms = plugin.getConfig().getConfigurationSection("auctionhouse.slot-permissions");
        int best = plugin.getConfig().getInt("auctionhouse.default-slots", 1);
        if (perms != null) {
            for (String perm : perms.getKeys(false)) {
                if (p.hasPermission(perm)) {
                    best = Math.max(best, perms.getInt(perm));
                }
            }
        }
        return best;
    }

    public long countActiveListings(UUID seller) {
        return listings.values().stream().filter(l -> l.seller.equals(seller)).count();
    }

    public void handle(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            plugin.getMessages().send(sender, "general.player-only");
            return;
        }
        if (!p.hasPermission("feathermc.ah.use")) {
            plugin.getMessages().send(p, "general.no-permission");
            return;
        }
        open(p, 0);
    }

    // ---------------------------------------------------------------
    // Main grid GUI
    // ---------------------------------------------------------------

    private String msg(String key, String... placeholders) {
        return plugin.getMessages().rawFormatted(key, placeholders);
    }

    public void open(Player p, int page) {
        int size = plugin.getConfig().getInt("auctionhouse.gui-size", 54);
        int perPage = plugin.getConfig().getInt("auctionhouse.listings-per-page", 45);

        SortMode sort = playerSort.getOrDefault(p.getUniqueId(), SortMode.RECENT);
        ItemCategory filter = playerFilter.getOrDefault(p.getUniqueId(), ItemCategory.ALL);

        List<Listing> all = new ArrayList<>(listings.values());
        all.removeIf(l -> !ItemCategory.of(l.item.getType()).matches(filter));
        switch (sort) {
            case PRICE_HIGH -> all.sort((a, b) -> Double.compare(b.price, a.price));
            case PRICE_LOW -> all.sort(Comparator.comparingDouble(a -> a.price));
            case RECENT -> all.sort((a, b) -> Long.compare(b.listedAt, a.listedAt));
        }

        int totalPages = Math.max(1, (int) Math.ceil(all.size() / (double) perPage));
        page = Math.max(0, Math.min(page, totalPages - 1));
        playerPage.put(p.getUniqueId(), page);

        Inventory inv = Bukkit.createInventory(null, size,
                ItemBuilder.colorize(msg("auctionhouse.gui-page-title", "page", String.valueOf(page + 1))));

        int start = page * perPage;
        int end = Math.min(start + perPage, all.size());
        int slot = 0;
        for (int i = start; i < end; i++) {
            Listing l = all.get(i);
            boolean own = l.seller.equals(p.getUniqueId());
            ItemStack display = l.item.clone();
            ItemMeta meta = display.getItemMeta();
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            lore.add(ItemBuilder.colorize(msg("auctionhouse.listing-lore-seller", "seller", l.sellerName)));
            lore.add(ItemBuilder.colorize(msg("auctionhouse.listing-lore-price", "price", fmt(l.price))));
            lore.add(ItemBuilder.colorize(msg(own ? "auctionhouse.listing-lore-own" : "auctionhouse.listing-lore-buy")));
            meta.lore(lore);
            meta.getPersistentDataContainer().set(listingKey, PersistentDataType.STRING, l.id.toString());
            display.setItemMeta(meta);
            inv.setItem(slot++, display);
        }

        int row = size - 9;
        if (page > 0) inv.setItem(row, control(Material.ARROW, msg("auctionhouse.gui-prev-page"), null, "prev"));

        inv.setItem(row + 1, control(Material.HOPPER, filter.display(),
                msg("auctionhouse.gui-filter-lore"), "filter"));
        inv.setItem(row + 2, control(Material.CAULDRON, sort.display(),
                msg("auctionhouse.gui-sort-lore"), "sort"));
        inv.setItem(row + 3, control(Material.ANVIL, msg("auctionhouse.gui-refresh"),
                msg("auctionhouse.gui-refresh-lore"), "refresh"));

        inv.setItem(row + 4, control(Material.CHEST, msg("auctionhouse.gui-sell-button"),
                msg("auctionhouse.gui-sell-button-lore"), "sell"));

        inv.setItem(row + 5, new ItemBuilder(Material.PAPER)
                .name(msg("auctionhouse.gui-page-lore", "page", String.valueOf(page + 1), "pages", String.valueOf(totalPages)))
                .build());

        if (page < totalPages - 1) inv.setItem(row + 8, control(Material.ARROW, msg("auctionhouse.gui-next-page"), null, "next"));

        mainInventories.add(inv);
        p.openInventory(inv);
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

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        Inventory top = event.getView().getTopInventory();

        if (mainInventories.contains(top)) {
            handleMainClick(event, p);
        } else if (sellInventories.contains(top)) {
            handleSellClick(event, p);
        } else if (confirmInventories.contains(top)) {
            handleConfirmClick(event, p);
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
                case "sell" -> openSellGui(p);
            }
            return;
        }

        String idStr = clicked.getItemMeta().getPersistentDataContainer().get(listingKey, PersistentDataType.STRING);
        if (idStr == null) return;
        purchase(p, UUID.fromString(idStr));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        mainInventories.remove(event.getInventory());

        if (sellInventories.remove(event.getInventory()) && event.getPlayer() instanceof Player p) {
            // return whatever item they'd placed if they closed without confirming
            ItemStack placed = event.getInventory().getItem(ITEM_SLOT);
            if (placed != null && placed.getType() != Material.AIR && !isPlaceholder(placed)) {
                p.getInventory().addItem(placed);
            }
        }
        confirmInventories.remove(event.getInventory());
    }

    // ---------------------------------------------------------------
    // Step 1: place item to sell
    // ---------------------------------------------------------------

    private void openSellGui(Player p) {
        if (p.getInventory().getItemInMainHand() == null || p.getInventory().getItemInMainHand().getType() == Material.AIR) {
            plugin.getMessages().send(p, "auctionhouse.no-item-held");
            return;
        }
        long active = countActiveListings(p.getUniqueId());
        int maxSlots = getMaxSlots(p);
        if (active >= maxSlots) {
            plugin.getMessages().send(p, "auctionhouse.slots-full", "active", String.valueOf(active), "max", String.valueOf(maxSlots));
            return;
        }

        Inventory inv = Bukkit.createInventory(null, 27, ItemBuilder.colorize(msg("auctionhouse.gui-sell-title")));
        fillBorder(inv);

        ItemStack held = p.getInventory().getItemInMainHand().clone();
        p.getInventory().setItemInMainHand(null);
        inv.setItem(ITEM_SLOT, held);

        inv.setItem(11, control(Material.RED_CONCRETE, msg("auctionhouse.gui-sell-cancel"),
                msg("auctionhouse.gui-sell-cancel-lore"), "cancel"));
        inv.setItem(15, control(Material.LIME_CONCRETE, msg("auctionhouse.gui-sell-confirm"),
                msg("auctionhouse.gui-sell-confirm-lore"), "continue"));

        sellInventories.add(inv);
        p.openInventory(inv);
    }

    private void fillBorder(Inventory inv) {
        ItemStack pane = new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE).name(" ").build();
        for (int i = 0; i < inv.getSize(); i++) {
            if (i == ITEM_SLOT) continue;
            inv.setItem(i, pane);
        }
    }

    private boolean isPlaceholder(ItemStack stack) {
        return stack.getType() == Material.GRAY_STAINED_GLASS_PANE
                || stack.getType() == Material.RED_CONCRETE
                || stack.getType() == Material.LIME_CONCRETE
                || stack.getType() == Material.PAPER;
    }

    private void handleSellClick(InventoryClickEvent event, Player p) {
        Inventory top = event.getView().getTopInventory();
        boolean clickedTop = event.getClickedInventory() != null && event.getClickedInventory().equals(top);

        if (!clickedTop) return; // let the player's own inventory behave normally
        if (event.getSlot() == ITEM_SLOT) return; // allow free movement in the item slot only

        event.setCancelled(true);

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        String control = clicked.getItemMeta().getPersistentDataContainer().get(controlKey, PersistentDataType.STRING);
        if (control == null) return;

        if (control.equals("cancel")) {
            ItemStack placed = top.getItem(ITEM_SLOT);
            if (placed != null && placed.getType() != Material.AIR) {
                p.getInventory().addItem(placed);
            }
            sellInventories.remove(top);
            p.closeInventory();
            return;
        }

        if (control.equals("continue")) {
            ItemStack placed = top.getItem(ITEM_SLOT);
            if (placed == null || placed.getType() == Material.AIR) {
                plugin.getMessages().send(p, "auctionhouse.no-item-held");
                return;
            }
            SellDraft draft = new SellDraft();
            draft.item = placed.clone();
            sellDrafts.put(p.getUniqueId(), draft);

            sellInventories.remove(top);
            top.setItem(ITEM_SLOT, null); // prevent double-return on close
            p.closeInventory();

            plugin.getMessages().send(p, "auctionhouse.prompt-price");
            plugin.getChatInput().awaitInput(p, input -> onPriceEntered(p, input));
        }
    }

    private void onPriceEntered(Player p, String input) {
        SellDraft draft = sellDrafts.get(p.getUniqueId());
        if (draft == null) return;

        if (input == null) {
            p.getInventory().addItem(draft.item);
            sellDrafts.remove(p.getUniqueId());
            plugin.getMessages().send(p, "auctionhouse.cancelled-input");
            return;
        }

        double price;
        try {
            price = Double.parseDouble(input.trim());
        } catch (NumberFormatException e) {
            plugin.getMessages().send(p, "auctionhouse.invalid-price");
            p.getInventory().addItem(draft.item);
            sellDrafts.remove(p.getUniqueId());
            return;
        }

        double min = plugin.getConfig().getDouble("auctionhouse.min-price", 1.0);
        double max = plugin.getConfig().getDouble("auctionhouse.max-price", 1000000.0);
        if (price < min || price > max) {
            plugin.getMessages().send(p, "auctionhouse.price-range", "min", fmt(min), "max", fmt(max));
            p.getInventory().addItem(draft.item);
            sellDrafts.remove(p.getUniqueId());
            return;
        }

        draft.price = price;
        openConfirmGui(p, draft);
    }

    // ---------------------------------------------------------------
    // Step 2: confirm listing
    // ---------------------------------------------------------------

    private void openConfirmGui(Player p, SellDraft draft) {
        Inventory inv = Bukkit.createInventory(null, 27, ItemBuilder.colorize(msg("auctionhouse.gui-confirm-title")));
        fillBorder(inv);

        ItemStack display = draft.item.clone();
        ItemMeta meta = display.getItemMeta();
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        if (meta.hasLore()) lore.addAll(meta.lore());
        lore.add(ItemBuilder.colorize(msg("auctionhouse.gui-confirm-item-lore-price", "price", fmt(draft.price))));
        meta.lore(lore);
        display.setItemMeta(meta);
        inv.setItem(ITEM_SLOT, display);

        inv.setItem(11, control(Material.RED_CONCRETE, msg("auctionhouse.gui-confirm-no"),
                msg("auctionhouse.gui-confirm-no-lore"), "no"));
        inv.setItem(15, control(Material.LIME_CONCRETE, msg("auctionhouse.gui-confirm-yes"),
                msg("auctionhouse.gui-confirm-yes-lore"), "yes"));

        confirmInventories.add(inv);
        p.openInventory(inv);
    }

    private void handleConfirmClick(InventoryClickEvent event, Player p) {
        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        String control = clicked.getItemMeta().getPersistentDataContainer().get(controlKey, PersistentDataType.STRING);
        if (control == null) return;

        SellDraft draft = sellDrafts.remove(p.getUniqueId());
        confirmInventories.remove(event.getView().getTopInventory());

        if (control.equals("no")) {
            if (draft != null) p.getInventory().addItem(draft.item);
            p.closeInventory();
            return;
        }

        if (control.equals("yes") && draft != null) {
            long active = countActiveListings(p.getUniqueId());
            int maxSlots = getMaxSlots(p);
            if (active >= maxSlots) {
                plugin.getMessages().send(p, "auctionhouse.slots-full", "active", String.valueOf(active), "max", String.valueOf(maxSlots));
                p.getInventory().addItem(draft.item);
                p.closeInventory();
                return;
            }

            long durationHours = plugin.getConfig().getLong("auctionhouse.listing-duration-hours", 48);
            long now = System.currentTimeMillis();
            Listing listing = new Listing(p.getUniqueId(), p.getName(), draft.item, draft.price,
                    now + durationHours * 3600_000L, now);
            listings.put(listing.id, listing);
            save();

            plugin.getMessages().send(p, "auctionhouse.listed",
                    "price", fmt(draft.price), "active", String.valueOf(active + 1), "max", String.valueOf(maxSlots));
            p.closeInventory();
        }
    }

    // ---------------------------------------------------------------
    // Purchasing
    // ---------------------------------------------------------------

    private void purchase(Player p, UUID id) {
        Listing listing = listings.get(id);
        if (listing == null) {
            plugin.getMessages().send(p, "auctionhouse.not-available");
            return;
        }
        if (listing.seller.equals(p.getUniqueId())) {
            listings.remove(id);
            p.getInventory().addItem(listing.item);
            plugin.getMessages().send(p, "auctionhouse.reclaimed");
            save();
            open(p, playerPage.getOrDefault(p.getUniqueId(), 0));
            return;
        }

        Economy econ = economy();
        if (econ == null) {
            plugin.getMessages().send(p, "auctionhouse.no-economy");
            return;
        }
        if (!econ.has(p, listing.price)) {
            plugin.getMessages().send(p, "auctionhouse.cant-afford", "price", fmt(listing.price));
            return;
        }

        econ.withdrawPlayer(p, listing.price);
        double tax = plugin.getConfig().getDouble("auctionhouse.tax-percent", 5.0) / 100.0;
        double payout = listing.price * (1 - tax);
        Player seller = Bukkit.getPlayer(listing.seller);
        if (seller != null) {
            econ.depositPlayer(seller, payout);
            plugin.getMessages().send(seller, "auctionhouse.sold-notify-seller", "amount", fmt(payout));
        } else {
            econ.depositPlayer(Bukkit.getOfflinePlayer(listing.seller), payout);
        }

        p.getInventory().addItem(listing.item);
        plugin.getMessages().send(p, "auctionhouse.purchased", "price", fmt(listing.price));
        listings.remove(id);
        save();
        open(p, playerPage.getOrDefault(p.getUniqueId(), 0));
    }

    public void purgeExpired() {
        long now = System.currentTimeMillis();
        Iterator<Listing> it = listings.values().iterator();
        boolean changed = false;
        while (it.hasNext()) {
            Listing l = it.next();
            if (l.expiresAt <= now) {
                Player seller = Bukkit.getPlayer(l.seller);
                if (seller != null && seller.isOnline()) {
                    seller.getInventory().addItem(l.item);
                    plugin.getMessages().send(seller, "auctionhouse.expired-returned");
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
