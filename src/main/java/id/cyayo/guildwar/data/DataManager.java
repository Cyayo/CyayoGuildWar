package id.cyayo.guildwar.data;

import id.cyayo.guildwar.CyayoGuildWar;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class DataManager {

    private final CyayoGuildWar plugin;
    private File dataFile;
    private FileConfiguration dataConfig;
    
    // Vault storage variables
    private File vaultsFile;
    private FileConfiguration vaultsConfig;
    private final Map<UUID, List<org.bukkit.inventory.ItemStack>> vaultCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, List<String>> logsCache = new java.util.concurrent.ConcurrentHashMap<>();

    // SQLite variables
    private boolean useSQLite = false;
    private String sqliteUrl;

    // Cache to hold territory ownership details
    // regionName -> OwnershipInfo
    private final Map<String, OwnershipInfo> ownershipCache = new HashMap<>();
    
    // Cache to hold custom Guild banner design details
    // guildId -> BannerInfo (using OwnershipInfo structure for reuse)
    private final Map<UUID, OwnershipInfo> guildBannersCache = new HashMap<>();

    // Cache to hold Guild Season Rating (SR)
    private final Map<UUID, Integer> guildSrCache = new HashMap<>();
    private final Map<UUID, String> guildNameCache = new HashMap<>();

    // Cache to hold player war toggles (true = enabled, false = disabled)
    private final Map<UUID, Boolean> playerToggleCache = new HashMap<>();

    public static class OwnershipInfo {
        public UUID ownerGuildId;
        public String ownerGuildName;
        public long cooldownUntil;
        public DyeColor baseColor;
        public List<Pattern> patterns = new ArrayList<>();

        public OwnershipInfo() {}
    }

    public DataManager(CyayoGuildWar plugin) {
        this.plugin = plugin;
        initDatabase();
    }

    private void initDatabase() {
        String dbType = plugin.getConfig().getString("database.type", "YAML").toUpperCase();
        if (dbType.equals("SQLITE")) {
            useSQLite = true;
            File dbFile = new File(plugin.getDataFolder(), plugin.getConfig().getString("database.sqlite-file", "database.db"));
            sqliteUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();
            try {
                // Load SQLite driver
                Class.forName("org.sqlite.JDBC");
                try (Connection conn = getConnection()) {
                    if (conn != null) {
                        String sql = "CREATE TABLE IF NOT EXISTS territory_ownership (" +
                                "region_id TEXT PRIMARY KEY," +
                                "owner_guild_id TEXT," +
                                "owner_guild_name TEXT," +
                                "cooldown_until INTEGER," +
                                "base_color TEXT," +
                                "patterns TEXT" +
                                ");";
                        String sql2 = "CREATE TABLE IF NOT EXISTS guild_banners (" +
                                "guild_id TEXT PRIMARY KEY," +
                                "base_color TEXT," +
                                "patterns TEXT" +
                                ");";
                        String sql3 = "CREATE TABLE IF NOT EXISTS guild_sr (" +
                                "guild_id TEXT PRIMARY KEY," +
                                "guild_name TEXT," +
                                "sr_points INTEGER" +
                                ");";
                        String sql4 = "CREATE TABLE IF NOT EXISTS player_toggle (" +
                                "uuid TEXT PRIMARY KEY," +
                                "enabled INTEGER" +
                                ");";
                        String sql5 = "CREATE TABLE IF NOT EXISTS guild_vaults (" +
                                "guild_id TEXT PRIMARY KEY," +
                                "items TEXT" +
                                ");";
                        String sql6 = "CREATE TABLE IF NOT EXISTS guild_logs (" +
                                "guild_id TEXT PRIMARY KEY," +
                                "logs TEXT" +
                                ");";
                        try (Statement stmt = conn.createStatement()) {
                            stmt.execute(sql);
                            stmt.execute(sql2);
                            stmt.execute(sql3);
                            stmt.execute(sql4);
                            stmt.execute(sql5);
                            stmt.execute(sql6);
                        }
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Gagal menginisialisasi database SQLite! Beralih menggunakan YAML data.yml. Error: " + e.getMessage());
                useSQLite = false;
            }
        }

        if (!useSQLite) {
            dataFile = new File(plugin.getDataFolder(), "data.yml");
            if (!dataFile.exists()) {
                try {
                    dataFile.getParentFile().mkdirs();
                    dataFile.createNewFile();
                } catch (IOException e) {
                    plugin.getLogger().severe("Gagal membuat file data.yml! " + e.getMessage());
                }
            }
            dataConfig = YamlConfiguration.loadConfiguration(dataFile);

            java.io.File oldFile = new java.io.File(plugin.getDataFolder(), "vaults.yml");
            vaultsFile = new java.io.File(plugin.getDataFolder(), "vaults-data.yml");
            if (oldFile.exists() && !vaultsFile.exists()) {
                oldFile.renameTo(vaultsFile);
                plugin.getLogger().info("Berhasil migrasi data brankas dari vaults.yml ke vaults-data.yml");
            }

            if (!vaultsFile.exists()) {
                try {
                    vaultsFile.getParentFile().mkdirs();
                    vaultsFile.createNewFile();
                } catch (IOException e) {
                    plugin.getLogger().severe("Gagal membuat file vaults-data.yml! " + e.getMessage());
                }
            }
            vaultsConfig = YamlConfiguration.loadConfiguration(vaultsFile);
        }

        loadAllData();
    }

    private Connection getConnection() throws SQLException {
        if (!useSQLite) return null;
        return DriverManager.getConnection(sqliteUrl);
    }

    public void loadAllData() {
        vaultCache.clear();
        logsCache.clear();
        ownershipCache.clear();
        guildBannersCache.clear();
        if (useSQLite) {
            String sql = "SELECT * FROM territory_ownership";
            try (Connection conn = getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String regionId = rs.getString("region_id");
                    OwnershipInfo info = new OwnershipInfo();
                    
                    String guildIdStr = rs.getString("owner_guild_id");
                    if (guildIdStr != null && !guildIdStr.isEmpty()) {
                        info.ownerGuildId = UUID.fromString(guildIdStr);
                    }
                    
                    info.ownerGuildName = rs.getString("owner_guild_name");
                    info.cooldownUntil = rs.getLong("cooldown_until");
                    
                    String baseColStr = rs.getString("base_color");
                    if (baseColStr != null && !baseColStr.isEmpty()) {
                        try {
                            info.baseColor = DyeColor.valueOf(baseColStr);
                        } catch (Exception ignored) {}
                    }
                    
                    String patStr = rs.getString("patterns");
                    if (patStr != null && !patStr.isEmpty()) {
                        info.patterns = deserializePatterns(patStr);
                    }
                    
                    ownershipCache.put(regionId, info);
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Gagal membaca data dari SQLite! " + e.getMessage());
            }

            // Load Banners
            String sqlBanners = "SELECT * FROM guild_banners";
            try (Connection conn = getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sqlBanners);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    UUID gId = UUID.fromString(rs.getString("guild_id"));
                    OwnershipInfo info = new OwnershipInfo();
                    
                    String baseColStr = rs.getString("base_color");
                    if (baseColStr != null && !baseColStr.isEmpty()) {
                        try {
                            info.baseColor = DyeColor.valueOf(baseColStr);
                        } catch (Exception ignored) {}
                    }
                    
                    String patStr = rs.getString("patterns");
                    if (patStr != null && !patStr.isEmpty()) {
                        info.patterns = deserializePatterns(patStr);
                    }
                    guildBannersCache.put(gId, info);
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Gagal membaca data banner dari SQLite! " + e.getMessage());
            }

            // Load SR from SQLite
            String sqlSr = "SELECT * FROM guild_sr";
            try (Connection conn = getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sqlSr);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    UUID gId = UUID.fromString(rs.getString("guild_id"));
                    String gName = rs.getString("guild_name");
                    int srVal = rs.getInt("sr_points");
                    guildSrCache.put(gId, srVal);
                    guildNameCache.put(gId, gName);
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Gagal membaca data SR dari SQLite! " + e.getMessage());
            }

            // Load Player Toggles from SQLite
            String sqlToggle = "SELECT * FROM player_toggle";
            try (Connection conn = getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sqlToggle);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    UUID pUuid = UUID.fromString(rs.getString("uuid"));
                    boolean enabled = rs.getInt("enabled") == 1;
                    playerToggleCache.put(pUuid, enabled);
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Gagal membaca data player toggle dari SQLite! " + e.getMessage());
            }
        } else {
            if (dataConfig == null) return;
            ConfigurationSection sec = dataConfig.getConfigurationSection("ownership");
            if (sec != null) {
                for (String key : sec.getKeys(false)) {
                    OwnershipInfo info = new OwnershipInfo();
                    String gId = sec.getString(key + ".owner-guild-id");
                    if (gId != null && !gId.isEmpty()) {
                        info.ownerGuildId = UUID.fromString(gId);
                    }
                    info.ownerGuildName = sec.getString(key + ".owner-guild-name");
                    info.cooldownUntil = sec.getLong(key + ".cooldown-until", 0);
                    
                    String baseCol = sec.getString(key + ".banner.base-color");
                    if (baseCol != null) {
                        try {
                            info.baseColor = DyeColor.valueOf(baseCol);
                        } catch (Exception ignored) {}
                    }
                    
                    List<String> patList = sec.getStringList(key + ".banner.patterns");
                    if (patList != null) {
                        for (String pat : patList) {
                            String[] split = pat.split(":");
                            if (split.length == 2) {
                                try {
                                    PatternType type = PatternType.valueOf(split[0]);
                                    DyeColor color = DyeColor.valueOf(split[1]);
                                    info.patterns.add(new Pattern(color, type));
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                    ownershipCache.put(key, info);
                }
            }

            // Load Banners from YAML
            ConfigurationSection bannerSec = dataConfig.getConfigurationSection("guild-banners");
            if (bannerSec != null) {
                for (String key : bannerSec.getKeys(false)) {
                    UUID gId = UUID.fromString(key);
                    OwnershipInfo info = new OwnershipInfo();
                    String baseCol = bannerSec.getString(key + ".base-color");
                    if (baseCol != null) {
                        try {
                            info.baseColor = DyeColor.valueOf(baseCol);
                        } catch (Exception ignored) {}
                    }
                    
                    List<String> patList = bannerSec.getStringList(key + ".patterns");
                    if (patList != null) {
                        for (String pat : patList) {
                            String[] split = pat.split(":");
                            if (split.length == 2) {
                                try {
                                    PatternType type = PatternType.valueOf(split[0]);
                                    DyeColor color = DyeColor.valueOf(split[1]);
                                    info.patterns.add(new Pattern(color, type));
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                    guildBannersCache.put(gId, info);
                }
            }

            // Load SR from YAML
            ConfigurationSection srSec = dataConfig.getConfigurationSection("season-rating");
            if (srSec != null) {
                for (String key : srSec.getKeys(false)) {
                    UUID gId = UUID.fromString(key);
                    String gName = srSec.getString(key + ".guild-name", "Unknown");
                    int srVal = srSec.getInt(key + ".points", 0);
                    guildSrCache.put(gId, srVal);
                    guildNameCache.put(gId, gName);
                }
            }

            // Load Player Toggles from YAML
            ConfigurationSection togglesSec = dataConfig.getConfigurationSection("player-toggles");
            if (togglesSec != null) {
                for (String key : togglesSec.getKeys(false)) {
                    UUID pUuid = UUID.fromString(key);
                    boolean enabled = togglesSec.getBoolean(key, true);
                    playerToggleCache.put(pUuid, enabled);
                }
            }
        }
    }

    public OwnershipInfo getOwnership(String regionId) {
        return ownershipCache.computeIfAbsent(regionId, k -> new OwnershipInfo());
    }

    public void clearAllOwnerships() {
        for (Map.Entry<String, OwnershipInfo> entry : ownershipCache.entrySet()) {
            OwnershipInfo info = entry.getValue();
            info.ownerGuildId = null;
            info.ownerGuildName = null;
            info.cooldownUntil = 0;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (useSQLite) {
                String sql = "UPDATE territory_ownership SET owner_guild_id = '', owner_guild_name = '', cooldown_until = 0";
                try (Connection conn = getConnection();
                     PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.executeUpdate();
                } catch (SQLException e) {
                    plugin.getLogger().severe("Gagal reset kepemilikan teritori di SQLite! " + e.getMessage());
                }
            } else {
                if (dataConfig == null) return;
                synchronized (dataConfig) {
                    ConfigurationSection sec = dataConfig.getConfigurationSection("ownership");
                    if (sec != null) {
                        for (String key : sec.getKeys(false)) {
                            dataConfig.set("ownership." + key + ".owner-guild-id", "");
                            dataConfig.set("ownership." + key + ".owner-guild-name", "");
                            dataConfig.set("ownership." + key + ".cooldown-until", 0);
                        }
                    }
                    try {
                        dataConfig.save(dataFile);
                    } catch (IOException e) {
                        plugin.getLogger().severe("Gagal menyimpan reset kepemilikan teritori ke data.yml! " + e.getMessage());
                    }
                }
            }
        });
    }

    public void saveOwnership(String regionId, OwnershipInfo info) {
        ownershipCache.put(regionId, info);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (useSQLite) {
                String sql = "INSERT OR REPLACE INTO territory_ownership(region_id, owner_guild_id, owner_guild_name, cooldown_until, base_color, patterns) VALUES(?,?,?,?,?,?)";
                try (Connection conn = getConnection();
                     PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, regionId);
                    pstmt.setString(2, info.ownerGuildId != null ? info.ownerGuildId.toString() : "");
                    pstmt.setString(3, info.ownerGuildName != null ? info.ownerGuildName : "");
                    pstmt.setLong(4, info.cooldownUntil);
                    pstmt.setString(5, info.baseColor != null ? info.baseColor.name() : "");
                    pstmt.setString(6, serializePatterns(info.patterns));
                    pstmt.executeUpdate();
                } catch (SQLException e) {
                    plugin.getLogger().severe("Gagal menyimpan data ke SQLite! " + e.getMessage());
                }
            } else {
                if (dataConfig == null) return;
                synchronized (dataConfig) {
                    dataConfig.set("ownership." + regionId + ".owner-guild-id", info.ownerGuildId != null ? info.ownerGuildId.toString() : "");
                    dataConfig.set("ownership." + regionId + ".owner-guild-name", info.ownerGuildName != null ? info.ownerGuildName : "");
                    dataConfig.set("ownership." + regionId + ".cooldown-until", info.cooldownUntil);
                    dataConfig.set("ownership." + regionId + ".banner.base-color", info.baseColor != null ? info.baseColor.name() : "");
                    
                    List<String> patList = new ArrayList<>();
                    for (Pattern p : info.patterns) {
                        patList.add(p.getPattern().name() + ":" + p.getColor().name());
                    }
                    dataConfig.set("ownership." + regionId + ".banner.patterns", patList);
                    try {
                        dataConfig.save(dataFile);
                    } catch (IOException e) {
                        plugin.getLogger().severe("Gagal menyimpan data.yml! " + e.getMessage());
                    }
                }
            }
        });
    }

    private String serializePatterns(List<Pattern> patterns) {
        if (patterns == null || patterns.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < patterns.size(); i++) {
            Pattern p = patterns.get(i);
            sb.append(p.getPattern().name()).append(":").append(p.getColor().name());
            if (i < patterns.size() - 1) {
                sb.append(";");
            }
        }
        return sb.toString();
    }

    private List<Pattern> deserializePatterns(String data) {
        List<Pattern> patterns = new ArrayList<>();
        if (data == null || data.isEmpty()) return patterns;
        String[] parts = data.split(";");
        for (String part : parts) {
            String[] split = part.split(":");
            if (split.length == 2) {
                try {
                    PatternType type = PatternType.valueOf(split[0]);
                    DyeColor color = DyeColor.valueOf(split[1]);
                    patterns.add(new Pattern(color, type));
                } catch (Exception ignored) {}
            }
        }
        return patterns;
    }
    public void saveGuildBanner(UUID guildId, DyeColor baseColor, List<Pattern> patterns) {
        OwnershipInfo info = guildBannersCache.computeIfAbsent(guildId, k -> new OwnershipInfo());
        info.baseColor = baseColor;
        info.patterns = patterns;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (useSQLite) {
                String sql = "INSERT OR REPLACE INTO guild_banners(guild_id, base_color, patterns) VALUES(?,?,?)";
                try (Connection conn = getConnection();
                     PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, guildId.toString());
                    pstmt.setString(2, baseColor != null ? baseColor.name() : "");
                    pstmt.setString(3, serializePatterns(patterns));
                    pstmt.executeUpdate();
                } catch (SQLException e) {
                    plugin.getLogger().severe("Gagal menyimpan banner guild ke SQLite! " + e.getMessage());
                }
            } else {
                if (dataConfig == null) return;
                synchronized (dataConfig) {
                    dataConfig.set("guild-banners." + guildId + ".base-color", baseColor != null ? baseColor.name() : "");
                    List<String> patList = new ArrayList<>();
                    for (Pattern p : patterns) {
                        patList.add(p.getPattern().name() + ":" + p.getColor().name());
                    }
                    dataConfig.set("guild-banners." + guildId + ".patterns", patList);
                    try {
                        dataConfig.save(dataFile);
                    } catch (IOException e) {
                        plugin.getLogger().severe("Gagal menyimpan data.yml! " + e.getMessage());
                    }
                }
            }
        });
    }

    public OwnershipInfo getGuildBanner(UUID guildId) {
        if (guildId == null) return null;
        return guildBannersCache.get(guildId);
    }

    public int getGuildSR(UUID guildId) {
        if (guildId == null) return 0;
        return guildSrCache.getOrDefault(guildId, 0);
    }

    public void addGuildSR(UUID guildId, String guildName, int amount) {
        if (guildId == null) return;
        int current = getGuildSR(guildId);
        int newVal = current + amount;
        guildSrCache.put(guildId, newVal);
        if (guildName != null) {
            guildNameCache.put(guildId, guildName);
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (useSQLite) {
                String sql = "INSERT OR REPLACE INTO guild_sr(guild_id, guild_name, sr_points) VALUES(?,?,?)";
                try (Connection conn = getConnection();
                     PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, guildId.toString());
                    pstmt.setString(2, guildName != null ? guildName : "Unknown");
                    pstmt.setInt(3, newVal);
                    pstmt.executeUpdate();
                } catch (SQLException e) {
                    plugin.getLogger().severe("Gagal menyimpan SR guild ke SQLite! " + e.getMessage());
                }
            } else {
                if (dataConfig == null) return;
                synchronized (dataConfig) {
                    dataConfig.set("season-rating." + guildId + ".guild-name", guildName != null ? guildName : "Unknown");
                    dataConfig.set("season-rating." + guildId + ".points", newVal);
                    try {
                        dataConfig.save(dataFile);
                    } catch (IOException e) {
                        plugin.getLogger().severe("Gagal menyimpan data.yml! " + e.getMessage());
                    }
                }
            }
        });
    }

    public void resetAllSR() {
        guildSrCache.clear();
        guildNameCache.clear();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (useSQLite) {
                String sql = "DELETE FROM guild_sr";
                try (Connection conn = getConnection();
                     PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.executeUpdate();
                } catch (SQLException e) {
                    plugin.getLogger().severe("Gagal mengosongkan tabel SR di SQLite! " + e.getMessage());
                }
            } else {
                if (dataConfig == null) return;
                synchronized (dataConfig) {
                    dataConfig.set("season-rating", null);
                    try {
                        dataConfig.save(dataFile);
                    } catch (IOException e) {
                        plugin.getLogger().severe("Gagal menyimpan data.yml! " + e.getMessage());
                    }
                }
            }
        });
    }

    public Map<UUID, Integer> getAllGuildSR() {
        return new HashMap<>(guildSrCache);
    }

    public static class GuildSrEntry {
        public UUID guildId;
        public String guildName;
        public int srPoints;

        public GuildSrEntry(UUID guildId, String guildName, int srPoints) {
            this.guildId = guildId;
            this.guildName = guildName;
            this.srPoints = srPoints;
        }
    }

    public List<GuildSrEntry> getTopGuilds() {
        List<GuildSrEntry> list = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : guildSrCache.entrySet()) {
            UUID gId = entry.getKey();
            String name = guildNameCache.getOrDefault(gId, "Unknown");
            list.add(new GuildSrEntry(gId, name, entry.getValue()));
        }
        list.sort((e1, e2) -> Integer.compare(e2.srPoints, e1.srPoints));
        return list;
    }

    public String getGuildNameFromCache(UUID guildId) {
        if (guildId == null) return "Unknown";
        return guildNameCache.getOrDefault(guildId, "Unknown");
    }

    public boolean getPlayerToggle(UUID uuid) {
        if (uuid == null) return true;
        // Default true jika belum pernah diset (artinya ikut war)
        return playerToggleCache.getOrDefault(uuid, true);
    }

    public void setPlayerToggle(UUID uuid, boolean enabled) {
        if (uuid == null) return;
        playerToggleCache.put(uuid, enabled);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (useSQLite) {
                String sql = "INSERT OR REPLACE INTO player_toggle(uuid, enabled) VALUES(?,?)";
                try (Connection conn = getConnection();
                     PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, uuid.toString());
                    pstmt.setInt(2, enabled ? 1 : 0);
                    pstmt.executeUpdate();
                } catch (SQLException e) {
                    plugin.getLogger().severe("Gagal menyimpan player toggle ke SQLite! " + e.getMessage());
                }
            } else {
                if (dataConfig == null) return;
                synchronized (dataConfig) {
                    dataConfig.set("player-toggles." + uuid.toString(), enabled);
                    try {
                        dataConfig.save(dataFile);
                    } catch (IOException e) {
                        plugin.getLogger().severe("Gagal menyimpan data.yml! " + e.getMessage());
                    }
                }
            }
        });
    }

    public String serializeItemStacks(List<org.bukkit.inventory.ItemStack> items) {
        try {
            java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
            org.bukkit.util.io.BukkitObjectOutputStream dataOutput = new org.bukkit.util.io.BukkitObjectOutputStream(outputStream);
            
            dataOutput.writeInt(items.size());
            for (org.bukkit.inventory.ItemStack item : items) {
                dataOutput.writeObject(item);
            }
            dataOutput.close();
            return java.util.Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (Exception e) {
            plugin.getLogger().severe("Gagal serialisasi items: " + e.getMessage());
            return "";
        }
    }

    public List<org.bukkit.inventory.ItemStack> deserializeItemStacks(String data) {
        List<org.bukkit.inventory.ItemStack> items = new ArrayList<>();
        if (data == null || data.isEmpty()) return items;
        try {
            java.io.ByteArrayInputStream inputStream = new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(data));
            org.bukkit.util.io.BukkitObjectInputStream dataInput = new org.bukkit.util.io.BukkitObjectInputStream(inputStream);
            
            int size = dataInput.readInt();
            for (int i = 0; i < size; i++) {
                items.add((org.bukkit.inventory.ItemStack) dataInput.readObject());
            }
            dataInput.close();
        } catch (Exception e) {
            plugin.getLogger().severe("Gagal deserialisasi items: " + e.getMessage());
        }
        return items;
    }

    public List<org.bukkit.inventory.ItemStack> getVaultItems(UUID guildId) {
        if (guildId == null) return new ArrayList<>();
        if (vaultCache.containsKey(guildId)) {
            List<org.bukkit.inventory.ItemStack> cached = vaultCache.get(guildId);
            List<org.bukkit.inventory.ItemStack> copy = new ArrayList<>();
            for (org.bukkit.inventory.ItemStack item : cached) {
                copy.add(item != null ? item.clone() : null);
            }
            return copy;
        }

        List<org.bukkit.inventory.ItemStack> loaded = new ArrayList<>();
        if (useSQLite) {
            String sql = "SELECT items FROM guild_vaults WHERE guild_id = ?";
            try (Connection conn = getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, guildId.toString());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        loaded = deserializeItemStacks(rs.getString("items"));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Gagal membaca vault items dari SQLite: " + e.getMessage());
            }
        } else {
            if (vaultsConfig != null) {
                synchronized (vaultsConfig) {
                    String data = vaultsConfig.getString("vaults." + guildId + ".items", "");
                    loaded = deserializeItemStacks(data);
                }
            }
        }

        vaultCache.put(guildId, loaded);
        List<org.bukkit.inventory.ItemStack> copy = new ArrayList<>();
        for (org.bukkit.inventory.ItemStack item : loaded) {
            copy.add(item != null ? item.clone() : null);
        }
        return copy;
    }

    public void saveVaultItems(UUID guildId, List<org.bukkit.inventory.ItemStack> items) {
        if (guildId == null) return;

        // Update the cache immediately (synchronously) to prevent async race conditions
        List<org.bukkit.inventory.ItemStack> cacheCopy = new ArrayList<>();
        for (org.bukkit.inventory.ItemStack item : items) {
            cacheCopy.add(item != null ? item.clone() : null);
        }
        vaultCache.put(guildId, cacheCopy);

        String serialized = serializeItemStacks(items);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (useSQLite) {
                String sql = "INSERT OR REPLACE INTO guild_vaults(guild_id, items) VALUES(?,?)";
                try (Connection conn = getConnection();
                     PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, guildId.toString());
                    pstmt.setString(2, serialized);
                    pstmt.executeUpdate();
                } catch (SQLException e) {
                    plugin.getLogger().severe("Gagal menyimpan vault items ke SQLite: " + e.getMessage());
                }
            } else {
                if (vaultsConfig == null) return;
                synchronized (vaultsConfig) {
                    vaultsConfig.set("vaults." + guildId + ".items", serialized);
                    try {
                        vaultsConfig.save(vaultsFile);
                    } catch (IOException e) {
                        plugin.getLogger().severe("Gagal menyimpan vaults-data.yml: " + e.getMessage());
                    }
                }
            }
        });
    }

    public static void addAndStackItem(List<org.bukkit.inventory.ItemStack> items, org.bukkit.inventory.ItemStack newItem) {
        if (newItem == null || newItem.getType() == org.bukkit.Material.AIR) return;
        int amountToAdd = newItem.getAmount();
        
        // Try to stack with existing items first
        for (org.bukkit.inventory.ItemStack existing : items) {
            if (existing != null && existing.getType() != org.bukkit.Material.AIR) {
                if (existing.isSimilar(newItem)) {
                    int maxStack = existing.getMaxStackSize();
                    int currentAmount = existing.getAmount();
                    if (currentAmount < maxStack) {
                        int space = maxStack - currentAmount;
                        int toAdd = Math.min(amountToAdd, space);
                        existing.setAmount(currentAmount + toAdd);
                        amountToAdd -= toAdd;
                        if (amountToAdd <= 0) {
                            return;
                        }
                    }
                }
            }
        }
        
        // Find empty slot (represented by null or AIR) within the list to prevent extending list
        for (int i = 0; i < items.size(); i++) {
            org.bukkit.inventory.ItemStack existing = items.get(i);
            if (existing == null || existing.getType() == org.bukkit.Material.AIR) {
                org.bukkit.inventory.ItemStack toSet = newItem.clone();
                toSet.setAmount(amountToAdd);
                items.set(i, toSet);
                return;
            }
        }
        
        // Append if no empty slot in current list
        org.bukkit.inventory.ItemStack toSet = newItem.clone();
        toSet.setAmount(amountToAdd);
        items.add(toSet);
    }

    public List<String> getGuildLogs(UUID guildId) {
        if (guildId == null) return new ArrayList<>();
        if (logsCache.containsKey(guildId)) {
            return new ArrayList<>(logsCache.get(guildId));
        }

        List<String> loaded = new ArrayList<>();
        if (useSQLite) {
            String sql = "SELECT logs FROM guild_logs WHERE guild_id = ?";
            try (Connection conn = getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, guildId.toString());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        String data = rs.getString("logs");
                        if (data != null && !data.isEmpty()) {
                            loaded = new ArrayList<>(java.util.Arrays.asList(data.split("\n")));
                        }
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Gagal membaca guild logs dari SQLite: " + e.getMessage());
            }
        } else {
            if (dataConfig != null) {
                synchronized (dataConfig) {
                    loaded = dataConfig.getStringList("logs." + guildId);
                }
            }
        }
        if (loaded == null) loaded = new ArrayList<>();

        logsCache.put(guildId, loaded);
        return new ArrayList<>(loaded);
    }

    public void addGuildLog(UUID guildId, String message) {
        if (guildId == null || message == null) return;
        
        List<String> logs = getGuildLogs(guildId);
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String timestamp = sdf.format(new java.util.Date());
        
        // Add log entry at the beginning (newest first)
        logs.add(0, timestamp + "|" + message);
        
        // Keep only last 50
        while (logs.size() > 50) {
            logs.remove(logs.size() - 1);
        }
        
        // Update cache
        logsCache.put(guildId, new ArrayList<>(logs));
        
        // Join for SQLite
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < logs.size(); i++) {
            sb.append(logs.get(i));
            if (i < logs.size() - 1) sb.append("\n");
        }
        String serialized = sb.toString();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (useSQLite) {
                String sql = "INSERT OR REPLACE INTO guild_logs(guild_id, logs) VALUES(?,?)";
                try (Connection conn = getConnection();
                     PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, guildId.toString());
                    pstmt.setString(2, serialized);
                    pstmt.executeUpdate();
                } catch (SQLException e) {
                    plugin.getLogger().severe("Gagal menyimpan guild logs ke SQLite: " + e.getMessage());
                }
            } else {
                if (dataConfig == null) return;
                synchronized (dataConfig) {
                    dataConfig.set("logs." + guildId, logs);
                    try {
                        dataConfig.save(dataFile);
                    } catch (IOException e) {
                        plugin.getLogger().severe("Gagal menyimpan data.yml logs: " + e.getMessage());
                    }
                }
            }
        });
    }

    public List<String> formatLogMessageList(org.bukkit.configuration.file.FileConfiguration config, String rawMsg) {
        List<String> formattedLines = new ArrayList<>();
        if (rawMsg == null || rawMsg.isEmpty()) return formattedLines;
        if (config == null) {
            formattedLines.add(rawMsg);
            return formattedLines;
        }
        String[] tokens = rawMsg.split(";");
        String type = tokens[0];

        List<String> formatList = new ArrayList<>();
        if (config.isList("formats." + type)) {
            formatList = config.getStringList("formats." + type);
        } else {
            String single = config.getString("formats." + type, rawMsg);
            formatList.add(single);
        }

        for (String line : formatList) {
            String format = line;
            if (type.equals("capture-victory") && tokens.length > 1) {
                format = format.replace("{territory}", tokens[1]);
            } else if (type.equals("capture-defeat") && tokens.length > 2) {
                format = format.replace("{territory}", tokens[1]).replace("{guild}", tokens[2]);
            } else if (type.equals("defend-victory") && tokens.length > 2) {
                format = format.replace("{territory}", tokens[1]).replace("{guild}", tokens[2]);
            } else if (type.equals("defend-defeat") && tokens.length > 2) {
                format = format.replace("{territory}", tokens[1]).replace("{guild}", tokens[2]);
            } else if (type.equals("periodic-rewards") && tokens.length > 1) {
                format = format.replace("{items}", tokens[1]);
            }
            formattedLines.add(format);
        }
        return formattedLines;
    }

    public String formatLogMessage(org.bukkit.configuration.file.FileConfiguration config, String rawMsg) {
        List<String> lines = formatLogMessageList(config, rawMsg);
        return String.join(" ", lines);
    }
}
