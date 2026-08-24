package com.feathermc.duels;

import com.feathermc.FeatherMC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Duels system.
 *
 * Setup flow (admin, feathermc.duel.admin):
 *   /feathermc duel pos1        - set corner 1 of the template arena (stand at player-1 spawn)
 *   /feathermc duel pos2        - set corner 2 of the template arena (stand at player-2 spawn)
 *   /feathermc duel generate    - creates the "duel" Multiverse world (if missing) and
 *                                  copies the template region into `arena-count` (default 50)
 *                                  evenly spaced instances inside it.
 *
 * Player flow:
 *   /feathermc duel <player>    - challenge a player
 *   /feathermc duel accept      - accept the pending challenge
 *   /feathermc duel deny        - deny the pending challenge
 *   /feathermc duel cancel      - cancel your own outgoing challenge
 *   /feathermc duel arenas      - list arena status
 */
public class DuelsManager implements Listener {

    private final FeatherMC plugin;
    private final File dataFile;
    private final org.bukkit.configuration.file.YamlConfiguration data;

    private final Map<UUID, Location> pos1 = new HashMap<>();
    private final Map<UUID, Location> pos2 = new HashMap<>();

    private final Map<Integer, Location[]> arenaBounds = new HashMap<>();
    private final Map<Integer, Boolean> arenaInUse = new HashMap<>();

    private final Map<UUID, UUID> pendingRequests = new HashMap<>();
    private final Map<UUID, UUID> activeDuels = new HashMap<>();
    private final Map<UUID, Location> preDuelLocation = new HashMap<>();
    private final Map<UUID, Integer> playerArena = new HashMap<>();

