package id.cyayo.guildwar.manager;

import id.cyayo.guildwar.CyayoGuildWar;
import id.cyayo.guildwar.data.DataManager;
import id.cyayo.guildwar.hook.GuildsHook;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.text.SimpleDateFormat;
import java.util.*;

public class SeasonManager {

    private final CyayoGuildWar plugin;
    private BukkitTask scheduledTask;
    private long nextResetTime = 0;
    private int currentSeason = 1;
    private long peacePeriodEnd = 0;

    public SeasonManager(CyayoGuildWar plugin) {
        this.plugin = plugin;
        reloadSeasonTimer();
    }

    public void reloadSeasonTimer() {
        if (scheduledTask != null) {
            scheduledTask.cancel();
            scheduledTask = null;
        }

        org.bukkit.configuration.file.FileConfiguration cfg = plugin.getConfigManager().getSeasonConfig();
        if (!cfg.getBoolean("enabled", true)) {
            plugin.getLogger().info("[Season] Sistem season dinonaktifkan (enabled: false di season.yml).");
            return;
        }

        currentSeason = cfg.getInt("current-season", 1);
        peacePeriodEnd = cfg.getLong("peace-period-end-timestamp", 0);

        calculateNextResetTime();
        
        long now = System.currentTimeMillis();
        long delayMillis = nextResetTime - now;
        long delayTicks = delayMillis / 50L;

        if (delayTicks <= 0) {
            // Already past the reset time, trigger now and reschedule!
            runSeasonReset();
        } else {
            plugin.getLogger().info("[Season] Season " + currentSeason + " | Jadwal reset berikutnya: " + new Date(nextResetTime).toString() + " (" + (delayMillis / 1000 / 60) + " menit lagi)");
            if (isPeacePeriod()) {
                plugin.getLogger().info("[Season] Saat ini dalam Masa Damai (hingga " + new Date(peacePeriodEnd).toString() + ")");
            }
            scheduledTask = Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                @Override
                public void run() {
                    runSeasonReset();
                }
            }, delayTicks);
        }
    }

    private void calculateNextResetTime() {
        org.bukkit.configuration.file.FileConfiguration cfg = plugin.getConfigManager().getSeasonConfig();
        String startStr = cfg.getString("start-date", "2026-07-09 00:00:00");
        String durStr = cfg.getString("duration", "7d");

        long startMillis;
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            startMillis = sdf.parse(startStr).getTime();
        } catch (Exception e) {
            plugin.getLogger().warning("[Season] Format start-date salah: " + startStr + ". Menggunakan waktu sekarang.");
            startMillis = System.currentTimeMillis();
        }

        long durMillis = parseDuration(durStr);
        long now = System.currentTimeMillis();

        if (now < startMillis) {
            nextResetTime = startMillis;
        } else {
            long elapsed = (now - startMillis) / durMillis;
            nextResetTime = startMillis + (elapsed + 1) * durMillis;
        }
    }

    private long parseDuration(String dur) {
        if (dur == null || dur.isEmpty()) return 7 * 24 * 60 * 60 * 1000L;
        dur = dur.toLowerCase();
        try {
            if (dur.endsWith("s")) {
                return Long.parseLong(dur.replace("s", "")) * 1000L;
            } else if (dur.endsWith("m")) {
                return Long.parseLong(dur.replace("m", "")) * 60 * 1000L;
            } else if (dur.endsWith("h")) {
                return Long.parseLong(dur.replace("h", "")) * 60 * 60 * 1000L;
            } else if (dur.endsWith("d")) {
                return Long.parseLong(dur.replace("d", "")) * 24 * 60 * 60 * 1000L;
            } else if (dur.endsWith("w")) {
                return Long.parseLong(dur.replace("w", "")) * 7 * 24 * 60 * 60 * 1000L;
            } else if (dur.endsWith("mo")) {
                return Long.parseLong(dur.replace("mo", "")) * 30 * 24 * 60 * 60 * 1000L;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Season] Gagal parse durasi: " + dur + ". Menggunakan default 7 hari.");
        }
        return 7 * 24 * 60 * 60 * 1000L;
    }

    public boolean isPeacePeriod() {
        org.bukkit.configuration.file.FileConfiguration cfg = plugin.getConfigManager().getSeasonConfig();
        if (!cfg.getBoolean("enabled", true)) return false;
        return System.currentTimeMillis() < peacePeriodEnd;
    }

    public int getCurrentSeason() {
        return currentSeason;
    }

    public void setSeasonNumber(int num) {
        this.currentSeason = num;
        saveSeasonData();
    }

    public void saveSeasonData() {
        org.bukkit.configuration.file.FileConfiguration cfg = plugin.getConfigManager().getSeasonConfig();
        cfg.set("current-season", currentSeason);
        cfg.set("peace-period-end-timestamp", peacePeriodEnd);
        try {
            java.io.File file = new java.io.File(plugin.getDataFolder(), "season.yml");
            cfg.save(file);
        } catch (Exception e) {
            plugin.getLogger().warning("[Season] Gagal menyimpan season.yml: " + e.getMessage());
        }
    }

    public void runSeasonReset() {
        org.bukkit.configuration.file.FileConfiguration cfg = plugin.getConfigManager().getSeasonConfig();
        if (!cfg.getBoolean("enabled", true)) return;

        plugin.getLogger().info("[Season] Memulai proses reset Season Rating...");

        // 1. Get top guilds
        List<DataManager.GuildSrEntry> topGuilds = plugin.getDataManager().getTopGuilds();
        int threshold = cfg.getInt("min-sr-threshold", 100);

        org.bukkit.configuration.ConfigurationSection rewardSection = cfg.getConfigurationSection("rewards");
        if (rewardSection != null) {
            for (int i = 0; i < topGuilds.size(); i++) {
                DataManager.GuildSrEntry entry = topGuilds.get(i);
                int rank = i + 1;
                
                // Check threshold
                if (entry.srPoints < threshold) {
                    plugin.getLogger().info("[Season] Guild " + entry.guildName + " (Rank " + rank + ") tidak memenuhi threshold SR (" + entry.srPoints + "/" + threshold + ")");
                    continue;
                }

                String rankKey = "top-" + rank;
                if (rewardSection.contains(rankKey)) {
                    // Distribute items
                    List<ItemStack> rewardItems = new ArrayList<>();
                    List<?> itemsList = rewardSection.getList(rankKey + ".items");
                    if (itemsList != null) {
                        for (Object obj : itemsList) {
                            if (obj instanceof Map) {
                                Map<?, ?> itemMap = (Map<?, ?>) obj;
                                ItemStack stack = deserializeRewardItem(itemMap);
                                if (stack != null) {
                                    rewardItems.add(stack);
                                }
                            }
                        }
                    }

                    if (!rewardItems.isEmpty()) {
                        for (ItemStack reward : rewardItems) {
                            id.cyayo.guildwar.listener.GUIListener.depositItemToVault(entry.guildId, reward);
                        }
                    }

                    // Execute commands
                    List<String> commands = rewardSection.getStringList(rankKey + ".commands");
                    for (String cmd : commands) {
                        String formattedCmd = cmd.replace("{guild}", entry.guildName)
                                                 .replace("{points}", String.valueOf(entry.srPoints));
                        org.bukkit.Bukkit.dispatchCommand(org.bukkit.Bukkit.getConsoleSender(), formattedCmd);
                    }

                    // Broadcast notification to guild members online
                    String prefix = plugin.getConfig().getString("messages.prefix");
                    String msg = plugin.color(prefix + "&a&lMusim berakhir! &eGuild kamu berhasil meraih Rank " + rank + " dengan " + entry.srPoints + " SR. Hadiah telah ditransfer ke War Vault!");
                    for (Player p : id.cyayo.guildwar.hook.GuildsHook.getOnlineMembers(entry.guildId)) {
                        p.sendMessage(msg);
                    }
                }
            }
        }

        // Reset SR in database
        plugin.getDataManager().resetAllSR();

        // Reset all territory ownerships
        plugin.getDataManager().clearAllOwnerships();

        // Increment current season number
        this.currentSeason++;

        // Set peace period end timestamp
        String peaceDurStr = cfg.getString("peace-duration", "1d");
        long peaceDurMillis = parseDuration(peaceDurStr);
        this.peacePeriodEnd = System.currentTimeMillis() + peaceDurMillis;

        // Save new state
        saveSeasonData();

        // Broadcast to whole server
        String prefix = plugin.getConfig().getString("messages.prefix");
        org.bukkit.Bukkit.broadcastMessage(plugin.color(prefix + "&e&lSeason " + (currentSeason - 1) + " selesai! Season " + currentSeason + " resmi dimulai dengan Masa Damai selama " + peaceDurStr + "!"));

        // Reschedule next automatic reset
        reloadSeasonTimer();
    }

    private ItemStack deserializeRewardItem(Map<?, ?> map) {
        try {
            // MMOItems
            if (map.containsKey("mmoitems") && org.bukkit.Bukkit.getPluginManager().isPluginEnabled("MMOItems")) {
                Map<?, ?> mmoMap = (Map<?, ?>) map.get("mmoitems");
                String mmoType = String.valueOf(mmoMap.get("type"));
                String mmoId = String.valueOf(mmoMap.get("id"));
                int amount = mmoMap.containsKey("amount") ? ((Number) mmoMap.get("amount")).intValue() : 1;
                
                Class<?> mmoItemsClass = Class.forName("net.Indyuce.mmoitems.MMOItems");
                Object mmoItemsPlugin = mmoItemsClass.getField("plugin").get(null);
                
                Class<?> typeClass = Class.forName("net.Indyuce.mmoitems.api.Type");
                Object type = typeClass.getMethod("get", String.class).invoke(null, mmoType);
                if (type == null) {
                    type = typeClass.getMethod("get", String.class).invoke(null, mmoType.toUpperCase());
                }
                
                if (type != null) {
                    java.lang.reflect.Method getItemMethod = mmoItemsPlugin.getClass().getMethod("getItem", typeClass, String.class);
                    ItemStack item = (ItemStack) getItemMethod.invoke(mmoItemsPlugin, type, mmoId);
                    if (item != null) {
                        item.setAmount(amount);
                        return item;
                    }
                }
            }
            
            // Vanilla
            if (map.containsKey("material")) {
                String matStr = String.valueOf(map.get("material"));
                int amount = map.containsKey("amount") ? ((Number) map.get("amount")).intValue() : 1;
                Material mat = Material.matchMaterial(matStr);
                if (mat != null) {
                    ItemStack item = new ItemStack(mat, amount);
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        if (map.containsKey("custom-name")) {
                            meta.setDisplayName(plugin.color(String.valueOf(map.get("custom-name"))));
                        }
                        if (map.containsKey("custom-lore")) {
                            List<?> rawLore = (List<?>) map.get("custom-lore");
                            List<String> coloredLore = new ArrayList<>();
                            for (Object line : rawLore) {
                                coloredLore.add(plugin.color(String.valueOf(line)));
                            }
                            meta.setLore(coloredLore);
                        }
                        item.setItemMeta(meta);
                    }
                    return item;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Season] Gagal deserialize item reward: " + e.getMessage());
        }
        return null;
    }
}
