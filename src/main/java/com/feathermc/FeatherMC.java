package com.feathermc;

import com.feathermc.auction.AuctionSystem;
import com.feathermc.command.FeatherMCCommand;
import com.feathermc.duels.DuelsManager;
import com.feathermc.economy.EggEconomy;
import com.feathermc.economy.EggsCommandHandler;
import com.feathermc.order.OrderSystem;
import com.feathermc.playtime.PlaytimeSystem;
import com.feathermc.punish.PunishSystem;
import com.feathermc.shop.ShopSystem;
import com.feathermc.utils.ChatInputManager;
import com.feathermc.utils.MessageManager;
import com.feathermc.warp.WarpSystem;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class FeatherMC extends JavaPlugin {

    private MessageManager messages;
    private ChatInputManager chatInput;
    private EggEconomy eggEconomy;
    private EggsCommandHandler eggsCommandHandler;

    private PunishSystem punishSystem;
    private WarpSystem warpSystem;
    private ShopSystem shopSystem;
    private DuelsManager duelsManager;
    private AuctionSystem auctionSystem;
    private OrderSystem orderSystem;
    private PlaytimeSystem playtimeSystem;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messages = new MessageManager(this);
        chatInput = new ChatInputManager();
        getServer().getPluginManager().registerEvents(chatInput, this);

        eggEconomy = new EggEconomy(this);
        eggsCommandHandler = new EggsCommandHandler(this);

        punishSystem = new PunishSystem(this);
        getServer().getPluginManager().registerEvents(punishSystem, this);

        warpSystem = new WarpSystem(this);
        getServer().getPluginManager().registerEvents(warpSystem, this);

        shopSystem = new ShopSystem(this);
        getServer().getPluginManager().registerEvents(shopSystem, this);

        duelsManager = new DuelsManager(this);
        getServer().getPluginManager().registerEvents(duelsManager, this);

        if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
            auctionSystem = new AuctionSystem(this);
            getServer().getPluginManager().registerEvents(auctionSystem, this);

            orderSystem = new OrderSystem(this);
            getServer().getPluginManager().registerEvents(orderSystem, this);

            Bukkit.getScheduler().runTaskTimer(this, () -> {
                auctionSystem.purgeExpired();
                orderSystem.purgeExpired();
            }, 6000L, 6000L);
        } else {
            getLogger().warning("Vault not found - Auction House and Order System are disabled until Vault + an economy plugin are installed. (Eggs are unaffected - they use FeatherMC's own currency, not Vault.)");
        }

        playtimeSystem = new PlaytimeSystem(this);
        getServer().getPluginManager().registerEvents(playtimeSystem, this);

        FeatherMCCommand command = new FeatherMCCommand(this);
        getCommand("feathermc").setExecutor(command);
        getCommand("feathermc").setTabCompleter(command);

        getLogger().info("FeatherMC enabled - punish, warps, shop, duels, auction house, orders, playtime, eggs all loaded.");
    }

    @Override
    public void onDisable() {
        if (playtimeSystem != null) playtimeSystem.saveAll();
        if (eggEconomy != null) eggEconomy.save();
        getLogger().info("FeatherMC disabled.");
    }

    public MessageManager getMessages() {
        return messages;
    }

    public ChatInputManager getChatInput() {
        return chatInput;
    }

    public EggEconomy getEggEconomy() {
        return eggEconomy;
    }

    public EggsCommandHandler getEggsCommandHandler() {
        return eggsCommandHandler;
    }

    public PunishSystem getPunishSystem() {
        return punishSystem;
    }

    public WarpSystem getWarpSystem() {
        return warpSystem;
    }

    public ShopSystem getShopSystem() {
        return shopSystem;
    }

    public DuelsManager getDuelsManager() {
        return duelsManager;
    }

    public AuctionSystem getAuctionSystem() {
        return auctionSystem;
    }

    public OrderSystem getOrderSystem() {
        return orderSystem;
    }

    public PlaytimeSystem getPlaytimeSystem() {
        return playtimeSystem;
    }
}
