package com.feathermc.auction;

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
 * Auction House GUI ("/feathermc ah"). Players list items straight from the
 * menu: click "Sell Held Item", then type a price in chat. Live-listing
 * counts are capped using whichever LuckPerms-granted permission
 * (auctionhouse.slot-permissions in config.yml) gives the player the
 * highest number of slots.
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

        Listing(UUID seller, String sellerName, ItemStack item, double price, long expiresAt) {
            this.seller = seller;
            this.sellerName = sellerName;
            this.item = item;
            this.price = price;
            this.expiresAt = expiresAt;
        }
    }

    private final Map<UUID, Listing> listings = new LinkedHashMap<>();
    private final Map<UUID, Integer> playerPage = new HashMap<>();
    private final Set<Inventory> openAhInventories = Collections.newSetFromMap(new WeakHashMap<>());

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
                listings.put(id, new Listing(seller, sellerName, item, price, expires));
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
    // GUI
    // ---------------------------------------------------------------

    public void open(Player p, int page) {
        String title = plugin.getConfig().getString("auctionhouse.gui-title", plugin.getMessages().rawFormatted("auctionhouse.gui-title"));
        int size = plugin.getConfig().getInt("auctionhouse.gui-size", 54);
        int perPage = plugin.getConfig().getInt("auctionhouse.listings-per-page", 45);

        List<Listing> all = new ArrayList<>(listings.values());
        int totalPages = Math.max(1, (int) Math.ceil(all.size() / (double) perPage));
        page = Math.max(0, Math.min(page, totalPages - 1));
        playerPage.put(p.getUniqueId(), page);

        Inventory inv = Bukkit.createInventory(null, size,
                ItemBuilder.colorize(plugin.getMessages().rawFormatted("auctionhouse.gui-title")));

        int start = page * perPage;
        int end = Math.min(start + perPage, all.size());
        int slot = 0;
        for (int i = start; i < end; i++) {
            Listing l = all.get(i);
            ItemStack display = l.item.clone();
            ItemMeta meta = display.getItemMeta();
            meta.lore(List.of(
                    ItemBuilder.colorize("&7Seller: &f" + l.sellerName),
                    ItemBuilder.colorize("&7Price: &a" + fmt(l.price)),
                    ItemBuilder.colorize("&eClick to purchase")
            ));
            meta.getPersistentDataContainer().set(listingKey, PersistentDataType.STRING, l.id.toString());
            display.setItemMeta(meta);
            inv.setItem(slot++, display);
        }

        // control row
        int controlRow = size - 9;
        if (page > 0) {
            inv.setItem(controlRow, control(Material.ARROW, plugin.getMessages().rawFormatted("auctionhouse.gui-prev-page"), "prev"));
        }
        ItemStack sellButton = new ItemBuilder(Material.GOLD_INGOT)
                .name(plugin.getMessages().rawFormatted("auctionhouse.gui-sell-button"))
                .lore(List.of(plugin.getMessages().rawFormatted("auctionhouse.gui-sell-lore")))
                .build();
        ItemMeta sellMeta = sellButton.getItemMeta();
        sellMeta.getPersistentDataContainer().set(controlKey, PersistentDataType.STRING, "sell");
        sellButton.setItemMeta(sellMeta);
        inv.setItem(controlRow + 4, sellButton);

        ItemStack pageInfo = new ItemBuilder(Material.PAPER)
                .name("&7Page " + (page + 1) + "/" + totalPages)
                .build();
        inv.setItem(controlRow + 5, pageInfo);

        if (page < totalPages - 1) {
            inv.setItem(controlRow + 8, control(Material.ARROW, plugin.getMessages().rawFormatted("auctionhouse.gui-next-page"), "next"));
        }

        openAhInventories.add(inv);
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
        if (!openAhInventories.contains(event.getView().getTopInventory())) return;
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
                case "sell" -> beginSellFlow(p);
            }
            return;
        }

        String idStr = clicked.getItemMeta().getPersistentDataContainer().get(listingKey, PersistentDataType.STRING);
        if (idStr == null) return;
        purchase(p, UUID.fromString(idStr));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        openAhInventories.remove(event.getInventory());
    }

    private void beginSellFlow(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) {
            plugin.getMessages().send(p, "auctionhouse.no-item-held");
            return;
        }
        long active = countActiveListings(p.getUniqueId());
        int maxSlots = getMaxSlots(p);
        if (active >= maxSlots) {
            plugin.getMessages().send(p, "auctionhouse.slots-full", "active", String.valueOf(active), "max", String.valueOf(maxSlots));
            return;
        }

        ItemStack toSell = hand.clone();
        p.closeInventory();
        plugin.getMessages().send(p, "auctionhouse.prompt-price");
        plugin.getChatInput().awaitInput(p, input -> {
            if (input == null) {
                plugin.getMessages().send(p, "auctionhouse.cancelled-input");
                return;
            }
            createListing(p, toSell, input);
        });
    }

    private void createListing(Player p, ItemStack toSell, String priceInput) {
        double price;
        try {
            price = Double.parseDouble(priceInput.trim());
        } catch (NumberFormatException e) {
            plugin.getMessages().send(p, "auctionhouse.invalid-price");
            return;
        }

        double min = plugin.getConfig().getDouble("auctionhouse.min-price", 1.0);
        double max = plugin.getConfig().getDouble("auctionhouse.max-price", 1000000.0);
        if (price < min || price > max) {
            plugin.getMessages().send(p, "auctionhouse.price-range", "min", fmt(min), "max", fmt(max));
            return;
        }

        // re-validate they still hold enough of the item (they may have used it while typing)
        if (!p.getInventory().containsAtLeast(toSell, toSell.getAmount())) {
            plugin.getMessages().send(p, "auctionhouse.no-item-held");
            return;
        }

        long active = countActiveListings(p.getUniqueId());
        int maxSlots = getMaxSlots(p);
        if (active >= maxSlots) {
            plugin.getMessages().send(p, "auctionhouse.slots-full", "active", String.valueOf(active), "max", String.valueOf(maxSlots));
            return;
        }

        long durationHours = plugin.getConfig().getLong("auctionhouse.listing-duration-hours", 48);
        long expiresAt = System.currentTimeMillis() + durationHours * 3600_000L;

        Listing listing = new Listing(p.getUniqueId(), p.getName(), toSell.clone(), price, expiresAt);
        listings.put(listing.id, listing);
        p.getInventory().removeItem(toSell);
        save();

        plugin.getMessages().send(p, "auctionhouse.listed",
                "price", fmt(price), "active", String.valueOf(active + 1), "max", String.valueOf(maxSlots));
    }

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
