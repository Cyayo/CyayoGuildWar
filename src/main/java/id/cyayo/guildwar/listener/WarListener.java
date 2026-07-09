package id.cyayo.guildwar.listener;

import id.cyayo.guildwar.CyayoGuildWar;
import id.cyayo.guildwar.manager.WarManager;
import id.cyayo.guildwar.hook.GuildsHook;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

public class WarListener implements Listener {

    private final CyayoGuildWar plugin;

    public WarListener(CyayoGuildWar plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID uuid = player.getUniqueId();
        
        if (plugin.getWarManager().isPlayerInWar(uuid)) {
            boolean dropItems = plugin.getConfig().getBoolean("war-settings.drop-items-on-death", false);
            
            if (!dropItems) {
                event.setKeepInventory(true);
                event.getDrops().clear();
                event.setDroppedExp(0);
            } else {
                // If dropping items is allowed, clear saved contents to prevent duplication on war end
                event.setKeepInventory(false);
                WarManager.PlayerSavedState state = plugin.getWarManager().getSavedStates().get(uuid);
                if (state != null) {
                    state.inventoryContents = new org.bukkit.inventory.ItemStack[0];
                    state.armorContents = new org.bukkit.inventory.ItemStack[0];
                }
            }
            
            // Instantly respawn in a delayed task to avoid death screen and enter spectator
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                player.spigot().respawn();
                plugin.getWarManager().handlePlayerDeath(player);
            }, 1L);
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;
        
        // 1. Lightweight block boundary check (avoids heavy calculations when player only rotates head)
        if (from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY() || from.getBlockZ() != to.getBlockZ()) {
            plugin.getConfigManager().updateCachedTerritory(player);
            
            // 2. Spectator bounds check
            WarManager.WarSession session = plugin.getWarManager().getPlayerSession(uuid);
            if (session != null && session.status == WarManager.WarStatus.ACTIVE) {
                if (session.spectators.contains(uuid)) {
                    if (!isInsideRegion(to, session.arena.arenaRegion)) {
                        Location targetLoc = session.arena.spawnDefender;
                        if (targetLoc == null || targetLoc.getWorld() == null) {
                            targetLoc = session.arena.spawnAttacker;
                        }
                        if (targetLoc == null || targetLoc.getWorld() == null) {
                            targetLoc = to.getWorld().getSpawnLocation();
                        }
                        player.teleport(targetLoc);
                        player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + 
                                plugin.getConfig().getString("messages.spectator-leave-arena")));
                    }
                }
            }
        }
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("cyayoguildwar.admin.bypass")) return;
        
        String msg = event.getMessage().toLowerCase().trim();
        // Izinkan command keluar dari mode spectator
        if (msg.startsWith("/war spectate quit") || msg.startsWith("/war spectate leave") || 
            msg.startsWith("/guildwar spectate quit") || msg.startsWith("/guildwar spectate leave") ||
            msg.startsWith("/gw spectate quit") || msg.startsWith("/gw spectate leave")) {
            return;
        }

        WarManager.WarSession session = plugin.getWarManager().getPlayerSession(player.getUniqueId());
        if (session != null && session.status == WarManager.WarStatus.ACTIVE) {
            player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + 
                    plugin.getConfig().getString("messages.command-blocked")));
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getWarManager().handleDisconnect(event.getPlayer());
        plugin.getConfigManager().clearCachedTerritory(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        plugin.getWarManager().handleRejoin(event.getPlayer());
        plugin.getConfigManager().updateCachedTerritory(event.getPlayer());
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onEntityDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            WarManager.WarSession session = plugin.getWarManager().getPlayerSession(player.getUniqueId());
            if (session != null) {
                if (session.status == WarManager.WarStatus.ENDED) {
                    event.setCancelled(true);
                    return;
                }
                
                if (session.status == WarManager.WarStatus.ACTIVE) {
                    // Cek jika ini adalah damage by player (PvP) untuk mem-force bypass WorldGuard pvp deny
                    if (event instanceof org.bukkit.event.entity.EntityDamageByEntityEvent damageByEntityEvent) {
                        Player attackerPlayer = null;
                        if (damageByEntityEvent.getDamager() instanceof Player attacker) {
                            attackerPlayer = attacker;
                        } else if (damageByEntityEvent.getDamager() instanceof org.bukkit.entity.Projectile proj && proj.getShooter() instanceof Player shooter) {
                            attackerPlayer = shooter;
                        }

                        if (attackerPlayer != null) {
                            UUID attackerGuild = GuildsHook.getGuildId(attackerPlayer);
                            UUID victimGuild = GuildsHook.getGuildId(player);
                            if (attackerGuild != null && attackerGuild.equals(victimGuild)) {
                                event.setCancelled(true); // MATIKAN FRIENDLY FIRE (Sesama Guild)
                                return;
                            }
                        }
                        event.setCancelled(false); // Paksa izinkan PvP di arena jika berbeda Guild
                    }

                    // Proteksi jatuh ke void di arena war
                    if (event.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.VOID) {
                        event.setCancelled(true);
                        // Reset player health & set to spectator
                        player.setHealth(player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue());
                        plugin.getWarManager().handlePlayerDeath(player);
                        player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + 
                                plugin.getConfig().getString("messages.void-fall", "&cKamu terjatuh ke void dan dieliminasi sebagai spectator!")));
                        return;
                    }

                    // DETEKSI FATAL DAMAGE (Lethal Hit): Jika damage menyebabkan player mati
                    double finalDamage = event.getFinalDamage();
                    if (player.getHealth() - finalDamage <= 0.0) {
                        // Cek apakah player memegang Totem of Undying
                        boolean holdsTotem = (player.getInventory().getItemInMainHand().getType() == org.bukkit.Material.TOTEM_OF_UNDYING) || 
                                             (player.getInventory().getItemInOffHand().getType() == org.bukkit.Material.TOTEM_OF_UNDYING);
                        
                        if (holdsTotem) {
                            // Biarkan totem vanilla menangani penyelamatan nyawa player (jangan di-cancel)
                            return;
                        }

                        event.setCancelled(true); // Batalkan damage asli (agar tidak memicu DeathEvent asli)
                        
                        // Isi penuh kembali darah player
                        double maxHealth = player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue();
                        player.setHealth(maxHealth);
                        
                        // Proses kematian/spectator manual
                        plugin.getWarManager().handlePlayerDeath(player);
                    }
                }
            }
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityResurrect(org.bukkit.event.entity.EntityResurrectEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (plugin.getWarManager().isPlayerInWar(player.getUniqueId())) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline()) {
                        plugin.getWarManager().refreshGlowAfterTotem(player);
                    }
                }, 1L);
            }
        }
    }

    private boolean isInsideRegion(Location loc, String regionId) {
        if (loc == null || regionId == null || regionId.isEmpty()) return true;
        if (Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) {
            try {
                com.sk89q.worldedit.util.Location weLoc = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(loc);
                com.sk89q.worldguard.WorldGuard wg = com.sk89q.worldguard.WorldGuard.getInstance();
                com.sk89q.worldguard.protection.regions.RegionContainer container = wg.getPlatform().getRegionContainer();
                com.sk89q.worldguard.protection.regions.RegionQuery query = container.createQuery();
                for (com.sk89q.worldguard.protection.regions.ProtectedRegion r : query.getApplicableRegions(weLoc)) {
                    if (r.getId().equalsIgnoreCase(regionId)) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }
}