    public DuelsManager(FeatherMC plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "duel_arenas.yml");
        if (!dataFile.exists()) {
            try {
                dataFile.getParentFile().mkdirs();
                dataFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create duel_arenas.yml: " + e.getMessage());
            }
        }
        this.data = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(dataFile);
        loadArenas();
    }

    private void save() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save duel_arenas.yml: " + e.getMessage());
        }
    }

    private void loadArenas() {
        arenaBounds.clear();
        arenaInUse.clear();
        if (data.getConfigurationSection("arenas") == null) return;
        for (String key : data.getConfigurationSection("arenas").getKeys(false)) {
            int idx = Integer.parseInt(key);
            String path = "arenas." + key;
            String world = data.getString(path + ".world");
            if (world == null || Bukkit.getWorld(world) == null) continue;
            Location s1 = loc(world, path + ".spawn1");
            Location s2 = loc(world, path + ".spawn2");
            arenaBounds.put(idx, new Location[]{s1, s2});
            arenaInUse.put(idx, false);
        }
    }

    private Location loc(String world, String path) {
        return new Location(Bukkit.getWorld(world),
                data.getDouble(path + ".x"), data.getDouble(path + ".y"), data.getDouble(path + ".z"),
                (float) data.getDouble(path + ".yaw"), (float) data.getDouble(path + ".pitch"));
    }

    private void setLoc(String path, Location l) {
        data.set(path + ".x", l.getX());
        data.set(path + ".y", l.getY());
        data.set(path + ".z", l.getZ());
        data.set(path + ".yaw", l.getYaw());
        data.set(path + ".pitch", l.getPitch());
    }

    // ---------------------------------------------------------------
    // Commands
    // ---------------------------------------------------------------

    public void handle(CommandSender sender, String[] args) {
        if (args.length == 0) {
            plugin.getMessages().send(sender, "duel.usage");
            return;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("pos1") || sub.equals("pos2")) {
            if (!sender.hasPermission("feathermc.duel.admin")) {
                plugin.getMessages().send(sender, "duel.admin-only");
                return;
            }
            if (!(sender instanceof Player p)) return;
            (sub.equals("pos1") ? pos1 : pos2).put(p.getUniqueId(), p.getLocation());
            plugin.getMessages().send(p, "duel.pos-set", "pos", sub);
            return;
        }

        if (sub.equals("generate")) {
            if (!sender.hasPermission("feathermc.duel.admin")) {
                plugin.getMessages().send(sender, "duel.admin-only");
                return;
            }
            if (!(sender instanceof Player p)) return;
            Location a = pos1.get(p.getUniqueId());
            Location b = pos2.get(p.getUniqueId());
            if (a == null || b == null) {
                plugin.getMessages().send(p, "duel.pos-missing");
                return;
            }
            generateArenas(p, a, b);
            return;
        }

        if (sub.equals("arenas")) {
            plugin.getMessages().send(sender, "duel.arenas-header", "count", String.valueOf(arenaBounds.size()));
            for (Map.Entry<Integer, Boolean> e : arenaInUse.entrySet()) {
                String status = plugin.getMessages().rawFormatted(e.getValue() ? "duel.arena-in-use" : "duel.arena-free");
                plugin.getMessages().send(sender, "duel.arena-line",
                        "index", String.valueOf(e.getKey()), "status", status);
            }
            return;
        }

        if (!(sender instanceof Player p)) return;

        switch (sub) {
            case "accept" -> handleAccept(p);
            case "deny" -> handleDeny(p);
            case "cancel" -> handleCancel(p);
            default -> handleChallenge(p, args[0]);
        }
    }

    // ---------------------------------------------------------------
    // Arena generation
    // ---------------------------------------------------------------

    private void generateArenas(Player initiator, Location a, Location b) {
        String duelWorldName = plugin.getConfig().getString("duels.duel-world", "duel");
        int count = plugin.getConfig().getInt("duels.arena-count", 50);
        int spacing = plugin.getConfig().getInt("duels.arena-spacing", 500);

        plugin.getMessages().send(initiator, "duel.generating-world", "world", duelWorldName);

        World existing = Bukkit.getWorld(duelWorldName);
        if (existing == null) {
            boolean hasMultiverse = Bukkit.getPluginManager().getPlugin("Multiverse-Core") != null;
            if (hasMultiverse) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv create " + duelWorldName + " normal -g NORMAL");
            } else {
                plugin.getMessages().send(initiator, "duel.no-multiverse");
                Bukkit.createWorld(new org.bukkit.WorldCreator(duelWorldName));
            }
        }

        new BukkitRunnable() {
            int attempts = 0;

            @Override
            public void run() {
                World duelWorld = Bukkit.getWorld(duelWorldName);
                attempts++;
                if (duelWorld == null && attempts < 10) return;
                cancel();

                if (duelWorld == null) {
                    plugin.getMessages().send(initiator, "duel.world-failed", "world", duelWorldName);
                    return;
                }

                doCopy(initiator, duelWorld, a, b, count, spacing);
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private void doCopy(Player initiator, World duelWorld, Location a, Location b, int count, int spacing) {
        int minX = Math.min(a.getBlockX(), b.getBlockX());
        int minY = Math.min(a.getBlockY(), b.getBlockY());
        int minZ = Math.min(a.getBlockZ(), b.getBlockZ());
        int maxX = Math.max(a.getBlockX(), b.getBlockX());
        int maxY = Math.max(a.getBlockY(), b.getBlockY());
        int maxZ = Math.max(a.getBlockZ(), b.getBlockZ());

        int sizeX = maxX - minX + 1;
        int sizeY = maxY - minY + 1;
        int sizeZ = maxZ - minZ + 1;
        World templateWorld = a.getWorld();

        int spawnOffsetX1 = a.getBlockX() - minX;
        int spawnOffsetZ1 = a.getBlockZ() - minZ;
        int spawnOffsetX2 = b.getBlockX() - minX;
        int spawnOffsetZ2 = b.getBlockZ() - minZ;

        plugin.getMessages().send(initiator, "duel.copying",
                "dimensions", sizeX + "x" + sizeY + "x" + sizeZ, "count", String.valueOf(count));

        BlockData[][][] snapshot = new BlockData[sizeX][sizeY][sizeZ];
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    snapshot[x][y][z] = templateWorld.getBlockAt(minX + x, minY + y, minZ + z).getBlockData();
                }
            }
        }

        List<Runnable> arenaJobs = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            final int arenaIndex = i;
            final int baseX = i * spacing;
            final int baseY = Math.max(minY, 64);
            final int baseZ = 0;

            arenaJobs.add(() -> {
                pasteBlocking(duelWorld, snapshot, baseX, baseY, baseZ, sizeX, sizeY, sizeZ);

                Location spawn1 = new Location(duelWorld, baseX + spawnOffsetX1 + 0.5, baseY + 1, baseZ + spawnOffsetZ1 + 0.5);
                Location spawn2 = new Location(duelWorld, baseX + spawnOffsetX2 + 0.5, baseY + 1, baseZ + spawnOffsetZ2 + 0.5);

                arenaBounds.put(arenaIndex, new Location[]{spawn1, spawn2});
                arenaInUse.put(arenaIndex, false);

                String path = "arenas." + arenaIndex;
                data.set(path + ".world", duelWorld.getName());
                setLoc(path + ".spawn1", spawn1);
                setLoc(path + ".spawn2", spawn2);
            });
        }

        new BukkitRunnable() {
            int index = 0;
            final int perTick = 1;

            @Override
            public void run() {
                for (int c = 0; c < perTick && index < arenaJobs.size(); c++, index++) {
                    arenaJobs.get(index).run();
                }
                if (index >= arenaJobs.size()) {
                    cancel();
                    save();
                    plugin.getMessages().send(initiator, "duel.generated",
                            "count", String.valueOf(count), "world", duelWorld.getName());
                } else if (index % 10 == 0) {
                    plugin.getMessages().send(initiator, "duel.progress",
                            "done", String.valueOf(index), "total", String.valueOf(arenaJobs.size()));
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void pasteBlocking(World world, BlockData[][][] snapshot,
                                int baseX, int baseY, int baseZ,
                                int sizeX, int sizeY, int sizeZ) {
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    world.getBlockAt(baseX + x, baseY + y, baseZ + z).setBlockData(snapshot[x][y][z], false);
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // Duel flow
    // ---------------------------------------------------------------

    private void handleChallenge(Player p, String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            plugin.getMessages().send(p, "general.player-not-found");
            return;
        }
        if (target.equals(p)) {
            plugin.getMessages().send(p, "duel.self-duel");
            return;
        }
        if (activeDuels.containsKey(p.getUniqueId()) || activeDuels.containsKey(target.getUniqueId())) {
            plugin.getMessages().send(p, "duel.already-in-duel");
            return;
        }
        pendingRequests.put(target.getUniqueId(), p.getUniqueId());
        plugin.getMessages().send(p, "duel.challenge-sent", "player", target.getName());
        plugin.getMessages().send(target, "duel.challenge-received", "player", p.getName());
    }

    private void handleAccept(Player p) {
        UUID challengerId = pendingRequests.remove(p.getUniqueId());
        if (challengerId == null) {
            plugin.getMessages().send(p, "duel.no-pending");
            return;
        }
        Player challenger = Bukkit.getPlayer(challengerId);
        if (challenger == null || !challenger.isOnline()) {
            plugin.getMessages().send(p, "duel.challenger-offline");
            return;
        }

        Integer arenaIndex = findFreeArena();
        if (arenaIndex == null) {
            plugin.getMessages().send(p, "duel.no-free-arena");
            plugin.getMessages().send(challenger, "duel.no-free-arena");
            return;
        }

        arenaInUse.put(arenaIndex, true);
        playerArena.put(p.getUniqueId(), arenaIndex);
        playerArena.put(challenger.getUniqueId(), arenaIndex);
        activeDuels.put(p.getUniqueId(), challenger.getUniqueId());
        activeDuels.put(challenger.getUniqueId(), p.getUniqueId());
        preDuelLocation.put(p.getUniqueId(), p.getLocation());
        preDuelLocation.put(challenger.getUniqueId(), challenger.getLocation());

        Location[] spawns = arenaBounds.get(arenaIndex);
        int countdown = plugin.getConfig().getInt("duels.countdown-seconds", 5);

        p.teleport(spawns[0]);
        challenger.teleport(spawns[1]);
        p.setHealth(p.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue());
        challenger.setHealth(challenger.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue());
        p.setFoodLevel(20);
        challenger.setFoodLevel(20);

        plugin.getMessages().send(p, "duel.starting", "arena", String.valueOf(arenaIndex), "seconds", String.valueOf(countdown));
        plugin.getMessages().send(challenger, "duel.starting", "arena", String.valueOf(arenaIndex), "seconds", String.valueOf(countdown));
    }

    private void handleDeny(Player p) {
        UUID challengerId = pendingRequests.remove(p.getUniqueId());
        if (challengerId == null) {
            plugin.getMessages().send(p, "duel.no-pending");
            return;
        }
        Player challenger = Bukkit.getPlayer(challengerId);
        plugin.getMessages().send(p, "duel.denied-self");
        if (challenger != null) plugin.getMessages().send(challenger, "duel.denied-other", "player", p.getName());
    }

    private void handleCancel(Player p) {
        pendingRequests.values().removeIf(v -> v.equals(p.getUniqueId()));
        plugin.getMessages().send(p, "duel.cancelled");
    }

    public void endDuel(Player winner, Player loser) {
        UUID w = winner.getUniqueId();
        UUID l = loser.getUniqueId();
        activeDuels.remove(w);
        activeDuels.remove(l);

        Integer arenaIndex = playerArena.remove(w);
        playerArena.remove(l);
        if (arenaIndex != null) arenaInUse.put(arenaIndex, false);

        if (plugin.getConfig().getBoolean("duels.return-to-spawn-on-finish", true)) {
            Location wLoc = preDuelLocation.remove(w);
            if (wLoc != null) winner.teleport(wLoc);
            if (!loser.isDead()) {
                Location lLoc = preDuelLocation.remove(l);
                if (lLoc != null) loser.teleport(lLoc);
            }
        }

        plugin.getMessages().send(winner, "duel.won", "player", loser.getName());
        plugin.getMessages().send(loser, "duel.lost", "player", winner.getName());
    }

    private Integer findFreeArena() {
        for (Map.Entry<Integer, Boolean> e : arenaInUse.entrySet()) {
            if (!e.getValue()) return e.getKey();
        }
        return null;
    }

    public boolean isInDuel(Player p) {
        return activeDuels.containsKey(p.getUniqueId());
    }

    public Player getOpponent(Player p) {
        UUID opp = activeDuels.get(p.getUniqueId());
        return opp == null ? null : Bukkit.getPlayer(opp);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player loser = event.getEntity();
        if (!isInDuel(loser)) return;
        Player winner = getOpponent(loser);
        if (winner == null) return;
        event.getDrops().clear();
        event.setKeepInventory(true);
        endDuel(winner, loser);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Location back = preDuelLocation.remove(event.getPlayer().getUniqueId());
        if (back != null) {
            event.setRespawnLocation(back);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        if (isInDuel(p)) {
            Player opponent = getOpponent(p);
            if (opponent != null) {
                endDuel(opponent, p);
            }
        }
        pendingRequests.remove(p.getUniqueId());
        pendingRequests.values().removeIf(v -> v.equals(p.getUniqueId()));
    }
}
