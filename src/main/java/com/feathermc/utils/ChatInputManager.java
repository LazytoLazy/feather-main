package com.feathermc.utils;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Lets a GUI ask a player to type something in chat instead of typing a
 * whole command - used by the Auction House "Sell Held Item" button and the
 * Order System "Create Order" button. Call awaitInput(player, callback) right
 * after closing their inventory; the next chat message they send is
 * intercepted (never broadcast) and handed to the callback instead.
 * Typing "cancel" calls the callback with null.
 */
public class ChatInputManager implements Listener {

    private final Map<java.util.UUID, Consumer<String>> waiting = new ConcurrentHashMap<>();

    public void awaitInput(Player player, Consumer<String> callback) {
        waiting.put(player.getUniqueId(), callback);
    }

    public boolean isAwaitingInput(Player player) {
        return waiting.containsKey(player.getUniqueId());
    }

    public void cancel(Player player) {
        waiting.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Consumer<String> callback = waiting.remove(player.getUniqueId());
        if (callback == null) return;

        event.setCancelled(true);
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());

        // run on the main thread since callbacks touch Bukkit API/inventories
        player.getServer().getScheduler().runTask(
                player.getServer().getPluginManager().getPlugin("FeatherMC"),
                () -> {
                    if (message.equalsIgnoreCase("cancel")) {
                        callback.accept(null);
                    } else {
                        callback.accept(message);
                    }
                }
        );
    }
}
