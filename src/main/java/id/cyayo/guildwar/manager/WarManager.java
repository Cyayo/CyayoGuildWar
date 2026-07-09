package id.cyayo.guildwar.manager;

import id.cyayo.guildwar.CyayoGuildWar;
import id.cyayo.guildwar.config.ConfigManager;
import id.cyayo.guildwar.data.DataManager;
import id.cyayo.guildwar.hook.GuildsHook;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Banner;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.banner.Pattern;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class WarManager {

    private final CyayoGuildWar plugin;
    private final Map<String, WarSession> activeSessions = new HashMap<>();
    private final Map<UUID, PlayerSavedState> savedStates = new HashMap<>();

    public static class PlayerSavedState {
        public Location location;
        public ItemStack[] inventoryContents;
        public ItemStack[] armorContents;
        public GameMode gameMode;
        public boolean isAttacker;
        public String regionId;
        public boolean isSpectator;
    }

    public enum WarStatus {
        PREPARING,
        ACTIVE,
        ENDED
    }

    public static class WarSession {
        public String regionId;
        public String displayName;
        public UUID attackerGuildId;
        public String attackerGuildName;
        public UUID defenderGuildId;
        public String defenderGuildName;
        public String arenaId;
        public ConfigManager.ArenaInfo arena;
        public WarStatus status;
        public long startTime;
        
        public final Set<UUID> attackerPlayers = new HashSet<>();
        public final Set<UUID> defenderPlayers = new HashSet<>();
        public final Set<UUID> spectators = new HashSet<>();
        
        public BukkitTask preparationTask;
        public BukkitTask durationTask;
        public org.bukkit.boss.BossBar bossBar;
        public int currentStrengthLevel = 0;
        public final Map<UUID, Integer> outsideWarnSeconds = new HashMap<>();
        public final Set<UUID> huntPlayersToRestore = new HashSet<>();
    }

    private BukkitTask srTask;

    public WarManager(CyayoGuildWar plugin) {
        this.plugin = plugin;
        startSrPointsTask();
    }

    public void startSrPointsTask() {
        if (srTask != null) {
            srTask.cancel();
        }
        
        long intervalMins = plugin.getConfig().getLong("season-rating.interval-minutes", 10);
        long ticks = intervalMins * 60L * 20L;
        
        srTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!plugin.getConfig().getBoolean("season-rating.enabled", true)) return;
            if (plugin.getSeasonManager() != null && plugin.getSeasonManager().isPeacePeriod()) return;
            int pointsPerTerr = plugin.getConfig().getInt("season-rating.points-per-territory", 5);
            
            // Kumpulkan data jumlah wilayah per guild
            Map<UUID, String> guildNames = new HashMap<>();
            Map<UUID, Integer> territoriesCount = new HashMap<>();
            
            for (ConfigManager.TerritoryInfo t : plugin.getConfigManager().getTerritories().values()) {
                DataManager.OwnershipInfo ownership = plugin.getDataManager().getOwnership(t.regionId);
                if (ownership.ownerGuildId != null && ownership.ownerGuildName != null) {
                    guildNames.put(ownership.ownerGuildId, ownership.ownerGuildName);
                    territoriesCount.put(ownership.ownerGuildId, territoriesCount.getOrDefault(ownership.ownerGuildId, 0) + 1);
                }
            }
            
            // Bagikan poin SR ke setiap guild yang memegang wilayah
            for (Map.Entry<UUID, Integer> entry : territoriesCount.entrySet()) {
                UUID guildId = entry.getKey();
                int count = entry.getValue();
                String guildName = guildNames.get(guildId);
                int addedPoints = count * pointsPerTerr;
                
                plugin.getDataManager().addGuildSR(guildId, guildName, addedPoints);
                
                // Beri notifikasi ke seluruh anggota guild online
                String prefix = plugin.getConfig().getString("messages.prefix");
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (guildId.equals(GuildsHook.getGuildId(p))) {
                        p.sendMessage(plugin.color(prefix + plugin.getConfig().getString("messages.sr-reward-received")
                                .replace("{points}", String.valueOf(addedPoints))
                                .replace("{count}", String.valueOf(count))));
                    }
                }
            }
        }, ticks, ticks);
    }

    public void cleanupOnDisable() {
        if (srTask != null) {
            srTask.cancel();
            srTask = null;
        }

        // Pulihkan state seluruh pemain di setiap session aktif sebelum dibersihkan
        for (WarSession session : activeSessions.values()) {
            if (session.preparationTask != null) {
                session.preparationTask.cancel();
            }
            if (session.durationTask != null) {
                session.durationTask.cancel();
            }
            if (session.bossBar != null) {
                session.bossBar.removeAll();
            }
            session.arena.inUse = false;

            // Kumpulkan semua peserta di session ini
            Set<UUID> participants = new HashSet<>();
            participants.addAll(session.attackerPlayers);
            participants.addAll(session.defenderPlayers);
            participants.addAll(session.spectators);

            for (UUID uuid : participants) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    restorePlayerState(p, session.regionId);
                }
            }
        }
        activeSessions.clear();
        savedStates.clear();

        // Bersihkan glow effects & team scoreboard dari main scoreboard secara paksa
        try {
            org.bukkit.scoreboard.Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
            org.bukkit.scoreboard.Team attackerTeam = sb.getTeam("GW_Attacker");
            if (attackerTeam != null) attackerTeam.unregister();
            org.bukkit.scoreboard.Team defenderTeam = sb.getTeam("GW_Defender");
            if (defenderTeam != null) defenderTeam.unregister();
        } catch (Throwable ignored) {}
    }

    public Map<String, WarSession> getActiveSessions() {
        return activeSessions;
    }

    public Map<UUID, PlayerSavedState> getSavedStates() {
        return savedStates;
    }

    public boolean isPlayerInWar(UUID uuid) {
        for (WarSession session : activeSessions.values()) {
            if (session.attackerPlayers.contains(uuid) || session.defenderPlayers.contains(uuid) || session.spectators.contains(uuid)) {
                return true;
            }
        }
        return false;
    }

    public WarSession getPlayerSession(UUID uuid) {
        for (WarSession session : activeSessions.values()) {
            if (session.attackerPlayers.contains(uuid) || session.defenderPlayers.contains(uuid) || session.spectators.contains(uuid)) {
                return session;
            }
        }
        return null;
    }

    public void startWar(Player attacker, ConfigManager.TerritoryInfo territory) {
        String regionId = territory.regionId;
        
        if (activeSessions.containsKey(regionId.toLowerCase())) {
            attacker.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + plugin.getConfig().getString("messages.already-warring")));
            return;
        }

        UUID attackerGuildId = GuildsHook.getGuildId(attacker);
        if (attackerGuildId == null) {
            attacker.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + plugin.getConfig().getString("messages.not-in-guild")));
            return;
        }

        // Check guild rank requirements for declaring war
        String roleName = GuildsHook.getGuildRoleName(attacker);
        List<String> allowedRanks = plugin.getConfig().getStringList("guild-rank-requirements.declare-war");
        boolean hasRank = false;
        for (String r : allowedRanks) {
            if (r.equalsIgnoreCase(roleName)) {
                hasRank = true;
                break;
            }
        }
        if (!hasRank) {
            attacker.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + 
                    plugin.getConfig().getString("messages.no-guild-rank").replace("{ranks}", String.join(", ", allowedRanks))));
            return;
        }

        String attackerGuildName = GuildsHook.getGuildName(attacker);

        // Check maximum simultaneous attacks per guild from config
        int maxAttacks = plugin.getConfig().getInt("war-settings.max-simultaneous-attacks-per-guild", 1);
        int currentAttacks = 0;
        for (WarSession s : activeSessions.values()) {
            if (attackerGuildId.equals(s.attackerGuildId)) {
                currentAttacks++;
            }
        }
        if (currentAttacks >= maxAttacks) {
            attacker.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + plugin.getConfig().getString("messages.simultaneous-attack-limit")
                    .replace("{count}", String.valueOf(currentAttacks))));
            return;
        }

        DataManager.OwnershipInfo ownership = plugin.getDataManager().getOwnership(regionId);
        
        // Check cooldown
        if (ownership.cooldownUntil > System.currentTimeMillis()) {
            long remainingSec = (ownership.cooldownUntil - System.currentTimeMillis()) / 1000;
            long mins = remainingSec / 60;
            long secs = remainingSec % 60;
            attacker.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + 
                    plugin.getConfig().getString("messages.cooldown").replace("{time}", mins + "m " + secs + "s")));
            return;
        }

        // If unclaimed, claim immediately
        if (ownership.ownerGuildId == null) {
            ownership.ownerGuildId = attackerGuildId;
            ownership.ownerGuildName = attackerGuildName;
            plugin.getDataManager().saveOwnership(regionId, ownership);
            
            // Re-render banner if set
            updateBannerColor(territory, attackerGuildId);

            attacker.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + "&aGuild kamu berhasil mengklaim wilayah " + territory.displayName + " karena belum dikuasai!"));
            plugin.getWebhookManager().sendWinWebhook(territory.displayName, attackerGuildName, "None", 0, "None");
            return;
        }

        // If already owned by same guild
        if (ownership.ownerGuildId.equals(attackerGuildId)) {
            attacker.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + "&cWilayah ini sudah dikuasai oleh Guild kamu!"));
            return;
        }

        // Find available arena (must not be inUse and not active in any session)
        String assignedArenaId = null;
        ConfigManager.ArenaInfo assignedArena = null;
        for (Map.Entry<String, ConfigManager.ArenaInfo> entry : plugin.getConfigManager().getArenas().entrySet()) {
            ConfigManager.ArenaInfo arena = entry.getValue();
            boolean isAlreadyInUse = false;
            for (WarSession activeSession : activeSessions.values()) {
                if (entry.getKey().equalsIgnoreCase(activeSession.arenaId)) {
                    isAlreadyInUse = true;
                    break;
                }
            }
            if (!arena.inUse && !isAlreadyInUse) {
                assignedArenaId = entry.getKey();
                assignedArena = arena;
                break;
            }
        }

        if (assignedArena == null) {
            attacker.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + plugin.getConfig().getString("messages.arenas-full")));
            return;
        }

        // Setup session
        assignedArena.inUse = true;
        
        WarSession session = new WarSession();
        session.regionId = regionId;
        session.displayName = territory.displayName;
        session.attackerGuildId = attackerGuildId;
        session.attackerGuildName = attackerGuildName;
        session.defenderGuildId = ownership.ownerGuildId;
        session.defenderGuildName = ownership.ownerGuildName;
        session.arenaId = assignedArenaId;
        session.arena = assignedArena;
        session.status = WarStatus.PREPARING;
        session.startTime = System.currentTimeMillis();

        activeSessions.put(regionId, session);

        // Find initial players inside the WorldGuard region
        List<Player> nearbyAttackers = getGuildPlayersInRegion(attackerGuildId, regionId);
        List<Player> nearbyDefenders = getGuildPlayersInRegion(ownership.ownerGuildId, regionId);

        for (Player p : nearbyAttackers) session.attackerPlayers.add(p.getUniqueId());
        for (Player p : nearbyDefenders) session.defenderPlayers.add(p.getUniqueId());

        // Broadcast declaration (ONLY to attacker and defender guilds, NOT globally!)
        String broadcastMsg = plugin.getConfig().getString("messages.war-start-broadcast")
                .replace("{attacker}", attackerGuildName)
                .replace("{territory}", territory.displayName);
        notifyGuildMembers(attackerGuildId, broadcastMsg);
        notifyGuildMembers(ownership.ownerGuildId, broadcastMsg);

        // Play sound to both guilds to announce war declaration
        playConfigSoundToGuild(attackerGuildId, "war-declare", "ENTITY_ENDER_DRAGON_GROWL;1.0;0.8");
        playConfigSoundToGuild(ownership.ownerGuildId, "war-declare", "ENTITY_ENDER_DRAGON_GROWL;1.0;0.8");

        // Send Discord Webhook
        plugin.getWebhookManager().sendStartWebhook(territory.displayName, attackerGuildName, ownership.ownerGuildName, GuildsHook.getGuildTier(attacker));

        // Create BossBar countdown for participating guild members
        final int prepTime = plugin.getConfig().getInt("war-settings.preparation-time-seconds", 60);
        
        org.bukkit.boss.BarColor bbColor = org.bukkit.boss.BarColor.RED;
        try {
            bbColor = org.bukkit.boss.BarColor.valueOf(plugin.getConfig().getString("bossbar.color", "RED").toUpperCase());
        } catch (Exception ignored) {}
        
        org.bukkit.boss.BarStyle bbStyle = org.bukkit.boss.BarStyle.SOLID;
        try {
            bbStyle = org.bukkit.boss.BarStyle.valueOf(plugin.getConfig().getString("bossbar.style", "SOLID").toUpperCase());
        } catch (Exception ignored) {}
 
        final String titleTemplate = plugin.getConfig().getString("bossbar.title", "&c&l⚔️ Persiapan War {territory}: {time} detik! ⚔️");
        session.bossBar = Bukkit.createBossBar(
                plugin.color(titleTemplate.replace("{time}", String.valueOf(prepTime)).replace("{territory}", territory.displayName)),
                bbColor,
                bbStyle
        );
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID gId = GuildsHook.getGuildId(p);
            if (attackerGuildId.equals(gId) || ownership.ownerGuildId.equals(gId)) {
                session.bossBar.addPlayer(p);
            }
        }
 
        // Start countdown task
        session.preparationTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int countdown = prepTime;
            @Override
            public void run() {
                if (countdown <= 0) {
                    if (session.bossBar != null) {
                        session.bossBar.removeAll();
                        session.bossBar = null;
                    }
                    // HENTIKAN TASK INI TERLEBIH DAHULU SEBELUM MEMULAI FIGHT AGAR TIDAK LOOP SPAM!
                    cancel();
                    startArenaFight(session);
                    return;
                }
                
                if (session.bossBar != null) {
                    session.bossBar.setTitle(plugin.color(titleTemplate
                            .replace("{time}", String.valueOf(countdown))
                            .replace("{territory}", session.displayName)));
                    double progress = (double) countdown / prepTime;
                    if (progress >= 0.0 && progress <= 1.0) {
                        session.bossBar.setProgress(progress);
                    }
                }
                
                // Play Clock Ticking Sound on last 5 seconds of countdown
                if (countdown <= 5) {
                    playConfigSoundToSession(session, "countdown-tick", "BLOCK_NOTE_BLOCK_PLING;1.0;1.5");
                }
                
                if (countdown == 30 || countdown == 10 || countdown <= 5) {
                    String msg = plugin.getConfig().getString("messages.war-preparing").replace("{time}", String.valueOf(countdown));
                    notifySessionPlayers(session, msg);
                }
                
                countdown--;
            }
            
            private void cancel() {
                if (session.preparationTask != null) {
                    session.preparationTask.cancel();
                    session.preparationTask = null;
                }
            }
        }, 0L, 20L);
    }
 
    private void notifySessionPlayers(WarSession session, String message) {
        String colored = plugin.color(plugin.getConfig().getString("messages.prefix") + message);
        for (UUID uuid : session.attackerPlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.sendMessage(colored);
        }
        for (UUID uuid : session.defenderPlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.sendMessage(colored);
        }
    }
 
    private void startArenaFight(WarSession session) {
        session.status = WarStatus.ACTIVE;
        if (session.preparationTask != null) {
            session.preparationTask.cancel();
            session.preparationTask = null;
        }
 
        // Re-verify players in the region
        List<Player> rawAttackers = getGuildPlayersInRegion(session.attackerGuildId, session.regionId);
        List<Player> rawDefenders = getGuildPlayersInRegion(session.defenderGuildId, session.regionId);

        List<Player> attackers = new ArrayList<>();
        for (Player p : rawAttackers) {
            if (isPlayerDueling(p)) {
                p.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix", "&8[&bGuildWar&8] ") + "&cKamu tidak di-teleport ke arena karena sedang dalam duel!"));
            } else {
                attackers.add(p);
            }
        }

        List<Player> defenders = new ArrayList<>();
        for (Player p : rawDefenders) {
            if (isPlayerDueling(p)) {
                p.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix", "&8[&bGuildWar&8] ") + "&cKamu tidak di-teleport ke arena karena sedang dalam duel!"));
            } else {
                defenders.add(p);
            }
        }
 
        int minPlayers = plugin.getConfig().getInt("war-settings.min-players-per-guild", 1);
        int maxPlayers = plugin.getConfig().getInt("war-settings.max-players-per-guild", 5);

        if (attackers.size() > maxPlayers) {
            String fullMsg = plugin.getConfig().getString("messages.arena-full-guild", "&cKamu tidak ikut diteleportasi karena kapasitas maksimum pertarung guild di arena sudah penuh ({limit} pemain).")
                    .replace("{limit}", String.valueOf(maxPlayers));
            for (int i = maxPlayers; i < attackers.size(); i++) {
                attackers.get(i).sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + fullMsg));
            }
            attackers = new ArrayList<>(attackers.subList(0, maxPlayers));
        }

        if (defenders.size() > maxPlayers) {
            String fullMsg = plugin.getConfig().getString("messages.arena-full-guild", "&cKamu tidak ikut diteleportasi karena kapasitas maksimum pertarung guild di arena sudah penuh ({limit} pemain).")
                    .replace("{limit}", String.valueOf(maxPlayers));
            for (int i = maxPlayers; i < defenders.size(); i++) {
                defenders.get(i).sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + fullMsg));
            }
            defenders = new ArrayList<>(defenders.subList(0, maxPlayers));
        }

         if (attackers.size() < minPlayers) {
            // Attacker tidak mendatangkan cukup pasukan, Defender menang W.O.!
            String cancelMsg = "&cWar dibatalkan karena pihak Attacker tidak mendatangkan cukup pasukan (" + minPlayers + " pemain) ke wilayah ini! Guild &b" + session.defenderGuildName + " &eberhasil mempertahankan wilayah.";
            notifySessionPlayers(session, cancelMsg);
            
            if (session.bossBar != null) {
                session.bossBar.removeAll();
                session.bossBar = null;
            }
            endWar(session, session.defenderGuildId);
            return;
        }

        if (defenders.size() < minPlayers) {
            // Defender tidak hadir/personel kurang, Attacker menang W.O.!
            String woMsg = "&ePihak Defender tidak hadir atau kekurangan personel! Guild &b" + session.attackerGuildName + " &emenang Walkover (W.O.) dan berhasil merebut wilayah &a" + session.displayName + "&e!";
            notifySessionPlayers(session, woMsg);
            
            if (session.bossBar != null) {
                session.bossBar.removeAll();
                session.bossBar = null;
            }
            endWar(session, session.attackerGuildId);
            return;
        }
 
        session.attackerPlayers.clear();
        session.defenderPlayers.clear();

        // Force load chunk arena spawn locations
        if (session.arena.spawnAttacker != null && session.arena.spawnAttacker.getWorld() != null) {
            session.arena.spawnAttacker.getWorld().getChunkAt(session.arena.spawnAttacker).load();
        }
        if (session.arena.spawnDefender != null && session.arena.spawnDefender.getWorld() != null) {
            session.arena.spawnDefender.getWorld().getChunkAt(session.arena.spawnDefender).load();
        }

        // Teleport attackers
        for (Player p : attackers) {
            savePlayerState(p, true, session.regionId);
            session.attackerPlayers.add(p.getUniqueId());
            
            // Turunkan paksa dari kendaraan jika ada
            if (p.isInsideVehicle()) {
                p.leaveVehicle();
            }

            p.setGameMode(GameMode.SURVIVAL);
            if (Bukkit.getPluginManager().isPluginEnabled("CyayoPvP") && isPlayerHuntEnabled(p)) {
                session.huntPlayersToRestore.add(p.getUniqueId());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "hunt off " + p.getName());
            }
            applyGlowEffect(p, true);
            playConfigSound(p, "war-teleport", "ENTITY_ENDERMAN_TELEPORT;1.0;1.0");
            p.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + plugin.getConfig().getString("messages.war-teleporting")));

            // Teleport di tick berikutnya agar dijamin sukses dengan retry
            Location loc = session.arena.spawnAttacker;
            Bukkit.getScheduler().runTask(plugin, () -> {
                boolean success = p.teleport(loc, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
                if (!success) {
                    plugin.getLogger().warning("Gagal teleport pertama untuk " + p.getName() + " (Attacker), mencoba lagi...");
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        p.teleport(loc, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
                    }, 2L);
                }
            });
        }

        // Teleport defenders
        for (Player p : defenders) {
            savePlayerState(p, false, session.regionId);
            session.defenderPlayers.add(p.getUniqueId());
            
            // Turunkan paksa dari kendaraan jika ada
            if (p.isInsideVehicle()) {
                p.leaveVehicle();
            }

            p.setGameMode(GameMode.SURVIVAL);
            if (Bukkit.getPluginManager().isPluginEnabled("CyayoPvP") && isPlayerHuntEnabled(p)) {
                session.huntPlayersToRestore.add(p.getUniqueId());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "hunt off " + p.getName());
            }
            applyGlowEffect(p, false);
            playConfigSound(p, "war-teleport", "ENTITY_ENDERMAN_TELEPORT;1.0;1.0");
            p.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + plugin.getConfig().getString("messages.war-teleporting")));

            // Teleport di tick berikutnya agar dijamin sukses dengan retry
            Location loc = session.arena.spawnDefender;
            Bukkit.getScheduler().runTask(plugin, () -> {
                boolean success = p.teleport(loc, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
                if (!success) {
                    plugin.getLogger().warning("Gagal teleport pertama untuk " + p.getName() + " (Defender), mencoba lagi...");
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        p.teleport(loc, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
                    }, 2L);
                }
            });
        }

        // Play epic horn to start the fight
        playConfigSoundToSession(session, "war-start", "EVENT_RAID_HORN;1.0;1.0");

        // Max duration task and live scoreboard updates
        final int maxDuration = plugin.getConfig().getInt("war-settings.max-war-duration-seconds", 600);
        session.durationTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            int elapsed = 0;
            @Override
            public void run() {
                if (session.status != WarStatus.ACTIVE) {
                    session.durationTask.cancel();
                    return;
                }
                
                int timeLeft = maxDuration - elapsed;
                if (timeLeft <= 0) {
                    endWar(session, session.defenderGuildId);
                    session.durationTask.cancel();
                    return;
                }
                
                // Monitor players outside region arena
                Set<UUID> fighters = new HashSet<>();
                fighters.addAll(session.attackerPlayers);
                fighters.addAll(session.defenderPlayers);

                for (UUID uuid : fighters) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null && p.isOnline()) {
                        // Check if player is outside WorldGuard region of this arena
                        if (!isPlayerInArenaRegion(p, session.arena.arenaRegion)) {
                            int warnSecs = session.outsideWarnSeconds.getOrDefault(uuid, 0) + 1;
                            session.outsideWarnSeconds.put(uuid, warnSecs);

                            int remaining = 5 - warnSecs;
                            if (remaining <= 0) {
                                // Sisa waktu habis! Eliminasi player
                                p.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + "&cWaktu habis! Kamu dieliminasi karena berada di luar batas arena."));
                                handlePlayerDeath(p);
                                session.outsideWarnSeconds.remove(uuid);
                            } else {
                                p.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + 
                                        "&c[!] Kamu berada di luar batas arena! Kembali dalam &e" + remaining + " &cdetik atau kamu akan dieliminasi!"));
                            }
                        } else {
                            // Player kembali ke dalam arena
                            session.outsideWarnSeconds.remove(uuid);
                        }
                    }
                }

                // Update live TAB scoreboard for participants
                updateWarScoreboard(session, timeLeft);
                
                elapsed++;
            }
        }, 0L, 20L); // Run every 1 second
    }

    public void handlePlayerDeath(Player player) {
        WarSession session = getPlayerSession(player.getUniqueId());
        if (session == null || session.status != WarStatus.ACTIVE) return;

        // Set to spectator
        player.setGameMode(GameMode.SPECTATOR);
        session.attackerPlayers.remove(player.getUniqueId());
        session.defenderPlayers.remove(player.getUniqueId());
        session.spectators.add(player.getUniqueId());

        // Teleport ke spawn spectator arena
        Location specSpawn = session.arena.spawnDefender;
        if (specSpawn != null) {
            player.teleport(specSpawn, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
        }

        // Paksa set spectator lagi setelah delay 2 tick untuk mengantisipasi plugin lain yang mereset gamemode saat teleport
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && session.status == WarStatus.ACTIVE && session.spectators.contains(player.getUniqueId())) {
                player.setGameMode(GameMode.SPECTATOR);
            }
        }, 2L);

        player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + "&eKamu telah gugur dan sekarang menjadi spectator."));

        // Check if fight is over
        if (session.attackerPlayers.isEmpty()) {
            // Defender wins
            endWar(session, session.defenderGuildId);
        } else if (session.defenderPlayers.isEmpty()) {
            // Attacker wins
            endWar(session, session.attackerGuildId);
        }
    }

    private void endWar(WarSession session, UUID winnerGuildId) {
        if (session.status == WarStatus.ENDED) return;
        WarStatus originalStatus = session.status;
        session.status = WarStatus.ENDED;
        
        if (session.preparationTask != null) {
            session.preparationTask.cancel();
            session.preparationTask = null;
        }
        if (session.durationTask != null) {
            session.durationTask.cancel();
            session.durationTask = null;
        }

        // Play sounds: Challenge Complete to winner guild, Wither Spawn to loser guild
        UUID loserGuildId = winnerGuildId.equals(session.attackerGuildId) ? session.defenderGuildId : session.attackerGuildId;
        playSoundToGuild(winnerGuildId, org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        playSoundToGuild(loserGuildId, org.bukkit.Sound.ENTITY_WITHER_SPAWN, 1.0f, 0.8f);

        boolean attackerWon = winnerGuildId.equals(session.attackerGuildId);
        String winnerName = attackerWon ? session.attackerGuildName : session.defenderGuildName;
        String loserName = attackerWon ? session.defenderGuildName : session.attackerGuildName;

        ConfigManager.TerritoryInfo territory = plugin.getConfigManager().getTerritories().get(session.regionId);
        int cooldownMins = territory != null ? territory.cooldownMinutes : 5;

        // 1. Define all players in the arena
        final Set<UUID> allParticipants = new java.util.HashSet<>();
        allParticipants.addAll(session.attackerPlayers);
        allParticipants.addAll(session.defenderPlayers);
        allParticipants.addAll(session.spectators);

        // Group participants by attacker and defender
        List<String> attackersList = new ArrayList<>();
        List<String> defendersList = new ArrayList<>();
        for (UUID u : allParticipants) {
            Player onlineP = Bukkit.getPlayer(u);
            if (onlineP != null) {
                PlayerSavedState state = savedStates.get(u);
                if (state != null) {
                    if (state.isAttacker) {
                        attackersList.add(onlineP.getName());
                    } else {
                        defendersList.add(onlineP.getName());
                    }
                } else {
                    if (session.attackerPlayers.contains(u)) {
                        attackersList.add(onlineP.getName());
                    } else {
                        defendersList.add(onlineP.getName());
                    }
                }
            }
        }
        String participantsStr = "attacker:\n" + 
                                 (attackersList.isEmpty() ? "Tidak ada" : String.join(",", attackersList)) + 
                                 "\n\ndefender:\n" + 
                                 (defendersList.isEmpty() ? "Tidak ada" : String.join(",", defendersList));

        if (attackerWon) {
            // Change Owner
            DataManager.OwnershipInfo ownership = plugin.getDataManager().getOwnership(session.regionId);
            ownership.ownerGuildId = session.attackerGuildId;
            ownership.ownerGuildName = session.attackerGuildName;
            // Cooldown applies because attacker won
            ownership.cooldownUntil = System.currentTimeMillis() + (cooldownMins * 60L * 1000L);
            plugin.getDataManager().saveOwnership(session.regionId, ownership);

            // Update banner automatically using the guild's saved custom design
            if (territory != null) {
                updateBannerColor(territory, session.attackerGuildId);
            }

            // Broadcast win
            String winMsg = plugin.getConfig().getString("messages.war-win-broadcast")
                    .replace("{winner}", winnerName)
                    .replace("{loser}", loserName)
                    .replace("{territory}", session.displayName);
            Bukkit.broadcastMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + winMsg));

            // Discord Webhook
            plugin.getWebhookManager().sendWinWebhook(session.displayName, winnerName, loserName, cooldownMins, participantsStr);

            // Play victory sound to winner (attacker) and defeat sound to loser (defender)
            playConfigSoundToGuild(session.attackerGuildId, "war-win", "UI_TOAST_CHALLENGE_COMPLETE;1.0;1.0");
            playConfigSoundToGuild(session.defenderGuildId, "war-lose", "ENTITY_WITHER_DEATH;0.8;0.8");

            // Berikan reward SR instan ke Attacker yang menang
            giveWarWinSrReward(session.attackerGuildId, session.attackerGuildName, session.displayName);

            // Log activity
            String cleanTerritoryName = org.bukkit.ChatColor.stripColor(plugin.color(session.displayName));
            plugin.getDataManager().addGuildLog(session.attackerGuildId, "capture-victory;" + cleanTerritoryName);
            plugin.getDataManager().addGuildLog(session.defenderGuildId, "capture-defeat;" + cleanTerritoryName + ";" + session.attackerGuildName);
        } else {
            // Defender retained
            // Cooldown protection applies ONLY if the actual fight was active/started (originalStatus == ACTIVE)
            if (originalStatus == WarStatus.ACTIVE) {
                DataManager.OwnershipInfo ownership = plugin.getDataManager().getOwnership(session.regionId);
                ownership.cooldownUntil = System.currentTimeMillis() + (cooldownMins * 60L * 1000L);
                plugin.getDataManager().saveOwnership(session.regionId, ownership);
            }

            String defendMsg = plugin.getConfig().getString("messages.war-defend-broadcast")
                    .replace("{winner}", winnerName)
                    .replace("{loser}", loserName)
                    .replace("{territory}", session.displayName);
            Bukkit.broadcastMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + defendMsg));

            // Discord Webhook
            plugin.getWebhookManager().sendFailWebhook(session.displayName, loserName, winnerName, cooldownMins, participantsStr);

            // Play victory sound to winner (defender) and defeat sound to loser (attacker)
            playConfigSoundToGuild(session.defenderGuildId, "war-win", "UI_TOAST_CHALLENGE_COMPLETE;1.0;1.0");
            playConfigSoundToGuild(session.attackerGuildId, "war-lose", "ENTITY_WITHER_DEATH;0.8;0.8");

            // Berikan reward SR instan ke Defender yang berhasil mempertahankan wilayah
            giveWarWinSrReward(session.defenderGuildId, session.defenderGuildName, session.displayName);

            // Log activity
            String cleanTerritoryName = org.bukkit.ChatColor.stripColor(plugin.color(session.displayName));
            plugin.getDataManager().addGuildLog(session.defenderGuildId, "defend-victory;" + cleanTerritoryName + ";" + session.attackerGuildName);
            plugin.getDataManager().addGuildLog(session.attackerGuildId, "defend-defeat;" + cleanTerritoryName + ";" + session.defenderGuildName);
        }

        // 2. Remove glow effect immediately for the celebration phase
        for (UUID uuid : allParticipants) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                removeGlowEffect(p);
            }
        }
 
        // 3. Move spectators back to survival mode for the celebration show without teleporting
        for (UUID uuid : session.spectators) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                PlayerSavedState state = savedStates.get(uuid);
                if (state != null) {
                    p.setGameMode(state.gameMode != null ? state.gameMode : org.bukkit.GameMode.SURVIVAL);
                }
            }
        }
 
        // Update physical banners in the world for the winning guild
        if (territory != null) {
            updateBannerColor(territory, winnerGuildId);
            updateAllGuildBanners(winnerGuildId);
        }

        final int celebrationTime = plugin.getConfig().getInt("war-settings.celebration-time-seconds", 10);
 
        // Schedule periodic random fireworks around players of the WINNER guild
        final UUID finalWinnerId = winnerGuildId;
        final org.bukkit.scheduler.BukkitTask fwTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            List<Player> winnerOnline = GuildsHook.getOnlineMembers(finalWinnerId);
            if (winnerOnline.isEmpty()) {
                // Fallback to any online participants if no winner members are online
                for (UUID uuid : allParticipants) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null && p.isOnline()) {
                        winnerOnline.add(p);
                    }
                }
            }
            
            if (!winnerOnline.isEmpty()) {
                Player targetPlayer = winnerOnline.get((int) (Math.random() * winnerOnline.size()));
                double dx = (Math.random() > 0.5 ? 1 : -1) * (3 + Math.random() * 5);
                double dz = (Math.random() > 0.5 ? 1 : -1) * (3 + Math.random() * 5);
                Location loc = targetPlayer.getLocation().add(dx, 4.0, dz);
                
                try {
                    org.bukkit.entity.Firework fw = loc.getWorld().spawn(loc, org.bukkit.entity.Firework.class);
                    org.bukkit.inventory.meta.FireworkMeta fwm = fw.getFireworkMeta();
                    org.bukkit.FireworkEffect effect = org.bukkit.FireworkEffect.builder()
                            .withColor(org.bukkit.Color.fromRGB((int)(Math.random()*256), (int)(Math.random()*256), (int)(Math.random()*256)))
                            .withColor(org.bukkit.Color.fromRGB((int)(Math.random()*256), (int)(Math.random()*256), (int)(Math.random()*256)))
                            .with(org.bukkit.FireworkEffect.Type.values()[(int)(Math.random() * org.bukkit.FireworkEffect.Type.values().length)])
                            .flicker(Math.random() > 0.5)
                            .trail(Math.random() > 0.5)
                            .build();
                    fwm.addEffect(effect);
                    fwm.setPower(1);
                    fw.setFireworkMeta(fwm);
                } catch (Exception ignored) {}
            }
        }, 0L, 30L); // Every 1.5 seconds (30 ticks)

        // Schedule delayed state restoration after celebration time ends
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            fwTask.cancel();
            
            for (UUID uuid : allParticipants) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    restorePlayerState(p, session.regionId);
                }
            }

            // Restore CyayoPvP hunt mode if they had it
            if (Bukkit.getPluginManager().isPluginEnabled("CyayoPvP")) {
                for (UUID uuid : session.huntPlayersToRestore) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null && p.isOnline()) {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "hunt on " + p.getName());
                    }
                }
            }
            
            session.arena.inUse = false;
            activeSessions.remove(session.regionId.toLowerCase());
        }, celebrationTime * 20L);
    }

    public void handleDisconnect(Player player) {
        UUID uuid = player.getUniqueId();
        WarSession session = getPlayerSession(uuid);
        if (session == null) return;
        
        session.spectators.remove(uuid);
        resetTabScoreboard(player);
        removeGlowEffect(player);

        if (session.status == WarStatus.PREPARING) {
            session.attackerPlayers.remove(uuid);
            session.defenderPlayers.remove(uuid);
        } else if (session.status == WarStatus.ACTIVE) {
            session.attackerPlayers.remove(uuid);
            session.defenderPlayers.remove(uuid);
            
            // Check if war should end
            if (session.attackerPlayers.isEmpty()) {
                endWar(session, session.defenderGuildId);
            } else if (session.defenderPlayers.isEmpty()) {
                endWar(session, session.attackerGuildId);
            }
        }
        
        // Pulihkan state segera untuk region spesifik agar tidak mengganggu region war lain
        restorePlayerState(player, session.regionId);
    }

    public void handleRejoin(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerSavedState state = savedStates.get(uuid);
        if (state != null) {
            // Pulihkan state hanya jika terdaftar di savedStates
            restorePlayerState(player, state.regionId);
            player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + "&eKamu terputus selama pertempuran teritori dan telah dikembalikan ke lokasi asal."));
        }
    }

    private void savePlayerState(Player player, boolean isAttacker, String regionId) {
        if (savedStates.containsKey(player.getUniqueId())) {
            return;
        }
        PlayerSavedState state = new PlayerSavedState();
        state.location = player.getLocation();
        state.inventoryContents = player.getInventory().getContents().clone();
        state.armorContents = player.getInventory().getArmorContents().clone();
        state.gameMode = player.getGameMode();
        state.isAttacker = isAttacker;
        state.regionId = regionId;

        savedStates.put(player.getUniqueId(), state);
    }

    public void saveSpectatorState(Player player, String regionId) {
        savePlayerState(player, false, regionId);
        PlayerSavedState state = savedStates.get(player.getUniqueId());
        if (state != null) {
            state.isSpectator = true;
        }
    }

    public void restorePlayerState(Player player) {
        restorePlayerState(player, null);
    }

    public void restorePlayerState(Player player, String targetRegionId) {
        UUID uuid = player.getUniqueId();
        PlayerSavedState state = savedStates.get(uuid);
        if (state == null) return;

        // Saring: Hanya restore jika region ID cocok (atau targetRegionId null untuk force reset/reload)
        if (targetRegionId != null && state.regionId != null && !state.regionId.equalsIgnoreCase(targetRegionId)) {
            return;
        }

        savedStates.remove(uuid);
        removeGlowEffect(player);
        resetTabScoreboard(player);

        player.teleport(state.location);
        
        // Pulihkan inventory & armor hanya jika diaktifkan di config
        if (plugin.getConfig().getBoolean("war-settings.restore-inventory-after-war", false)) {
            player.getInventory().setContents(state.inventoryContents);
            player.getInventory().setArmorContents(state.armorContents);
        }
        
        player.setGameMode(state.gameMode);
        player.setFireTicks(0);
        player.setFallDistance(0);
    }

    private void notifyGuildMembers(UUID guildId, String message) {
        if (guildId == null) return;
        String colored = plugin.color(plugin.getConfig().getString("messages.prefix") + message);
        for (Player p : GuildsHook.getOnlineMembers(guildId)) {
            p.sendMessage(colored);
        }
    }

    private List<Player> getGuildPlayersInRegion(UUID guildId, String regionId) {
        List<Player> list = new ArrayList<>();
        if (Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) {
            try {
                com.sk89q.worldguard.WorldGuard wg = com.sk89q.worldguard.WorldGuard.getInstance();
                com.sk89q.worldguard.protection.managers.RegionManager rm = wg.getPlatform().getRegionContainer().get(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(Bukkit.getWorlds().get(0))); // Fallback check
                
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (guildId.equals(GuildsHook.getGuildId(p))) {
                        // Jika pemain mematikan toggle partisipasi war, skip!
                        if (!plugin.getDataManager().getPlayerToggle(p.getUniqueId())) {
                            p.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + plugin.getConfig().getString("messages.war-toggle-disabled-msg")));
                            continue;
                        }

                        // Check if player is inside the WorldGuard region
                        com.sk89q.worldedit.util.Location weLoc = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(p.getLocation());
                        com.sk89q.worldguard.protection.regions.RegionContainer container = wg.getPlatform().getRegionContainer();
                        com.sk89q.worldguard.protection.regions.RegionQuery query = container.createQuery();
                        for (com.sk89q.worldguard.protection.regions.ProtectedRegion r : query.getApplicableRegions(weLoc)) {
                            if (r.getId().equalsIgnoreCase(regionId)) {
                                list.add(p);
                                break;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        return list;
    }

    public void updateBannerColor(ConfigManager.TerritoryInfo territory, UUID guildId) {
        if (territory == null) {
            plugin.getLogger().info("[BannerDebug] Gagal update: info territory null!");
            return;
        }
        if (territory.bannerLocation == null) {
            plugin.getLogger().info("[BannerDebug] Gagal update wilayah " + territory.displayName + ": Lokasi banner di konfigurasi yml null!");
            return;
        }
        if (territory.bannerLocation.getWorld() == null) {
            plugin.getLogger().info("[BannerDebug] Gagal update wilayah " + territory.displayName + ": World untuk lokasi banner null atau tidak di-load!");
            return;
        }
        if (guildId == null) {
            plugin.getLogger().info("[BannerDebug] Gagal update wilayah " + territory.displayName + ": Guild ID null!");
            return;
        }

        Block block = territory.bannerLocation.getBlock();
        String matName = block.getType().name();
        plugin.getLogger().info("[BannerDebug] Mencoba update banner wilayah " + territory.displayName + " di koordinat: " 
                + territory.bannerLocation.getBlockX() + ", " + territory.bannerLocation.getBlockY() + ", " + territory.bannerLocation.getBlockZ() 
                + " | Tipe Block saat ini: " + matName);

        if (matName.contains("BANNER")) {
            DataManager.OwnershipInfo guildBanner = plugin.getDataManager().getGuildBanner(guildId);
            
            org.bukkit.DyeColor baseColor = org.bukkit.DyeColor.WHITE;
            List<Pattern> patterns = new ArrayList<>();
            
            if (guildBanner != null && guildBanner.baseColor != null) {
                baseColor = guildBanner.baseColor;
                patterns = guildBanner.patterns;
                plugin.getLogger().info("[BannerDebug] Banner Guild terdeteksi dari database! Warna dasar: " + baseColor.name() + " | Jumlah pattern: " + patterns.size());
            } else {
                // Fallback: Coba ambil dari online player milik guild tersebut
                Player anyMember = null;
                for (Player onlineP : Bukkit.getOnlinePlayers()) {
                    if (guildId.equals(GuildsHook.getGuildId(onlineP))) {
                        anyMember = onlineP;
                        break;
                    }
                }
                if (anyMember != null) {
                    ItemStack guildsBannerItem = GuildsHook.getGuildBanner(anyMember);
                    if (guildsBannerItem != null && guildsBannerItem.getItemMeta() instanceof org.bukkit.inventory.meta.BannerMeta meta) {
                        // Dapatkan base color
                        String matName2 = guildsBannerItem.getType().name();
                        for (org.bukkit.DyeColor color : org.bukkit.DyeColor.values()) {
                            if (matName2.startsWith(color.name() + "_")) {
                                baseColor = color;
                                break;
                            }
                        }
                        patterns = meta.getPatterns();
                        plugin.getLogger().info("[BannerDebug] Banner Guild terdeteksi dari plugin Guilds! Warna dasar: " + baseColor.name() + " | Jumlah pattern: " + patterns.size());
                    } else {
                        plugin.getLogger().info("[BannerDebug] Banner dari plugin Guilds null atau meta tidak valid. Menggunakan banner putih default.");
                    }
                } else {
                    plugin.getLogger().info("[BannerDebug] Banner Guild tidak ditemukan dan tidak ada member online. Menggunakan banner putih default.");
                }
            }
            
            // Tentukan tipe material block baru berdasarkan wall/standing banner
            boolean isWall = matName.contains("WALL");
            String newMatName = baseColor.name() + (isWall ? "_WALL_BANNER" : "_BANNER");
            Material newMat = Material.matchMaterial(newMatName);
            if (newMat != null) {
                // Ambil BlockData lama untuk mempertahankan orientasi arah hadap (rotasi / facing)
                final org.bukkit.block.data.BlockData oldData = block.getBlockData();
                final org.bukkit.DyeColor finalBaseColor = baseColor;
                final List<Pattern> finalPatterns = patterns;
                final Location loc = block.getLocation();

                // 1. Hancurkan block lama dengan mengubahnya ke AIR untuk membersihkan TileEntity
                block.setType(Material.AIR, false);

                // 2. Berikan delay 1 tick sebelum meletakkan block banner baru agar client me-refresh visual secara total
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    Block targetBlock = loc.getBlock();
                    targetBlock.setType(newMat, false);
                    
                    try {
                        // Coba pulihkan orientasi arah hadap banner
                        if (oldData instanceof org.bukkit.block.data.Rotatable && targetBlock.getBlockData() instanceof org.bukkit.block.data.Rotatable) {
                            org.bukkit.block.data.Rotatable oldRot = (org.bukkit.block.data.Rotatable) oldData;
                            org.bukkit.block.data.Rotatable newRot = (org.bukkit.block.data.Rotatable) targetBlock.getBlockData();
                            newRot.setRotation(oldRot.getRotation());
                            targetBlock.setBlockData(newRot, true);
                        } else if (oldData instanceof org.bukkit.block.data.Directional && targetBlock.getBlockData() instanceof org.bukkit.block.data.Directional) {
                            org.bukkit.block.data.Directional oldDir = (org.bukkit.block.data.Directional) oldData;
                            org.bukkit.block.data.Directional newDir = (org.bukkit.block.data.Directional) targetBlock.getBlockData();
                            newDir.setFacing(oldDir.getFacing());
                            targetBlock.setBlockData(newDir, true);
                        } else {
                            targetBlock.setBlockData(oldData, true);
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warning("[BannerDebug] Gagal memulihkan orientasi banner: " + e.getMessage());
                    }

                    // Terapkan pattern langsung secara programmatic ke TileEntity
                    BlockState state = targetBlock.getState();
                    if (state instanceof Banner banner) {
                        try {
                            banner.setBaseColor(finalBaseColor);
                        } catch (Throwable ignored) {}
                        
                        // Pastikan menyalin pattern ke list baru untuk menghindari bug immutable list
                        banner.setPatterns(new ArrayList<>(finalPatterns));
                        
                        // Tulis data ke world secara fisik
                        boolean success = banner.update(true, true);
                        plugin.getLogger().info("[BannerDebug] Update state banner fisik secara programmatic sukses? " + success);
                    }
                }, 1L);
            }

            // Simpan ke ownership data teritori
            DataManager.OwnershipInfo ownership = plugin.getDataManager().getOwnership(territory.regionId);
            ownership.baseColor = baseColor;
            ownership.patterns = new ArrayList<>(patterns);
            plugin.getDataManager().saveOwnership(territory.regionId, ownership);
        } else {
            plugin.getLogger().warning("[BannerDebug] Block di koordinat tersebut bukan tipe BANNER! Silakan set kembali dengan /war setbanner.");
        }
    }

    public void updateAllGuildBanners(UUID guildId) {
        if (guildId == null) return;
        for (ConfigManager.TerritoryInfo territory : plugin.getConfigManager().getTerritories().values()) {
            DataManager.OwnershipInfo ownership = plugin.getDataManager().getOwnership(territory.regionId);
            if (guildId.equals(ownership.ownerGuildId)) {
                updateBannerColor(territory, guildId);
            }
        }
    }

    private void applyGlowEffect(Player p, boolean isAttacker) {
        if (!plugin.getConfig().getBoolean("war-settings.glow-effect", true)) return;
        
        try {
            String teamName = isAttacker ? "GW_Attacker" : "GW_Defender";
            String colorStr = plugin.getConfig().getString("war-settings.glow-color-" + (isAttacker ? "attacker" : "defender"), isAttacker ? "RED" : "BLUE");
            org.bukkit.ChatColor color = org.bukkit.ChatColor.valueOf(colorStr.toUpperCase());

            // 1. Integrasi dengan TAB API
            boolean tabApplied = false;
            if (Bukkit.getPluginManager().isPluginEnabled("TAB")) {
                try {
                    Class<?> tabApiClass = Class.forName("me.neznamy.tab.api.TabAPI");
                    Object apiInstance = tabApiClass.getMethod("getInstance").invoke(null);
                    Object tabPlayer = tabApiClass.getMethod("getPlayer", UUID.class).invoke(apiInstance, p.getUniqueId());
                    Object ntm = tabApiClass.getMethod("getNameTagManager").invoke(apiInstance);
                    if (tabPlayer != null && ntm != null) {
                        java.lang.reflect.Method getPrefixMethod = ntm.getClass().getMethod("getOriginalPrefix", Class.forName("me.neznamy.tab.api.TabPlayer"));
                        String originalPrefix = (String) getPrefixMethod.invoke(ntm, tabPlayer);
                        if (originalPrefix == null) originalPrefix = "";
                        String colorCode = "\u00a7" + color.getChar();
                        java.lang.reflect.Method setPrefixMethod = ntm.getClass().getMethod("setPrefix", Class.forName("me.neznamy.tab.api.TabPlayer"), String.class);
                        setPrefixMethod.invoke(ntm, tabPlayer, originalPrefix + colorCode);
                        tabApplied = true;
                    }
                } catch (Throwable e) {
                    plugin.getLogger().warning("TAB API glow failed: " + e.getMessage());
                }
            }

            // Fallback ke vanilla team jika TAB tidak digunakan / gagal
            if (!tabApplied) {
                // Main Scoreboard
                org.bukkit.scoreboard.Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
                org.bukkit.scoreboard.Team team = sb.getTeam(teamName);
                if (team == null) {
                    team = sb.registerNewTeam(teamName);
                }
                team.setColor(color);
                team.setOption(org.bukkit.scoreboard.Team.Option.COLLISION_RULE, org.bukkit.scoreboard.Team.OptionStatus.NEVER);
                team.addEntry(p.getName());

                // Personal Scoreboard
                org.bukkit.scoreboard.Scoreboard playerBoard = p.getScoreboard();
                org.bukkit.scoreboard.Team pTeam = playerBoard.getTeam(teamName);
                if (pTeam == null) {
                    pTeam = playerBoard.registerNewTeam(teamName);
                }
                pTeam.setColor(color);
                pTeam.setOption(org.bukkit.scoreboard.Team.Option.COLLISION_RULE, org.bukkit.scoreboard.Team.OptionStatus.NEVER);
                pTeam.addEntry(p.getName());
            }

            p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.GLOWING, org.bukkit.potion.PotionEffect.INFINITE_DURATION, 0, false, false, false));
        } catch (Exception e) {
            plugin.getLogger().warning("Gagal menerapkan efek glow untuk " + p.getName() + ": " + e.getMessage());
        }
    }

    public void refreshGlowAfterTotem(Player player) {
        WarSession session = getPlayerSession(player.getUniqueId());
        if (session == null || session.status != WarStatus.ACTIVE) return;

        boolean isAttacker = session.attackerPlayers.contains(player.getUniqueId());
        boolean isDefender = session.defenderPlayers.contains(player.getUniqueId());

        if (isAttacker) {
            applyGlowEffect(player, true);
        } else if (isDefender) {
            applyGlowEffect(player, false);
        }
    }

    private void removeGlowEffect(Player p) {
        try {
            // 1. Matikan glow visual secara paksa
            p.setGlowing(false);
            p.removePotionEffect(org.bukkit.potion.PotionEffectType.GLOWING);

            // 2. Reset TAB prefix jika ada
            if (Bukkit.getPluginManager().isPluginEnabled("TAB")) {
                try {
                    Class<?> tabApiClass = Class.forName("me.neznamy.tab.api.TabAPI");
                    Object apiInstance = tabApiClass.getMethod("getInstance").invoke(null);
                    Object tabPlayer = tabApiClass.getMethod("getPlayer", UUID.class).invoke(apiInstance, p.getUniqueId());
                    Object ntm = tabApiClass.getMethod("getNameTagManager").invoke(apiInstance);
                    if (tabPlayer != null && ntm != null) {
                        java.lang.reflect.Method getPrefixMethod = ntm.getClass().getMethod("getOriginalPrefix", Class.forName("me.neznamy.tab.api.TabPlayer"));
                        String originalPrefix = (String) getPrefixMethod.invoke(ntm, tabPlayer);
                        if (originalPrefix == null) originalPrefix = "";
                        if (originalPrefix.length() >= 2 && originalPrefix.charAt(originalPrefix.length() - 2) == '\u00a7') {
                            originalPrefix = originalPrefix.substring(0, originalPrefix.length() - 2);
                        }
                        java.lang.reflect.Method setPrefixMethod = ntm.getClass().getMethod("setPrefix", Class.forName("me.neznamy.tab.api.TabPlayer"), String.class);
                        setPrefixMethod.invoke(ntm, tabPlayer, originalPrefix);
                    }
                } catch (Throwable ignored) {}
            }

            // 3. Bersihkan vanilla team di Main Scoreboard
            org.bukkit.scoreboard.Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
            org.bukkit.scoreboard.Team attTeam = sb.getTeam("GW_Attacker");
            if (attTeam != null) {
                attTeam.removeEntry(p.getName());
                if (attTeam.getEntries().isEmpty()) {
                    attTeam.unregister(); // Hapus team kosong agar bersih total
                }
            }
            org.bukkit.scoreboard.Team defTeam = sb.getTeam("GW_Defender");
            if (defTeam != null) {
                defTeam.removeEntry(p.getName());
                if (defTeam.getEntries().isEmpty()) {
                    defTeam.unregister(); // Hapus team kosong agar bersih total
                }
            }

            // 4. Bersihkan vanilla team di Player Scoreboard
            org.bukkit.scoreboard.Scoreboard playerBoard = p.getScoreboard();
            org.bukkit.scoreboard.Team pAtt = playerBoard.getTeam("GW_Attacker");
            if (pAtt != null) {
                pAtt.removeEntry(p.getName());
                if (pAtt.getEntries().isEmpty()) {
                    pAtt.unregister();
                }
            }
            org.bukkit.scoreboard.Team pDef = playerBoard.getTeam("GW_Defender");
            if (pDef != null) {
                pDef.removeEntry(p.getName());
                if (pDef.getEntries().isEmpty()) {
                    pDef.unregister();
                }
            }
        } catch (Exception e) {
            // Ignored
        }
    }
    private void playSoundToGuild(UUID guildId, org.bukkit.Sound sound, float volume, float pitch) {
        if (guildId == null) return;
        for (Player p : GuildsHook.getOnlineMembers(guildId)) {
            p.playSound(p.getLocation(), sound, volume, pitch);
        }
    }

    private void playSoundToSession(WarSession session, org.bukkit.Sound sound, float volume, float pitch) {
        for (UUID uuid : session.attackerPlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.playSound(p.getLocation(), sound, volume, pitch);
        }
        for (UUID uuid : session.defenderPlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.playSound(p.getLocation(), sound, volume, pitch);
        }
        for (UUID uuid : session.spectators) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.playSound(p.getLocation(), sound, volume, pitch);
        }
    }

    public void updateWarScoreboard(WarSession session, int timeLeft) {
        if (!plugin.getConfig().getBoolean("scoreboard.enabled", true)) return;
        
        String title = plugin.color(plugin.getConfig().getString("scoreboard.title", "&c&l⚔️ WAR ARENA ⚔️"));
        List<String> rawLines = plugin.getConfig().getStringList("scoreboard.lines");
        
        String mins = String.valueOf(timeLeft / 60);
        String secs = String.format("%02d", timeLeft % 60);
        String timeStr = mins + ":" + secs;

        // Build player list with live health status
        List<String> attackerLines = new ArrayList<>();
        List<String> defenderLines = new ArrayList<>();

        String aliveFormat = plugin.getConfig().getString("scoreboard.player-alive-format", " &8- &e{player}: &c{health} HP");
        String deadFormat = plugin.getConfig().getString("scoreboard.player-dead-format", " &8- &7{player}: &7☠ DEAD");

        // Build attackers list
        for (UUID uuid : session.attackerPlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                String pLine = plugin.color(aliveFormat
                        .replace("{player}", p.getName())
                        .replace("{health}", String.valueOf((int) p.getHealth())));
                attackerLines.add(pLine);
            }
        }

        // Build defenders list
        for (UUID uuid : session.defenderPlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                String pLine = plugin.color(aliveFormat
                        .replace("{player}", p.getName())
                        .replace("{health}", String.valueOf((int) p.getHealth())));
                defenderLines.add(pLine);
            }
        }

        // Build dead / spectators list (from actual participants inside spectators)
        for (UUID uuid : session.spectators) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                PlayerSavedState state = savedStates.get(uuid);
                if (state != null && !state.isSpectator) {
                    String pLine = plugin.color(deadFormat.replace("{player}", p.getName()));
                    if (state.isAttacker) {
                        attackerLines.add(pLine);
                    } else {
                        defenderLines.add(pLine);
                    }
                }
            }
        }
        
        List<String> formattedLines = new ArrayList<>();
        for (String line : rawLines) {
            if (line.equals("{attacker_players}")) {
                if (attackerLines.isEmpty()) {
                    formattedLines.add(plugin.color(" &8- &7Tidak ada pemain"));
                } else {
                    formattedLines.addAll(attackerLines);
                }
            } else if (line.equals("{defender_players}")) {
                if (defenderLines.isEmpty()) {
                    formattedLines.add(plugin.color(" &8- &7Tidak ada pemain"));
                } else {
                    formattedLines.addAll(defenderLines);
                }
            } else {
                formattedLines.add(plugin.color(line
                        .replace("{territory}", session.displayName)
                        .replace("{time_left}", timeStr)
                        .replace("{attacker_guild}", session.attackerGuildName)
                        .replace("{defender_guild}", session.defenderGuildName)));
            }
        }
        
        Set<UUID> allParticipants = new HashSet<>();
        allParticipants.addAll(session.attackerPlayers);
        allParticipants.addAll(session.defenderPlayers);
        allParticipants.addAll(session.spectators);
        
        for (UUID uuid : allParticipants) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                showTabScoreboard(p, "gw_" + session.regionId, title, formattedLines);
            }
        }
    }

    public void showTabScoreboard(Player player, String name, String title, List<String> lines) {
        if (Bukkit.getPluginManager().getPlugin("TAB") == null) return;
        try {
            Class<?> tabApiClass = Class.forName("me.neznamy.tab.api.TabAPI");
            Object apiInstance = tabApiClass.getMethod("getInstance").invoke(null);
            Object sbManager = tabApiClass.getMethod("getScoreboardManager").invoke(apiInstance);
            if (sbManager == null) return;

            // Get TabPlayer
            Object tabPlayer = tabApiClass.getMethod("getPlayer", UUID.class).invoke(apiInstance, player.getUniqueId());
            if (tabPlayer == null) return;

            // Create Scoreboard: manager.createScoreboard(name, title, lines)
            java.lang.reflect.Method createMethod = sbManager.getClass().getMethod("createScoreboard", String.class, String.class, List.class);
            Object customScoreboard = createMethod.invoke(sbManager, name, title, lines);
            
            // Show Scoreboard: manager.showScoreboard(player, scoreboard)
            java.lang.reflect.Method showMethod = sbManager.getClass().getMethod("showScoreboard", 
                    Class.forName("me.neznamy.tab.api.TabPlayer"), 
                    Class.forName("me.neznamy.tab.api.scoreboard.Scoreboard"));
            showMethod.invoke(sbManager, tabPlayer, customScoreboard);
        } catch (Exception e) {
            // Ignored
        }
    }

    public void resetTabScoreboard(Player player) {
        if (Bukkit.getPluginManager().getPlugin("TAB") == null) return;
        try {
            Class<?> tabApiClass = Class.forName("me.neznamy.tab.api.TabAPI");
            Object apiInstance = tabApiClass.getMethod("getInstance").invoke(null);
            Object sbManager = tabApiClass.getMethod("getScoreboardManager").invoke(apiInstance);
            if (sbManager == null) return;

            // Get TabPlayer
            Object tabPlayer = tabApiClass.getMethod("getPlayer", UUID.class).invoke(apiInstance, player.getUniqueId());
            if (tabPlayer == null) return;

            // manager.resetScoreboard(player)
            java.lang.reflect.Method resetMethod = sbManager.getClass().getMethod("resetScoreboard", 
                    Class.forName("me.neznamy.tab.api.TabPlayer"));
            resetMethod.invoke(sbManager, tabPlayer);
        } catch (Exception e) {
            // Ignored
        }
    }

    public void applyStrengthToAlive(WarSession session, int level) {
        if (level <= 0) return;
        int amp = level - 1;
        
        Set<UUID> alive = new HashSet<>();
        alive.addAll(session.attackerPlayers);
        alive.addAll(session.defenderPlayers);
        
        for (UUID uuid : alive) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                p.removePotionEffect(org.bukkit.potion.PotionEffectType.STRENGTH);
                p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                        org.bukkit.potion.PotionEffectType.STRENGTH,
                        999999,
                        amp,
                        false,
                        false
                ));
            }
        }
        
        String msg = plugin.getConfig().getString("messages.sudden-death", "&c&l⚔️ SUDDEN DEATH! &eEfek Strength semua petarung meningkat ke Level &c{level}&e!")
                .replace("{level}", String.valueOf(level));
        notifySessionPlayers(session, msg);
        playConfigSoundToSession(session, "sudden-death-boost", "ENTITY_LIGHTNING_BOLT_THUNDER;0.8;1.0");
    }

    public void playConfigSound(Player player, String configPath, String defaultSound) {
        String raw = plugin.getConfig().getString("sounds." + configPath, defaultSound);
        String[] split = raw.split(";");
        if (split.length >= 3) {
            try {
                org.bukkit.Sound sound = org.bukkit.Sound.valueOf(split[0].toUpperCase());
                float volume = Float.parseFloat(split[1]);
                float pitch = Float.parseFloat(split[2]);
                player.playSound(player.getLocation(), sound, volume, pitch);
            } catch (Exception e) {
                try {
                    String[] defSplit = defaultSound.split(";");
                    player.playSound(player.getLocation(), org.bukkit.Sound.valueOf(defSplit[0]), Float.parseFloat(defSplit[1]), Float.parseFloat(defSplit[2]));
                } catch (Exception ignored) {}
            }
        }
    }

    private void playConfigSoundToGuild(UUID guildId, String configPath, String defaultSound) {
        if (guildId == null) return;
        String raw = plugin.getConfig().getString("sounds." + configPath, defaultSound);
        String[] split = raw.split(";");
        if (split.length >= 3) {
            try {
                org.bukkit.Sound sound = org.bukkit.Sound.valueOf(split[0].toUpperCase());
                float volume = Float.parseFloat(split[1]);
                float pitch = Float.parseFloat(split[2]);
                for (Player p : GuildsHook.getOnlineMembers(guildId)) {
                    p.playSound(p.getLocation(), sound, volume, pitch);
                }
            } catch (Exception e) {
                try {
                    String[] defSplit = defaultSound.split(";");
                    org.bukkit.Sound sound = org.bukkit.Sound.valueOf(defSplit[0]);
                    float volume = Float.parseFloat(defSplit[1]);
                    float pitch = Float.parseFloat(defSplit[2]);
                    for (Player p : GuildsHook.getOnlineMembers(guildId)) {
                        p.playSound(p.getLocation(), sound, volume, pitch);
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    private void playConfigSoundToSession(WarSession session, String configPath, String defaultSound) {
        String raw = plugin.getConfig().getString("sounds." + configPath, defaultSound);
        String[] split = raw.split(";");
        if (split.length >= 3) {
            try {
                org.bukkit.Sound sound = org.bukkit.Sound.valueOf(split[0].toUpperCase());
                float volume = Float.parseFloat(split[1]);
                float pitch = Float.parseFloat(split[2]);
                
                Set<UUID> all = new java.util.HashSet<>();
                all.addAll(session.attackerPlayers);
                all.addAll(session.defenderPlayers);
                all.addAll(session.spectators);
                
                for (UUID uuid : all) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null && p.isOnline()) {
                        p.playSound(p.getLocation(), sound, volume, pitch);
                    }
                }
            } catch (Exception e) {
                try {
                    String[] defSplit = defaultSound.split(";");
                    org.bukkit.Sound sound = org.bukkit.Sound.valueOf(defSplit[0]);
                    float volume = Float.parseFloat(defSplit[1]);
                    float pitch = Float.parseFloat(defSplit[2]);
                    Set<UUID> all = new java.util.HashSet<>();
                    all.addAll(session.attackerPlayers);
                    all.addAll(session.defenderPlayers);
                    all.addAll(session.spectators);
                    for (UUID uuid : all) {
                        Player p = Bukkit.getPlayer(uuid);
                        if (p != null && p.isOnline()) {
                            p.playSound(p.getLocation(), sound, volume, pitch);
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    private void giveWarWinSrReward(UUID winnerGuildId, String winnerGuildName, String regionDisplayName) {
        if (!plugin.getConfig().getBoolean("season-rating.enabled", true)) return;
        if (plugin.getSeasonManager() != null && plugin.getSeasonManager().isPeacePeriod()) return;
        int rewardPoints = plugin.getConfig().getInt("season-rating.win-war-sr-reward", 25);
        if (rewardPoints <= 0) return;

        plugin.getDataManager().addGuildSR(winnerGuildId, winnerGuildName, rewardPoints);

        String prefix = plugin.getConfig().getString("messages.prefix");
        String rewardMsg = plugin.getConfig().getString("messages.sr-reward-win-war")
                .replace("{points}", String.valueOf(rewardPoints))
                .replace("{territory}", regionDisplayName);

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (winnerGuildId.equals(GuildsHook.getGuildId(p))) {
                p.sendMessage(plugin.color(prefix + rewardMsg));
            }
        }
    }

    private boolean isPlayerInArenaRegion(Player player, String arenaRegionId) {
        if (arenaRegionId == null) return true; // Safe fallback jika region name kosong
        if (!Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) return true;
        try {
            com.sk89q.worldguard.WorldGuard wg = com.sk89q.worldguard.WorldGuard.getInstance();
            com.sk89q.worldedit.util.Location weLoc = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(player.getLocation());
            com.sk89q.worldguard.protection.regions.RegionContainer container = wg.getPlatform().getRegionContainer();
            com.sk89q.worldguard.protection.regions.RegionQuery query = container.createQuery();
            for (com.sk89q.worldguard.protection.regions.ProtectedRegion r : query.getApplicableRegions(weLoc)) {
                if (r.getId().equalsIgnoreCase(arenaRegionId)) {
                    return true; // Player berada di dalam region arena
                }
            }
        } catch (Throwable ignored) {
            return true; // Safe fallback jika terjadi error integrasi agar tidak salah mengeliminasi player
        }
        return false; // Player berada di luar region arena
    }

    private boolean isPlayerHuntEnabled(Player p) {
        org.bukkit.plugin.Plugin pvpPlugin = org.bukkit.Bukkit.getPluginManager().getPlugin("CyayoPvP");
        if (pvpPlugin != null && pvpPlugin.isEnabled()) {
            try {
                java.lang.reflect.Method method = pvpPlugin.getClass().getMethod("isHuntEnabled", Player.class);
                return (boolean) method.invoke(pvpPlugin, p);
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    private boolean isPlayerDueling(Player p) {
        org.bukkit.plugin.Plugin pvpPlugin = org.bukkit.Bukkit.getPluginManager().getPlugin("CyayoPvP");
        if (pvpPlugin != null && pvpPlugin.isEnabled()) {
            try {
                java.lang.reflect.Method method = pvpPlugin.getClass().getMethod("isDueling", Player.class);
                return (boolean) method.invoke(pvpPlugin, p);
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }
}
