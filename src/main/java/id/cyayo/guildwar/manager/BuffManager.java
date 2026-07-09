package id.cyayo.guildwar.manager;

import id.cyayo.guildwar.CyayoGuildWar;
import id.cyayo.guildwar.config.ConfigManager;
import id.cyayo.guildwar.data.DataManager;
import id.cyayo.guildwar.hook.GuildsHook;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class BuffManager implements Listener {

    private final CyayoGuildWar plugin;
    private final List<Integer> periodicTaskIds = new ArrayList<>();
    private final Map<UUID, List<String>> pendingNotifications = new HashMap<>();
    private final Map<UUID, org.bukkit.scheduler.BukkitTask> notificationTasks = new HashMap<>();
    private int potionEffectTaskId = -1;

    public BuffManager(CyayoGuildWar plugin) {
        this.plugin = plugin;
        startPotionEffectTask();
        startPeriodicRewards();
    }

    // Antrian player online untuk diproses 1 player per tick
    private final java.util.Queue<UUID> playerQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();

    private void startPotionEffectTask() {
        // Task 1: Daftarkan/sinkronisasi semua player online ke antrian setiap 20 tick (1 detik)
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                UUID uuid = p.getUniqueId();
                if (!playerQueue.contains(uuid)) {
                    playerQueue.add(uuid);
                }
            }
            // Bersihkan player offline dari antrian
            playerQueue.removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
        }, 100L, 20L);

        // Task 2: Proses tepat 1 player dari antrian setiap 2 tick (sangat ringan!)
        potionEffectTaskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (playerQueue.isEmpty()) return;

            // Ambil player terdepan di antrian
            UUID targetUuid = playerQueue.poll();
            if (targetUuid == null) return;

            Player player = Bukkit.getPlayer(targetUuid);
            if (player == null || !player.isOnline()) return;

            // Masukkan kembali ke antrian belakang untuk putaran berikutnya
            playerQueue.add(targetUuid);

            // Proses logika teritori & potion untuk player tunggal ini
            UUID guildId = GuildsHook.getGuildId(player);
            if (guildId == null) return;

            ConfigManager.TerritoryInfo territory = plugin.getConfigManager().getCachedTerritory(player.getUniqueId());
            if (territory == null) return;

            DataManager.OwnershipInfo ownership = plugin.getDataManager().getOwnership(territory.regionId);
            if (guildId.equals(ownership.ownerGuildId)) {
                // Terapkan efek ramuan
                for (String eff : territory.potionEffects) {
                    String[] split = eff.split(":");
                    if (split.length == 2) {
                        try {
                            PotionEffectType type = PotionEffectType.getByName(split[0]);
                            int amp = Integer.parseInt(split[1]);
                            if (type != null) {
                                // Cek apakah player sudah memiliki efek tersebut
                                if (player.hasPotionEffect(type)) {
                                    PotionEffect active = player.getPotionEffect(type);
                                    if (active != null && active.getAmplifier() > amp) {
                                        // Jika efek yang ada lebih kuat (misal Strength II vs Strength I teritori), lewati
                                        continue;
                                    }
                                }
                                // Durasi diset 160 ticks (8 detik) agar efek ramuan tidak berkedip
                                player.addPotionEffect(new PotionEffect(type, 160, amp, true, false, true));
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        }, 100L, 2L).getTaskId(); // Berjalan setiap 2 ticks (10 player per detik)
    }

    // Antrian hadiah periodik yang tertunda untuk didistribusikan secara bertahap
    private static class PendingReward {
        final Player player;
        final ConfigManager.PeriodicItem item;
        final ConfigManager.PeriodicCommand command;

        PendingReward(Player player, ConfigManager.PeriodicItem item, ConfigManager.PeriodicCommand command) {
            this.player = player;
            this.item = item;
            this.command = command;
        }
    }

    private final java.util.Queue<PendingReward> pendingRewardsQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private int distributionTaskId = -1;

    public void stopTasks() {
        if (potionEffectTaskId != -1) {
            Bukkit.getScheduler().cancelTask(potionEffectTaskId);
        }
        if (distributionTaskId != -1) {
            Bukkit.getScheduler().cancelTask(distributionTaskId);
        }
        for (int id : periodicTaskIds) {
            Bukkit.getScheduler().cancelTask(id);
        }
        periodicTaskIds.clear();
    }

    private void startPeriodicRewards() {
        // Task: Jalankan pendistribusian hadiah bertahap (1 hadiah setiap 5 tick)
        distributionTaskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (pendingRewardsQueue.isEmpty()) return;

            PendingReward reward = pendingRewardsQueue.poll();
            if (reward == null || reward.player == null || !reward.player.isOnline()) return;

            // Proses item jika ada
            if (reward.item != null) {
                double chance = ThreadLocalRandom.current().nextDouble(100.0);
                if (chance <= reward.item.chance) {
                    giveItem(reward.player, reward.item);
                }
            }
            // Proses command jika ada
            if (reward.command != null) {
                double chance = ThreadLocalRandom.current().nextDouble(100.0);
                if (chance <= reward.command.chance) {
                    String ranCmd = reward.command.command.replace("{player}", reward.player.getName());
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), ranCmd);
                }
            }
        }, 100L, 2L).getTaskId();

        Map<String, ConfigManager.TerritoryInfo> territories = plugin.getConfigManager().getTerritories();
        
        // Schedule periodic items
        for (ConfigManager.TerritoryInfo t : territories.values()) {
            List<ConfigManager.PeriodicItem> items = t.periodicItems;
            if (items.isEmpty()) continue;
            
            String regionId = t.regionId;
            String displayName = t.displayName;
            for (ConfigManager.PeriodicItem item : items) {
                int intervalMins = item.intervalMinutes;
                long ticks = intervalMins * 60L * 20L;
                
                int taskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                    DataManager.OwnershipInfo ownership = plugin.getDataManager().getOwnership(regionId);
                    if (ownership.ownerGuildId == null) {
                        plugin.getLogger().info("[BuffManager] Teritori " + displayName + " tidak memiliki pemilik. Melewati pembagian hadiah.");
                        return;
                    }

                    double chance = ThreadLocalRandom.current().nextDouble(100.0);
                    plugin.getLogger().info("[BuffManager] Memulai roll hadiah teritori " + displayName + " untuk Guild: " + ownership.ownerGuildName + " (Peluang: " + item.chance + "%, Roll: " + String.format("%.1f", chance) + "%)");
                    if (chance <= item.chance) {
                        depositItemToGuildVault(ownership.ownerGuildId, item, displayName);
                    }
                }, ticks, ticks).getTaskId();
                
                periodicTaskIds.add(taskId);
            }
        }

        // Schedule periodic commands
        for (ConfigManager.TerritoryInfo t : territories.values()) {
            List<ConfigManager.PeriodicCommand> cmds = t.periodicCommands;
            if (cmds.isEmpty()) continue;
            
            String regionId = t.regionId;
            for (ConfigManager.PeriodicCommand cmd : cmds) {
                int intervalMins = cmd.intervalMinutes;
                long ticks = intervalMins * 60L * 20L;
                
                int taskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                    DataManager.OwnershipInfo ownership = plugin.getDataManager().getOwnership(regionId);
                    if (ownership.ownerGuildId == null) return;

                    List<Player> members = GuildsHook.getOnlineMembers(ownership.ownerGuildId);
                    for (Player p : members) {
                        // Masukkan ke antrian distribusi
                        pendingRewardsQueue.add(new PendingReward(p, null, cmd));
                    }
                }, ticks, ticks).getTaskId();
                
                periodicTaskIds.add(taskId);
            }
        }
    }

    private void depositItemToGuildVault(UUID guildId, ConfigManager.PeriodicItem pi, String territoryDisplayName) {
        ItemStack resultItem = null;

        if (pi.mmoType != null && pi.mmoId != null && Bukkit.getPluginManager().isPluginEnabled("MMOItems")) {
            try {
                Class<?> mmoItemsClass = Class.forName("net.Indyuce.mmoitems.MMOItems");
                Object mmoItemsPlugin = mmoItemsClass.getField("plugin").get(null);
                
                Class<?> typeClass = Class.forName("net.Indyuce.mmoitems.api.Type");
                Object type = typeClass.getMethod("get", String.class).invoke(null, pi.mmoType);
                if (type == null) {
                    type = typeClass.getMethod("get", String.class).invoke(null, pi.mmoType.toUpperCase());
                }
                
                if (type != null) {
                    java.lang.reflect.Method getItemMethod = mmoItemsPlugin.getClass().getMethod("getItem", typeClass, String.class);
                    ItemStack item = (ItemStack) getItemMethod.invoke(mmoItemsPlugin, type, pi.mmoId);
                    if (item != null) {
                        item.setAmount(pi.amount);
                        resultItem = item;
                    }
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("Gagal men-generate MMOItem " + pi.mmoType + ":" + pi.mmoId + " untuk vault guild: " + t.getMessage());
            }
        }

        // Vanilla Item Fallback
        if (resultItem == null && pi.material != null) {
            try {
                Material mat = Material.matchMaterial(pi.material);
                if (mat != null) {
                    ItemStack item = new ItemStack(mat, pi.amount);
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        if (pi.customName != null && !pi.customName.isEmpty()) {
                            meta.setDisplayName(plugin.color(pi.customName));
                        }
                        if (pi.customLore != null && !pi.customLore.isEmpty()) {
                            List<String> coloredLore = new ArrayList<>();
                            for (String line : pi.customLore) {
                                coloredLore.add(plugin.color(line));
                            }
                            meta.setLore(coloredLore);
                        }
                        item.setItemMeta(meta);
                    }
                    resultItem = item;
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Gagal men-generate item " + pi.material + " untuk vault: " + e.getMessage());
            }
        }

        if (resultItem == null) return;

        // Deposit the item into the vault safely (handles open vault GUI to prevent overwrite race conditions)
        id.cyayo.guildwar.listener.GUIListener.depositItemToVault(guildId, resultItem);

        String itemDisplayName = getItemName(resultItem);

        // Queue the notification to consolidate messages and prevent chat spam
        queueNotification(guildId, territoryDisplayName, itemDisplayName, pi.amount);
    }

    private void queueNotification(UUID guildId, String territoryDisplayName, String itemDisplayName, int amount) {
        if (!plugin.getConfig().getBoolean("messages.notify-periodic-vault-deposit", true)) {
            return;
        }

        List<String> list = pendingNotifications.computeIfAbsent(guildId, k -> new ArrayList<>());
        list.add("&e" + amount + "x " + itemDisplayName + " &7(" + territoryDisplayName + "&7)");

        if (notificationTasks.containsKey(guildId)) {
            return;
        }

        org.bukkit.scheduler.BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            notificationTasks.remove(guildId);
            List<String> items = pendingNotifications.remove(guildId);
            if (items == null || items.isEmpty()) return;

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < items.size(); i++) {
                sb.append(items.get(i));
                if (i < items.size() - 1) {
                    sb.append("&a, ");
                }
            }

            String format = plugin.getConfig().getString("messages.periodic-reward-vault-deposited-consolidated", 
                "&aGuild kamu menerima bonus teritori: {items}&a! Item telah dimasukkan ke War Vault.");
            String finalMsg = format.replace("{items}", sb.toString());

            String prefix = plugin.getConfig().getString("messages.prefix", "");
            for (Player p : GuildsHook.getOnlineMembers(guildId)) {
                p.sendMessage(plugin.color(prefix + finalMsg));
            }

            // Group all items into a single clean text log entry
            StringBuilder logSb = new StringBuilder();
            for (int i = 0; i < items.size(); i++) {
                String cleanItem = org.bukkit.ChatColor.stripColor(plugin.color(items.get(i)));
                logSb.append(cleanItem);
                if (i < items.size() - 1) {
                    logSb.append(", ");
                }
            }
            plugin.getDataManager().addGuildLog(guildId, "periodic-rewards;" + logSb.toString());
        }, 5L);
        notificationTasks.put(guildId, task);
    }

    private String getItemName(ItemStack item) {
        if (item == null) return "None";
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return item.getItemMeta().getDisplayName();
        }
        String name = item.getType().name().replace('_', ' ').toLowerCase();
        StringBuilder sb = new StringBuilder();
        for (String word : name.split(" ")) {
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(" ");
            }
        }
        return sb.toString().trim();
    }

    private void giveItem(Player player, ConfigManager.PeriodicItem pi) {
        // MMOItems support
        if (pi.mmoType != null && pi.mmoId != null && Bukkit.getPluginManager().isPluginEnabled("MMOItems")) {
            try {
                Class<?> mmoItemsClass = Class.forName("net.Indyuce.mmoitems.MMOItems");
                Object mmoItemsPlugin = mmoItemsClass.getField("plugin").get(null);
                
                Class<?> typeClass = Class.forName("net.Indyuce.mmoitems.api.Type");
                Object type = typeClass.getMethod("get", String.class).invoke(null, pi.mmoType);
                if (type == null) {
                    type = typeClass.getMethod("get", String.class).invoke(null, pi.mmoType.toUpperCase());
                }
                
                if (type != null) {
                    java.lang.reflect.Method getItemMethod = mmoItemsPlugin.getClass().getMethod("getItem", typeClass, String.class);
                    ItemStack item = (ItemStack) getItemMethod.invoke(mmoItemsPlugin, type, pi.mmoId);
                    if (item != null) {
                        item.setAmount(pi.amount);
                        
                        // Menangani inventori penuh agar item tidak hilang
                        java.util.Map<Integer, ItemStack> leftOver = player.getInventory().addItem(item);
                        if (!leftOver.isEmpty()) {
                            for (ItemStack left : leftOver.values()) {
                                player.getWorld().dropItemNaturally(player.getLocation(), left);
                            }
                            player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + "&cPersediaan penuh! Item reward dari wilayah dijatuhkan ke tanah."));
                        }
                        
                        String displayName = (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) ? item.getItemMeta().getDisplayName() : pi.mmoId;
                        String msg = plugin.getConfig().getString("messages.periodic-reward-received", "&aKamu menerima &e{amount}x {item} &adari wilayah kekuasaan Guild!")
                                .replace("{amount}", String.valueOf(pi.amount))
                                .replace("{item}", displayName);
                        player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + msg));
                        return;
                    }
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("Gagal memberikan MMOItem " + pi.mmoType + ":" + pi.mmoId + " ke player " + player.getName() + " - " + t.getMessage());
            }
        }

        // Vanilla Item Fallback
        if (pi.material != null) {
            try {
                Material mat = Material.matchMaterial(pi.material);
                if (mat != null) {
                    ItemStack item = new ItemStack(mat, pi.amount);
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        if (pi.customName != null && !pi.customName.isEmpty()) {
                            meta.setDisplayName(plugin.color(pi.customName));
                        }
                        if (pi.customLore != null && !pi.customLore.isEmpty()) {
                            List<String> coloredLore = new ArrayList<>();
                            for (String line : pi.customLore) {
                                coloredLore.add(plugin.color(line));
                            }
                            meta.setLore(coloredLore);
                        }
                        item.setItemMeta(meta);
                    }
                    
                    // Menangani inventori penuh agar item tidak hilang
                    java.util.Map<Integer, ItemStack> leftOver = player.getInventory().addItem(item);
                    if (!leftOver.isEmpty()) {
                        for (ItemStack left : leftOver.values()) {
                            player.getWorld().dropItemNaturally(player.getLocation(), left);
                        }
                        player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + "&cPersediaan penuh! Item reward dari wilayah dijatuhkan ke tanah."));
                    }
                    
                    String displayName = (pi.customName != null && !pi.customName.isEmpty()) ? pi.customName : mat.name();
                    String msg = plugin.getConfig().getString("messages.periodic-reward-received", "&aKamu menerima &e{amount}x {item} &adari wilayah kekuasaan Guild!")
                                .replace("{amount}", String.valueOf(pi.amount))
                                .replace("{item}", displayName);
                    player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + msg));
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Gagal memberikan item " + pi.material + " - " + e.getMessage());
            }
        }
    }

    // AuraSkills XP Modifier hook
    @EventHandler
    public void onAuraSkillsXpGain(dev.aurelium.auraskills.api.event.skill.XpGainEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        UUID guildId = GuildsHook.getGuildId(player);
        if (guildId == null) return;

        ConfigManager.TerritoryInfo territory = plugin.getConfigManager().getCachedTerritory(player.getUniqueId());
        if (territory == null) return;

        DataManager.OwnershipInfo ownership = plugin.getDataManager().getOwnership(territory.regionId);
        if (guildId.equals(ownership.ownerGuildId)) {
            // Check if this skill has a multiplier
            String skillName = event.getSkill().getId().getKey().toLowerCase();
            if (territory.auraskillsXp.containsKey(skillName)) {
                double multiplier = territory.auraskillsXp.get(skillName);
                event.setAmount(event.getAmount() * multiplier);
            }
        }
    }

    public void triggerPeriodicItemsRoll(String targetRegionId) {
        ConfigManager.TerritoryInfo t = plugin.getConfigManager().getTerritories().get(targetRegionId.toLowerCase());
        if (t == null) {
            plugin.getLogger().warning("[BuffManager] Teritori " + targetRegionId + " tidak terdaftar di konfigurasi.");
            return;
        }

        List<ConfigManager.PeriodicItem> items = t.periodicItems;
        if (items.isEmpty()) {
            plugin.getLogger().info("[BuffManager] Teritori " + t.displayName + " tidak memiliki periodic-items.");
            return;
        }
        
        String regionId = t.regionId;
        String displayName = t.displayName;
        for (ConfigManager.PeriodicItem item : items) {
            DataManager.OwnershipInfo ownership = plugin.getDataManager().getOwnership(regionId);
            if (ownership.ownerGuildId == null) {
                plugin.getLogger().info("[BuffManager] Teritori " + displayName + " tidak memiliki pemilik. Melewati pembagian hadiah.");
                continue;
            }

            double chance = ThreadLocalRandom.current().nextDouble(100.0);
            plugin.getLogger().info("[BuffManager] [Test] Memulai roll hadiah teritori " + displayName + " untuk Guild: " + ownership.ownerGuildName + " (Peluang: " + item.chance + "%, Roll: " + String.format("%.1f", chance) + "%)");
            if (chance <= item.chance) {
                depositItemToGuildVault(ownership.ownerGuildId, item, displayName);
            }
        }
    }
}
