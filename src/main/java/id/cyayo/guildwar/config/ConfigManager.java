package id.cyayo.guildwar.config;

import id.cyayo.guildwar.CyayoGuildWar;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConfigManager {

    private final CyayoGuildWar plugin;
    
    // Arenas config
    private File arenasFile;
    private FileConfiguration arenasConfig;
    private final Map<String, ArenaInfo> arenas = new HashMap<>();

    // GUI configs
    private File territoryInfoFile;
    private FileConfiguration territoryInfoConfig;
    private File statsFile;
    private FileConfiguration statsConfig;
    private File territoryDetailFile;
    private FileConfiguration territoryDetailConfig;
    private File logsFile;
    private FileConfiguration logsConfig;
    
    // Vault GUI config
    private File vaultFile;
    private FileConfiguration vaultConfig;
    
    // Season config
    private File seasonFile;
    private FileConfiguration seasonConfig;

    // Territories config
    private final Map<String, TerritoryInfo> territories = new HashMap<>();

    public static class ArenaInfo {
        public String worldName;
        public String arenaRegion; // WorldGuard region for spectators limit
        public Location spawnAttacker;
        public Location spawnDefender;
        public boolean inUse = false;
    }

    public static class TerritoryInfo {
        public String regionId; // WorldGuard region name
        public String displayName;
        public int cooldownMinutes;
        public Location bannerLocation; // Block location

        // Lore bebas untuk buffs-item di GUI (diatur langsung per territory)
        public List<String> buffLore = new ArrayList<>();

        // Buffs (untuk sistem aktual, bukan display)
        public List<String> potionEffects = new ArrayList<>();
        public Map<String, Double> auraskillsXp = new HashMap<>();
        public double luckBonus = 0.0;

        public List<PeriodicItem> periodicItems = new ArrayList<>();
        public List<PeriodicCommand> periodicCommands = new ArrayList<>();
    }

    public static class PeriodicItem {
        public int intervalMinutes;
        public String material;
        public String mmoType; // MMOItem type
        public String mmoId;   // MMOItem id
        public int amount;
        public double chance;
        public String customName;
        public List<String> customLore = new ArrayList<>();
    }

    public static class PeriodicCommand {
        public int intervalMinutes;
        public String command;
        public double chance;
    }

    public ConfigManager(CyayoGuildWar plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        loadArenas();
        loadGuiConfigs();
        loadVault();
        loadSeason();
        loadTerritories();
    }

    private void loadArenas() {
        arenas.clear();
        arenasFile = new File(plugin.getDataFolder(), "arenas.yml");
        if (!arenasFile.exists()) {
            saveResource("arenas.yml", arenasFile);
        }
        arenasConfig = YamlConfiguration.loadConfiguration(arenasFile);

        ConfigurationSection sec = arenasConfig.getConfigurationSection("arenas");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                ArenaInfo info = new ArenaInfo();
                info.worldName = sec.getString(key + ".world", "world");
                info.arenaRegion = sec.getString(key + ".arena-region", "");
                
                World world = Bukkit.getWorld(info.worldName);
                
                double ax = sec.getDouble(key + ".spawn-attacker.x", 0);
                double ay = sec.getDouble(key + ".spawn-attacker.y", 64);
                double az = sec.getDouble(key + ".spawn-attacker.z", 0);
                float ayaw = (float) sec.getDouble(key + ".spawn-attacker.yaw", 0);
                float apitch = (float) sec.getDouble(key + ".spawn-attacker.pitch", 0);
                info.spawnAttacker = new Location(world, ax, ay, az, ayaw, apitch);

                double dx = sec.getDouble(key + ".spawn-defender.x", 0);
                double dy = sec.getDouble(key + ".spawn-defender.y", 64);
                double dz = sec.getDouble(key + ".spawn-defender.z", 0);
                float dyaw = (float) sec.getDouble(key + ".spawn-defender.yaw", 0);
                float dpitch = (float) sec.getDouble(key + ".spawn-defender.pitch", 0);
                info.spawnDefender = new Location(world, dx, dy, dz, dyaw, dpitch);

                arenas.put(key, info);
            }
        }
    }

    private void loadTerritories() {
        territories.clear();
        File folder = new File(plugin.getDataFolder(), "territories");
        if (!folder.exists()) {
            folder.mkdirs();
            // Save example map_rpg.yml
            File exampleFile = new File(folder, "map_rpg.yml");
            saveResource("territories/map_rpg.yml", exampleFile);
        }

        File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                FileConfiguration config = YamlConfiguration.loadConfiguration(file);
                ConfigurationSection sec = config.getConfigurationSection("territories");
                if (sec != null) {
                    for (String key : sec.getKeys(false)) {
                        TerritoryInfo info = new TerritoryInfo();
                        info.regionId = key;
                        info.displayName = sec.getString(key + ".display-name", key);
                        info.cooldownMinutes = sec.getInt(key + ".cooldown-minutes", 5);

                        // Banner location (could be set dynamically)
                        if (sec.contains(key + ".banner")) {
                            String bWorld = sec.getString(key + ".banner.world", "world");
                            double bx = sec.getDouble(key + ".banner.x", 0);
                            double by = sec.getDouble(key + ".banner.y", 0);
                            double bz = sec.getDouble(key + ".banner.z", 0);
                            info.bannerLocation = new Location(Bukkit.getWorld(bWorld), bx, by, bz);
                        }

                        // Lore bebas untuk buffs-item di GUI
                        info.buffLore = sec.getStringList(key + ".buff-lore");
                        if (info.buffLore == null) info.buffLore = new ArrayList<>();

                        ConfigurationSection buffSec = sec.getConfigurationSection(key + ".buffs");
                        if (buffSec != null) {
                            // Potion effects
                            info.potionEffects = buffSec.getStringList("potion-effects");
                            if (info.potionEffects == null) info.potionEffects = new ArrayList<>();

                            // AuraSkills XP
                            ConfigurationSection xpSec = buffSec.getConfigurationSection("auraskills-xp");
                            if (xpSec != null) {
                                for (String skill : xpSec.getKeys(false)) {
                                    info.auraskillsXp.put(skill.toLowerCase(), xpSec.getDouble(skill));
                                }
                            }

                            // CyayoLuckRNG Luck
                            info.luckBonus = buffSec.getDouble("cyayoluckrng-luck", 0.0);

                            // Periodical Items
                            ConfigurationSection itemSec = buffSec.getConfigurationSection("periodical-items");
                            if (itemSec == null) {
                                itemSec = buffSec.getConfigurationSection("periodic-items");
                            }
                            if (itemSec != null) {
                                int interval = itemSec.getInt("interval-minutes", 10);
                                List<Map<?, ?>> rawItems = itemSec.getMapList("items");
                                if (rawItems != null) {
                                    for (Map<?, ?> rawItem : rawItems) {
                                        PeriodicItem pi = new PeriodicItem();
                                        pi.intervalMinutes = interval;
                                        pi.material = (String) rawItem.get("material");
                                        
                                        Map<?, ?> mmoObj = (Map<?, ?>) rawItem.get("mmoitem");
                                        if (mmoObj != null) {
                                            pi.mmoType = (String) mmoObj.get("type");
                                            pi.mmoId = (String) mmoObj.get("id");
                                        } else {
                                            if (rawItem.containsKey("mmo-type")) {
                                                pi.mmoType = (String) rawItem.get("mmo-type");
                                            }
                                            if (rawItem.containsKey("mmo-id")) {
                                                pi.mmoId = (String) rawItem.get("mmo-id");
                                            }
                                        }
                                        
                                        Number amt = (Number) rawItem.get("amount");
                                        pi.amount = amt != null ? amt.intValue() : 1;
                                        
                                        Number ch = (Number) rawItem.get("chance");
                                        pi.chance = ch != null ? ch.doubleValue() : 100.0;
                                        
                                        pi.customName = (String) rawItem.get("name");
                                        pi.customLore = (List<String>) rawItem.get("lore");
                                        if (pi.customLore == null) pi.customLore = new ArrayList<>();
 
                                        info.periodicItems.add(pi);
                                    }
                                }
                            }
 
                            // Periodical Commands
                            ConfigurationSection cmdSec = buffSec.getConfigurationSection("periodical-commands");
                            if (cmdSec == null) {
                                cmdSec = buffSec.getConfigurationSection("periodic-commands");
                            }
                            if (cmdSec != null) {
                                int interval = cmdSec.getInt("interval-minutes", 5);
                                List<Map<?, ?>> rawCmds = cmdSec.getMapList("commands");
                                if (rawCmds != null) {
                                    for (Map<?, ?> rawCmd : rawCmds) {
                                        PeriodicCommand pc = new PeriodicCommand();
                                        pc.intervalMinutes = interval;
                                        pc.command = (String) rawCmd.get("command");
                                        
                                        Number ch = (Number) rawCmd.get("chance");
                                        pc.chance = ch != null ? ch.doubleValue() : 100.0;
                                        
                                        info.periodicCommands.add(pc);
                                    }
                                }
                            }
                        }

                        territories.put(key, info);
                    }
                }
            }
        }
    }

    public void saveBannerLocation(String regionId, Location loc) {
        TerritoryInfo info = territories.get(regionId);
        if (info == null) return;
        info.bannerLocation = loc;

        // Save to file
        File folder = new File(plugin.getDataFolder(), "territories");
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                FileConfiguration config = YamlConfiguration.loadConfiguration(file);
                if (config.contains("territories." + regionId)) {
                    config.set("territories." + regionId + ".banner.world", loc.getWorld().getName());
                    config.set("territories." + regionId + ".banner.x", loc.getBlockX());
                    config.set("territories." + regionId + ".banner.y", loc.getBlockY());
                    config.set("territories." + regionId + ".banner.z", loc.getBlockZ());
                    try {
                        config.save(file);
                    } catch (Exception e) {
                        plugin.getLogger().severe("Gagal menyimpan lokasi banner ke " + file.getName());
                    }
                    break;
                }
            }
        }
    }

    private void saveResource(String resourcePath, File outFile) {
        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in == null) return;
            outFile.getParentFile().mkdirs();
            try (OutputStream out = new FileOutputStream(outFile)) {
                byte[] buf = new byte[1024];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Gagal menyalin resource default: " + resourcePath + " - " + e.getMessage());
        }
    }

    public Map<String, ArenaInfo> getArenas() {
        return arenas;
    }

    public Map<String, TerritoryInfo> getTerritories() {
        return territories;
    }

    public FileConfiguration getTerritoryInfoConfig() {
        return territoryInfoConfig;
    }

    public FileConfiguration getStatsConfig() {
        return statsConfig;
    }

    public FileConfiguration getTerritoryDetailConfig() {
        return territoryDetailConfig;
    }

    public FileConfiguration getLogsConfig() {
        return logsConfig;
    }

    public FileConfiguration getVaultConfig() {
        return vaultConfig;
    }

    public FileConfiguration getSeasonConfig() {
        return seasonConfig;
    }

    private void loadGuiConfigs() {
        territoryInfoFile = new File(plugin.getDataFolder(), "gui/territory_info.yml");
        if (!territoryInfoFile.exists()) {
            saveResource("gui/territory_info.yml", territoryInfoFile);
        }
        territoryInfoConfig = YamlConfiguration.loadConfiguration(territoryInfoFile);

        statsFile = new File(plugin.getDataFolder(), "gui/stats.yml");
        if (!statsFile.exists()) {
            saveResource("gui/stats.yml", statsFile);
        }
        statsConfig = YamlConfiguration.loadConfiguration(statsFile);

        territoryDetailFile = new File(plugin.getDataFolder(), "gui/territory_detail.yml");
        if (!territoryDetailFile.exists()) {
            saveResource("gui/territory_detail.yml", territoryDetailFile);
        }
        territoryDetailConfig = YamlConfiguration.loadConfiguration(territoryDetailFile);

        logsFile = new File(plugin.getDataFolder(), "gui/logs.yml");
        if (!logsFile.exists()) {
            saveResource("gui/logs.yml", logsFile);
        }
        logsConfig = YamlConfiguration.loadConfiguration(logsFile);
    }

    private void loadVault() {
        vaultFile = new File(plugin.getDataFolder(), "gui/vault.yml");
        if (!vaultFile.exists()) {
            saveResource("gui/vault.yml", vaultFile);
        }
        vaultConfig = YamlConfiguration.loadConfiguration(vaultFile);
    }

    private void loadSeason() {
        seasonFile = new File(plugin.getDataFolder(), "season.yml");
        if (!seasonFile.exists()) {
            saveResource("season.yml", seasonFile);
        }
        seasonConfig = YamlConfiguration.loadConfiguration(seasonFile);
    }

    public TerritoryInfo getTerritoryByLocation(Location loc) {
        if (loc == null) return null;
        // Search which WorldGuard region at this location matches our territories list
        if (Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) {
            try {
                com.sk89q.worldedit.util.Location weLoc = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(loc);
                com.sk89q.worldguard.WorldGuard wg = com.sk89q.worldguard.WorldGuard.getInstance();
                com.sk89q.worldguard.protection.regions.RegionContainer container = wg.getPlatform().getRegionContainer();
                com.sk89q.worldguard.protection.regions.RegionQuery query = container.createQuery();
                
                List<com.sk89q.worldguard.protection.regions.ProtectedRegion> list = new ArrayList<>();
                for (com.sk89q.worldguard.protection.regions.ProtectedRegion r : query.getApplicableRegions(weLoc)) {
                    list.add(r);
                }
                
                // Sort by priority descending (highest priority first)
                list.sort((r1, r2) -> Integer.compare(r2.getPriority(), r1.getPriority()));
                
                for (com.sk89q.worldguard.protection.regions.ProtectedRegion r : list) {
                    if (territories.containsKey(r.getId())) {
                        return territories.get(r.getId());
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private final Map<java.util.UUID, TerritoryInfo> playerTerritoryCache = new java.util.concurrent.ConcurrentHashMap<>();

    public TerritoryInfo getCachedTerritory(java.util.UUID uuid) {
        return playerTerritoryCache.get(uuid);
    }

    public void updateCachedTerritory(org.bukkit.entity.Player player) {
        if (player == null) return;
        TerritoryInfo territory = getTerritoryByLocation(player.getLocation());
        if (territory == null) {
            playerTerritoryCache.remove(player.getUniqueId());
        } else {
            playerTerritoryCache.put(player.getUniqueId(), territory);
        }
    }

    public void clearCachedTerritory(java.util.UUID uuid) {
        playerTerritoryCache.remove(uuid);
    }
}
