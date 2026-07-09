package id.cyayo.guildwar.command;

import id.cyayo.guildwar.CyayoGuildWar;
import id.cyayo.guildwar.config.ConfigManager;
import id.cyayo.guildwar.data.DataManager;
import id.cyayo.guildwar.hook.GuildsHook;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class WarCommand implements CommandExecutor, TabCompleter {

    private final CyayoGuildWar plugin;

    public WarCommand(CyayoGuildWar plugin) {
        this.plugin = plugin;
    }

    /**
     * Terapkan filler ke inventory berdasarkan konfigurasi per-GUI.
     * @param gui       Inventory yang akan diisi
     * @param cfg       Objek configuration yang sudah mengarah ke section GUI terkait
     */
    private void applyFiller(Inventory gui, org.bukkit.configuration.file.FileConfiguration cfg) {
        if (cfg == null) return;

        String mode = cfg.getString("filler.mode", "none").toLowerCase();
        if (mode.equals("none")) return;

        // Buat item filler
        String matStr = cfg.getString("filler.material", "GRAY_STAINED_GLASS_PANE");
        Material mat = Material.matchMaterial(matStr);
        if (mat == null) mat = Material.GRAY_STAINED_GLASS_PANE;
        ItemStack pane = new ItemStack(mat);
        ItemMeta paneMeta = pane.getItemMeta();
        if (paneMeta != null) {
            paneMeta.setDisplayName(plugin.color(cfg.getString("filler.name", " ")));
            pane.setItemMeta(paneMeta);
        }

        int size = gui.getSize();
        int rows = size / 9;

        java.util.Set<Integer> targetSlots = new java.util.HashSet<>();

        switch (mode) {
            case "all" -> {
                for (int i = 0; i < size; i++) targetSlots.add(i);
            }
            case "border" -> {
                // Baris atas & bawah
                for (int i = 0; i < 9; i++) targetSlots.add(i);
                for (int i = size - 9; i < size; i++) targetSlots.add(i);
                // Kolom kiri & kanan (baris tengah)
                for (int row = 1; row < rows - 1; row++) {
                    targetSlots.add(row * 9);
                    targetSlots.add(row * 9 + 8);
                }
            }
            case "custom" -> {
                for (int slot : cfg.getIntegerList("filler.slots")) {
                    if (slot >= 0 && slot < size) targetSlots.add(slot);
                }
            }
            default -> {} // none atau tidak dikenal
        }

        for (int slot : targetSlots) {
            // Hanya isi slot yang kosong (tidak menimpa item yang sudah ada)
            if (gui.getItem(slot) == null) {
                gui.setItem(slot, pane);
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Hanya pemain yang dapat menggunakan perintah ini.");
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("vault")) {
            if (!player.hasPermission("cyayoguildwar.use")) {
                player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + plugin.getConfig().getString("messages.no-permission")));
                return true;
            }

            UUID targetGuildId = GuildsHook.getGuildId(player);
            if (targetGuildId == null) {
                player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + plugin.getConfig().getString("messages.not-in-guild")));
                return true;
            }

            String roleName = GuildsHook.getGuildRoleName(player);
            List<String> allowedRanks = plugin.getConfigManager().getVaultConfig().getStringList("permissions.open");
            boolean hasRank = false;
            for (String r : allowedRanks) {
                if (r.equalsIgnoreCase(roleName)) {
                    hasRank = true;
                    break;
                }
            }
            if (!hasRank) {
                player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + 
                        "&cRole guild kamu tidak diperbolehkan membuka War Vault!"));
                return true;
            }

            id.cyayo.guildwar.listener.GUIListener.openVaultGUI(player, targetGuildId, 0);
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("info")) {
            if (!player.hasPermission("cyayoguildwar.use")) {
                player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + plugin.getConfig().getString("messages.no-permission")));
                return true;
            }

            ConfigManager.TerritoryInfo territory = plugin.getConfigManager().getTerritoryByLocation(player.getLocation());
            if (territory == null) {
                player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + plugin.getConfig().getString("messages.no-region")));
                return true;
            }

            openTerritoryGUI(player, territory);
            return true;
        }

        if (args[0].equalsIgnoreCase("banner")) {
            if (!player.hasPermission("cyayoguildwar.use")) {
                player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + plugin.getConfig().getString("messages.no-permission")));
                return true;
            }

            UUID guildId = GuildsHook.getGuildId(player);
            if (guildId == null) {
                player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + plugin.getConfig().getString("messages.not-in-guild")));
                return true;
            }

            // Check rank requirements for modifying banner
            String roleName = GuildsHook.getGuildRoleName(player);
            List<String> allowedRanks = plugin.getConfig().getStringList("guild-rank-requirements.modify-banner");
            boolean hasRank = false;
            for (String r : allowedRanks) {
                if (r.equalsIgnoreCase(roleName)) {
                    hasRank = true;
                    break;
                }
            }
            if (!hasRank) {
                player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + 
                        plugin.getConfig().getString("messages.no-guild-rank").replace("{ranks}", String.join(", ", allowedRanks))));
                return true;
            }

            ItemStack handItem = player.getInventory().getItemInMainHand();
            if (handItem == null || !handItem.getType().name().contains("BANNER")) {
                player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + plugin.getConfig().getString("messages.not-holding-banner")));
                return true;
            }

            org.bukkit.inventory.meta.BannerMeta meta = (org.bukkit.inventory.meta.BannerMeta) handItem.getItemMeta();
            if (meta == null) {
                player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + plugin.getConfig().getString("messages.banner-invalid-meta")));
                return true;
            }

            org.bukkit.DyeColor baseColor = null;
            String matName = handItem.getType().name();
            for (org.bukkit.DyeColor color : org.bukkit.DyeColor.values()) {
                if (matName.startsWith(color.name() + "_")) {
                    baseColor = color;
                    break;
                }
            }
            if (baseColor == null) baseColor = org.bukkit.DyeColor.WHITE;

            // 1. Simpan banner guild secara global
            plugin.getDataManager().saveGuildBanner(guildId, baseColor, meta.getPatterns());
            
            // 2. Update blok banner fisik di seluruh wilayah yang saat ini dikuasai oleh guild ini (beri delay 2 tick agar DB tersimpan)
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                plugin.getWarManager().updateAllGuildBanners(guildId);
            }, 2L);

            player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + "&aDesain Banner Guild kamu berhasil disimpan secara global!"));
            return true;
        }


        if (args[0].equalsIgnoreCase("stats")) {
            openStatsGUI(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("attack")) {
            ConfigManager.TerritoryInfo territory = plugin.getConfigManager().getTerritoryByLocation(player.getLocation());
            if (territory == null) {
                player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + plugin.getConfig().getString("messages.no-region")));
                return true;
            }
            plugin.getWarManager().startWar(player, territory);
            return true;
        }

        if (args[0].equalsIgnoreCase("topterr")) {
            java.util.Map<String, Integer> counts = new java.util.HashMap<>();
            for (ConfigManager.TerritoryInfo t : plugin.getConfigManager().getTerritories().values()) {
                DataManager.OwnershipInfo ownership = plugin.getDataManager().getOwnership(t.regionId);
                if (ownership.ownerGuildId != null && ownership.ownerGuildName != null) {
                    String cleanName = org.bukkit.ChatColor.stripColor(plugin.color(ownership.ownerGuildName));
                    counts.put(cleanName, counts.getOrDefault(cleanName, 0) + 1);
                }
            }
            List<java.util.Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
            sorted.sort((e1, e2) -> e2.getValue().compareTo(e1.getValue()));
            
            List<String> layout = plugin.getConfig().getStringList("messages.topterr-layout");
            String entryFormat = plugin.getConfig().getString("messages.topterr-entry-format", " &e{rank}. &b{guild} &8» &a{count} Wilayah");
            String emptyMsg = plugin.getConfig().getString("messages.topterr-empty", " &8- &7Belum ada wilayah yang dikuasai");

            for (String line : layout) {
                if (line.contains("{entries}")) {
                    if (sorted.isEmpty()) {
                        player.sendMessage(plugin.color(emptyMsg));
                    } else {
                        for (int i = 0; i < Math.min(sorted.size(), 10); i++) {
                            var entry = sorted.get(i);
                            player.sendMessage(plugin.color(entryFormat
                                    .replace("{rank}", String.valueOf(i + 1))
                                    .replace("{guild}", entry.getKey())
                                    .replace("{count}", String.valueOf(entry.getValue()))));
                        }
                    }
                } else {
                    player.sendMessage(plugin.color(line));
                }
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("topsr")) {
            java.util.Map<UUID, Integer> srMap = plugin.getDataManager().getAllGuildSR();
            List<java.util.Map.Entry<UUID, Integer>> sorted = new ArrayList<>(srMap.entrySet());
            sorted.sort((e1, e2) -> e2.getValue().compareTo(e1.getValue()));
            
            List<String> layout = plugin.getConfig().getStringList("messages.topsr-layout");
            if (layout == null || layout.isEmpty()) {
                layout = Arrays.asList(
                    "&8&m========================================",
                    "            &6&lTOP SEASON RATING GUILD",
                    "{entries}",
                    "&8&m========================================"
                );
            }
            String entryFormat = plugin.getConfig().getString("messages.topsr-entry-format", " &e{rank}. &b{guild} &8» &e{points} SR");
            String emptyMsg = plugin.getConfig().getString("messages.topsr-empty", " &8- &7Belum ada rating SR terdaftar");

            for (String line : layout) {
                if (line.contains("{entries}")) {
                    if (sorted.isEmpty()) {
                        player.sendMessage(plugin.color(emptyMsg));
                    } else {
                        for (int i = 0; i < Math.min(sorted.size(), 10); i++) {
                            var entry = sorted.get(i);
                            String gName = plugin.getDataManager().getGuildNameFromCache(entry.getKey());
                            String cleanName = org.bukkit.ChatColor.stripColor(plugin.color(gName));
                            player.sendMessage(plugin.color(entryFormat
                                    .replace("{rank}", String.valueOf(i + 1))
                                    .replace("{guild}", cleanName)
                                    .replace("{points}", String.valueOf(entry.getValue()))));
                        }
                    }
                } else {
                    player.sendMessage(plugin.color(line));
                }
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("allterr")) {
            List<String> layout = plugin.getConfig().getStringList("messages.allterr-layout");
            String entryFormat = plugin.getConfig().getString("messages.allterr-entry-format", " &e• &f{territory} &8» {owner}");
            String unclaimedStr = plugin.getConfig().getString("messages.allterr-unclaimed", "Belum dikuasai");

            for (String line : layout) {
                if (line.contains("{entries}")) {
                    for (ConfigManager.TerritoryInfo t : plugin.getConfigManager().getTerritories().values()) {
                        DataManager.OwnershipInfo ownership = plugin.getDataManager().getOwnership(t.regionId);
                        String ownerStr = ownership.ownerGuildId != null ? "&b" + ownership.ownerGuildName 
                                : plugin.color(unclaimedStr);
                        player.sendMessage(plugin.color(entryFormat
                                .replace("{territory}", t.displayName)
                                .replace("{owner}", ownerStr)));
                    }
                } else {
                    player.sendMessage(plugin.color(line));
                }
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("spectate")) {
            if (args.length < 2) {
                player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + plugin.getConfig().getString("messages.usage-spectate")));
                return true;
            }
            
            String action = args[1].toLowerCase();
            if (action.equals("quit") || action.equals("leave")) {
                UUID uuid = player.getUniqueId();
                id.cyayo.guildwar.manager.WarManager.WarSession session = plugin.getWarManager().getPlayerSession(uuid);
                if (session != null && session.spectators.contains(uuid)) {
                    session.spectators.remove(uuid);
                    plugin.getWarManager().restorePlayerState(player, session.regionId);
                    player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + plugin.getConfig().getString("messages.spectator-quit")));
                    return true;
                }
                player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + plugin.getConfig().getString("messages.spectator-not-in-war")));
                return true;
            }

            String regionId = action;
            id.cyayo.guildwar.manager.WarManager.WarSession session = plugin.getWarManager().getActiveSessions().get(regionId);
            if (session == null) {
                // Look up by clean display name without spaces and color codes
                for (id.cyayo.guildwar.manager.WarManager.WarSession s : plugin.getWarManager().getActiveSessions().values()) {
                    if (s.status == id.cyayo.guildwar.manager.WarManager.WarStatus.ACTIVE) {
                        ConfigManager.TerritoryInfo terr = plugin.getConfigManager().getTerritories().get(s.regionId.toLowerCase());
                        String name = (terr != null) ? terr.displayName : s.regionId;
                        String cleanName = org.bukkit.ChatColor.stripColor(plugin.color(name)).replace(" ", "").toLowerCase();
                        if (cleanName.equals(regionId)) {
                            session = s;
                            break;
                        }
                    }
                }
            }
            if (session == null || session.status != id.cyayo.guildwar.manager.WarManager.WarStatus.ACTIVE) {
                player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + plugin.getConfig().getString("messages.no-active-war")));
                return true;
            }
            
            // Simpan state asal player sebelum masuk spectator mode agar bisa dipulihkan saat /war spectate quit
            plugin.getWarManager().saveSpectatorState(player, session.regionId);
            
            plugin.getWarManager().resetTabScoreboard(player);
            session.spectators.add(player.getUniqueId());
            player.teleport(session.arena.spawnDefender);
            player.setGameMode(org.bukkit.GameMode.SPECTATOR);
            player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + 
                    plugin.getConfig().getString("messages.spectator-joined").replace("{territory}", session.displayName)));
            return true;
        }

        if (args[0].equalsIgnoreCase("toggle")) {
            boolean current = plugin.getDataManager().getPlayerToggle(player.getUniqueId());
            boolean target = !current;
            plugin.getDataManager().setPlayerToggle(player.getUniqueId(), target);

            String prefix = plugin.getConfig().getString("messages.prefix");
            if (target) {
                player.sendMessage(plugin.color(prefix + plugin.getConfig().getString("messages.war-toggle-on")));
            } else {
                player.sendMessage(plugin.color(prefix + plugin.getConfig().getString("messages.war-toggle-off")));
            }
            return true;
        }

        return true;
    }

    private void openTerritoryGUI(Player player, ConfigManager.TerritoryInfo territory) {
        org.bukkit.configuration.file.FileConfiguration guiConfig = plugin.getConfigManager().getTerritoryInfoConfig();
        
        String guiTitle = plugin.color(guiConfig.getString("title", "&8Detail Wilayah: {territory}")
                .replace("{territory}", territory.displayName));
        int guiSize = guiConfig.getInt("size", 27);
        
        Inventory gui = Bukkit.createInventory(new GuildWarInfoHolder(), guiSize, guiTitle);

        // 1. Info Item
        String infoMatStr = guiConfig.getString("info-item.material", "MAP");
        int infoSlot = guiConfig.getInt("info-item.slot", 10);
        Material infoMat = Material.matchMaterial(infoMatStr);
        if (infoMat == null) infoMat = Material.MAP;
        
        ItemStack infoItem = new ItemStack(infoMat);
        ItemMeta infoMeta = infoItem.getItemMeta();
        if (infoMeta != null) {
            infoMeta.setDisplayName(plugin.color(guiConfig.getString("info-item.name", "&6&lInformasi Wilayah")));
            
            DataManager.OwnershipInfo ownership = plugin.getDataManager().getOwnership(territory.regionId);
            String ownerName = ownership.ownerGuildId != null ? ownership.ownerGuildName : guiConfig.getString("info-item.owner-none", "Belum ada pemilik");
            
            String statusStr = "";
            if (ownership.ownerGuildId != null) {
                if (ownership.cooldownUntil > System.currentTimeMillis()) {
                    long diff = (ownership.cooldownUntil - System.currentTimeMillis()) / 1000;
                    statusStr = guiConfig.getString("info-item.status-cooldown", "&cMasa Cooldown ({time})")
                            .replace("{time}", (diff / 60) + "m " + (diff % 60) + "s");
                } else {
                    statusStr = guiConfig.getString("info-item.status-active", "&aDapat Diserang");
                }
            } else {
                statusStr = guiConfig.getString("info-item.status-unclaimed", "&aDapat Diklaim Instan");
            }
            
            List<String> rawLore = guiConfig.getStringList("info-item.lore");
            List<String> infoLore = new ArrayList<>();
            for (String line : rawLore) {
                infoLore.add(plugin.color(line
                        .replace("{display_name}", territory.displayName)
                        .replace("{region_id}", territory.regionId)
                        .replace("{owner}", ownerName)
                        .replace("{status}", statusStr)));
            }
            infoMeta.setLore(infoLore);
            infoItem.setItemMeta(infoMeta);
        }
        gui.setItem(infoSlot, infoItem);

        // 2. Buffs Item — lore bebas dari territory config (buff-lore)
        String buffMatStr = guiConfig.getString("buffs-item.material", "BREWING_STAND");
        int buffSlot = guiConfig.getInt("buffs-item.slot", 13);
        Material buffMat = Material.matchMaterial(buffMatStr);
        if (buffMat == null) buffMat = Material.BREWING_STAND;

        ItemStack buffItem = new ItemStack(buffMat);
        ItemMeta buffMeta = buffItem.getItemMeta();
        if (buffMeta != null) {
            buffMeta.setDisplayName(plugin.color(guiConfig.getString("buffs-item.name", "&a&lEfek & Bonus Wilayah")));
            List<String> buffLore = new ArrayList<>();
            for (String line : guiConfig.getStringList("buffs-item.lore")) {
                if (line.contains("{buffs}")) {
                    if (!territory.buffLore.isEmpty()) {
                        for (String bLine : territory.buffLore) {
                            buffLore.add(plugin.color(bLine));
                        }
                    } else {
                        buffLore.add(plugin.color(guiConfig.getString("buffs-item.lore-empty", " &8- &7Tidak ada efek/buff aktif di teritori ini.")));
                    }
                } else {
                    buffLore.add(plugin.color(line));
                }
            }

            buffMeta.setLore(buffLore);
            buffItem.setItemMeta(buffMeta);
        }
        gui.setItem(buffSlot, buffItem);


        // 3. Attack/Claim/Owned Item
        int actionSlot = guiConfig.getInt("action-item.slot", 16);
        DataManager.OwnershipInfo ownership = plugin.getDataManager().getOwnership(territory.regionId);
        
        String modeKey = "claim";
        if (ownership.ownerGuildId != null) {
            if (ownership.ownerGuildId.equals(GuildsHook.getGuildId(player))) {
                modeKey = "owned";
            } else {
                modeKey = "attack";
            }
        }
        
        String actMatStr = guiConfig.getString("action-item." + modeKey + ".material", "RED_WOOL");
        Material actMat = Material.matchMaterial(actMatStr);
        if (actMat == null) actMat = Material.RED_WOOL;
        
        ItemStack actionItem = new ItemStack(actMat);
        ItemMeta actionMeta = actionItem.getItemMeta();
        if (actionMeta != null) {
            actionMeta.setDisplayName(plugin.color(guiConfig.getString("action-item." + modeKey + ".name", "")));
            List<String> rawActLore = guiConfig.getStringList("action-item." + modeKey + ".lore");
            List<String> actLore = new ArrayList<>();
            for (String line : rawActLore) {
                actLore.add(plugin.color(line.replace("{owner}", ownership.ownerGuildName != null ? ownership.ownerGuildName : "")));
            }
            actionMeta.setLore(actLore);
            actionItem.setItemMeta(actionMeta);
        }
        gui.setItem(actionSlot, actionItem);

        // 4. Filler
        applyFiller(gui, guiConfig);

        player.openInventory(gui);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> list = new ArrayList<>();
            String input = args[0].toLowerCase();
            if ("info".startsWith(input)) list.add("info");
            if ("stats".startsWith(input)) list.add("stats");
            if ("attack".startsWith(input)) list.add("attack");
            if ("topterr".startsWith(input)) list.add("topterr");
            if ("topsr".startsWith(input)) list.add("topsr");
            if ("allterr".startsWith(input)) list.add("allterr");
            if ("banner".startsWith(input)) list.add("banner");
            if ("spectate".startsWith(input)) list.add("spectate");
            if ("toggle".startsWith(input)) list.add("toggle");
            if ("vault".startsWith(input)) list.add("vault");
            return list;
        } else if (args.length == 2 && args[0].equalsIgnoreCase("spectate")) {
            List<String> list = new ArrayList<>();
            String input = args[1].toLowerCase();
            
            if ("quit".startsWith(input)) list.add("quit");
            if ("leave".startsWith(input)) list.add("leave");
            
            for (id.cyayo.guildwar.manager.WarManager.WarSession session : plugin.getWarManager().getActiveSessions().values()) {
                if (session.status == id.cyayo.guildwar.manager.WarManager.WarStatus.ACTIVE) {
                    ConfigManager.TerritoryInfo terr = plugin.getConfigManager().getTerritories().get(session.regionId.toLowerCase());
                    String name = (terr != null) ? terr.displayName : session.regionId;
                    String cleanName = org.bukkit.ChatColor.stripColor(plugin.color(name)).replace(" ", "");
                    if (cleanName.toLowerCase().startsWith(input)) {
                        list.add(cleanName);
                    }
                }
            }
            return list;
        }
        return new ArrayList<>();
    }

    public void openStatsGUI(Player player) {
        UUID guildId = GuildsHook.getGuildId(player);
        if (guildId == null) {
            player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + plugin.getConfig().getString("messages.not-in-guild")));
            return;
        }

        org.bukkit.configuration.file.FileConfiguration guiConfig = plugin.getConfigManager().getStatsConfig();
        String guildName = GuildsHook.getGuildName(player);
        
        String title = guiConfig.getString("title", "&8Statistik Guild: {guild}").replace("{guild}", guildName);
        int size = guiConfig.getInt("size", 45);
        Inventory gui = Bukkit.createInventory(new GuildStatsHolder(), size, plugin.color(title));

        DataManager.OwnershipInfo guildBanner = plugin.getDataManager().getGuildBanner(guildId);
        ItemStack bannerItem;
        if (guildBanner != null && guildBanner.baseColor != null) {
            String bannerMatName = guildBanner.baseColor.name() + "_BANNER";
            Material bannerMat = Material.matchMaterial(bannerMatName);
            if (bannerMat == null) bannerMat = Material.WHITE_BANNER;
            bannerItem = new ItemStack(bannerMat);
            org.bukkit.inventory.meta.BannerMeta bannerMeta = (org.bukkit.inventory.meta.BannerMeta) bannerItem.getItemMeta();
            if (bannerMeta != null) {
                bannerMeta.setPatterns(guildBanner.patterns);
                bannerItem.setItemMeta(bannerMeta);
            }
        } else {
            bannerItem = new ItemStack(Material.WHITE_BANNER);
        }
        
        ItemMeta bMeta = bannerItem.getItemMeta();
        if (bMeta != null) {
            bMeta.setDisplayName(plugin.color(guiConfig.getString("banner-item.name", "&6&lBanner Guild Kamu")));
            List<String> rawLore = guiConfig.getStringList("banner-item.lore");
            List<String> bLore = new ArrayList<>();
            for (String line : rawLore) {
                bLore.add(plugin.color(line));
            }
            bMeta.setLore(bLore);
            bannerItem.setItemMeta(bMeta);
        }
        
        int bannerSlot = guiConfig.getInt("banner-item.slot", 13);
        gui.setItem(bannerSlot, bannerItem);

        // ── Territory Summary Item (1 item dengan lore list semua territory) ──
        List<ConfigManager.TerritoryInfo> owned = new ArrayList<>();
        for (ConfigManager.TerritoryInfo t : plugin.getConfigManager().getTerritories().values()) {
            DataManager.OwnershipInfo ownership = plugin.getDataManager().getOwnership(t.regionId);
            if (guildId.equals(ownership.ownerGuildId)) {
                owned.add(t);
            }
        }

        String terrMatStr = guiConfig.getString("territory-item.material", "FILLED_MAP");
        Material terrMat = Material.matchMaterial(terrMatStr);
        if (terrMat == null) terrMat = Material.FILLED_MAP;
        ItemStack terrSummary = new ItemStack(terrMat);
        ItemMeta tsMeta = terrSummary.getItemMeta();
        if (tsMeta != null) {
            tsMeta.setDisplayName(plugin.color(guiConfig.getString("territory-item.name", "&a&lWilayah Dikuasai")));
            List<String> tsLore = new ArrayList<>();
            for (String line : guiConfig.getStringList("territory-item.lore")) {
                if (line.contains("{territories}")) {
                    if (owned.isEmpty()) {
                        tsLore.add(plugin.color(guiConfig.getString("territory-item.lore-empty", " &8- &cBelum menguasai wilayah apapun.")));
                    } else {
                        String entryFormat = guiConfig.getString("territory-item.lore-entry", " &8- &e{territory} &8| {status}");
                        for (ConfigManager.TerritoryInfo t : owned) {
                            DataManager.OwnershipInfo ownership = plugin.getDataManager().getOwnership(t.regionId);
                            long cooldownLeft = (ownership.cooldownUntil - System.currentTimeMillis()) / 1000;
                            String statusStr;
                            if (cooldownLeft > 0) {
                                long minutes = cooldownLeft / 60;
                                long seconds = cooldownLeft % 60;
                                statusStr = plugin.color(guiConfig.getString("territory-item.status-cooldown", "&cCooldown ({time})")
                                        .replace("{time}", minutes + "m " + seconds + "s"));
                            } else {
                                statusStr = plugin.color(guiConfig.getString("territory-item.status-ready", "&aSiap"));
                            }
                            tsLore.add(plugin.color(entryFormat
                                    .replace("{territory}", t.displayName)
                                    .replace("{status}", statusStr)));
                        }
                    }
                } else {
                    tsLore.add(plugin.color(line));
                }
            }

            tsMeta.setLore(tsLore);
            terrSummary.setItemMeta(tsMeta);
        }
        int terrSummarySlot = guiConfig.getInt("territory-item.slot", 22);
        gui.setItem(terrSummarySlot, terrSummary);

        // ── Logs Summary Item (slot 31) ──
        String logsMatStr = guiConfig.getString("logs-item.material", "WRITABLE_BOOK");
        Material logsMat = Material.matchMaterial(logsMatStr);
        if (logsMat == null) logsMat = Material.WRITABLE_BOOK;
        ItemStack logsItem = new ItemStack(logsMat);
        ItemMeta logsMeta = logsItem.getItemMeta();
        if (logsMeta != null) {
            logsMeta.setDisplayName(plugin.color(guiConfig.getString("logs-item.name", "&b&lCatatan Aktivitas Guild")));
            List<String> logsLore = new ArrayList<>();
            for (String line : guiConfig.getStringList("logs-item.lore")) {
                if (line.contains("{logs}")) {
                    List<String> logsList = plugin.getDataManager().getGuildLogs(guildId);
                    if (logsList.isEmpty()) {
                        logsLore.add(plugin.color(guiConfig.getString("logs-item.lore-empty", " &8- &cBelum ada catatan aktivitas.")));
                    } else {
                        String entryFormat = guiConfig.getString("logs-item.entry-format", "&8[{time}] {message}");
                        int showCount = Math.min(logsList.size(), 10);
                        for (int idx = 0; idx < showCount; idx++) {
                            String logEntry = logsList.get(idx);
                            String[] parts = logEntry.split("\\|", 2);
                            if (parts.length == 2) {
                                String time = formatTimestamp(guiConfig, parts[0]);
                                List<String> msgLines = plugin.getDataManager().formatLogMessageList(guiConfig, parts[1]);
                                for (int lineIdx = 0; lineIdx < msgLines.size(); lineIdx++) {
                                    if (lineIdx == 0) {
                                        logsLore.add(plugin.color(entryFormat
                                                .replace("{time}", time)
                                                .replace("{message}", msgLines.get(lineIdx))));
                                    } else {
                                        logsLore.add(plugin.color("           " + msgLines.get(lineIdx)));
                                    }
                                }
                            }
                        }
                    }
                } else {
                    logsLore.add(plugin.color(line));
                }
            }
            logsMeta.setLore(logsLore);
            logsItem.setItemMeta(logsMeta);
        }
        int logsSlot = guiConfig.getInt("logs-item.slot", 31);
        gui.setItem(logsSlot, logsItem);

        // Filler
        applyFiller(gui, guiConfig);

        player.openInventory(gui);
    }

    public void openTerritoryDetailGUI(Player player) {
        openTerritoryDetailGUI(player, 0);
    }

    public void openTerritoryDetailGUI(Player player, int page) {
        UUID guildId = GuildsHook.getGuildId(player);
        if (guildId == null) return;

        org.bukkit.configuration.file.FileConfiguration guiConfig = plugin.getConfigManager().getTerritoryDetailConfig();
        String guildName  = GuildsHook.getGuildName(player);
        String guildTier  = GuildsHook.getGuildTier(player);
        String playerRole = GuildsHook.getGuildRoleName(player);

        String title = guiConfig.getString("title", "&8Wilayah Guild: {guild}").replace("{guild}", guildName);
        int size = guiConfig.getInt("size", 54);
        Inventory gui = Bukkit.createInventory(new GuildTerritoryDetailHolder(page), size, plugin.color(title));

        // ── Kumpulkan territory yang dimiliki guild ──
        List<ConfigManager.TerritoryInfo> owned = new ArrayList<>();
        for (ConfigManager.TerritoryInfo t : plugin.getConfigManager().getTerritories().values()) {
            DataManager.OwnershipInfo ownership = plugin.getDataManager().getOwnership(t.regionId);
            if (guildId.equals(ownership.ownerGuildId)) owned.add(t);
        }

        // ── Info Guild Item (slot 4 = tengah baris atas) ──
        int infoSlot = guiConfig.getInt("info-item.slot", 4);
        String infoMatStr = guiConfig.getString("info-item.material", "BOOK");
        Material infoMat = Material.matchMaterial(infoMatStr);
        if (infoMat == null) infoMat = Material.BOOK;
        ItemStack infoItem = new ItemStack(infoMat);
        ItemMeta infoMeta = infoItem.getItemMeta();
        if (infoMeta != null) {
            infoMeta.setDisplayName(plugin.color(guiConfig.getString("info-item.name", "&6&lInfo Guild: &e{guild}")
                    .replace("{guild}", guildName)));
            List<String> rawInfoLore = guiConfig.getStringList("info-item.lore");
            List<String> infoLore = new ArrayList<>();
            for (String line : rawInfoLore) {
                infoLore.add(plugin.color(line
                        .replace("{guild}", guildName)
                        .replace("{tier}", guildTier)
                        .replace("{role}", playerRole)
                        .replace("{count}", String.valueOf(owned.size()))));
            }
            infoMeta.setLore(infoLore);
            infoItem.setItemMeta(infoMeta);
        }
        gui.setItem(infoSlot, infoItem);

        int backSlot = guiConfig.getInt("back-item.slot", 49);

        // Slot yang boleh diisi territory = dibaca dari list slots di config
        List<Integer> availableSlots = guiConfig.getIntegerList("territory-item.slots");
        if (availableSlots == null || availableSlots.isEmpty()) {
            // Fallback ke slot baris tengah jika kosong
            availableSlots = new ArrayList<>();
            java.util.Set<Integer> borderSlots = new java.util.HashSet<>();
            for (int i = 0; i < 9; i++) borderSlots.add(i);
            for (int i = size - 9; i < size; i++) borderSlots.add(i);
            for (int row = 1; row < (size/9) - 1; row++) {
                borderSlots.add(row * 9);
                borderSlots.add(row * 9 + 8);
            }
            for (int i = 0; i < size; i++) {
                if (!borderSlots.contains(i) && i != infoSlot && i != backSlot) {
                    availableSlots.add(i);
                }
            }
        }

        // ── Territory Items ──
        String tMatStr = guiConfig.getString("territory-item.material", "MAP");
        Material tMat = Material.matchMaterial(tMatStr);
        if (tMat == null) tMat = Material.MAP;

        int slotsPerPage = availableSlots.size();
        int totalTerritories = owned.size();
        int startIndex = page * slotsPerPage;
        int endIndex = Math.min(startIndex + slotsPerPage, totalTerritories);
        int slotIndex = 0;

        for (int i = startIndex; i < endIndex; i++) {
            ConfigManager.TerritoryInfo t = owned.get(i);
            DataManager.OwnershipInfo ownership = plugin.getDataManager().getOwnership(t.regionId);

            long cooldownLeft = (ownership.cooldownUntil - System.currentTimeMillis()) / 1000;
            String statusStr;
            if (cooldownLeft > 0) {
                long minutes = cooldownLeft / 60;
                long seconds = cooldownLeft % 60;
                statusStr = plugin.color(guiConfig.getString("territory-item.status-cooldown", "&cMasa Cooldown ({time})")
                        .replace("{time}", minutes + "m " + seconds + "s"));
            } else {
                statusStr = plugin.color(guiConfig.getString("territory-item.status-ready", "&aSiap Bertempur"));
            }

            String coordsStr = t.bannerLocation != null
                    ? t.bannerLocation.getBlockX() + ", " + t.bannerLocation.getBlockY() + ", " + t.bannerLocation.getBlockZ()
                    : plugin.color(guiConfig.getString("territory-item.coords-empty", "&cBelum diatur"));

            ItemStack terrItem = new ItemStack(tMat);
            ItemMeta tMeta = terrItem.getItemMeta();
            if (tMeta != null) {
                tMeta.setDisplayName(plugin.color(guiConfig.getString("territory-item.name", "&a&l{territory}")
                        .replace("{territory}", t.displayName)));
                List<String> rawLore = guiConfig.getStringList("territory-item.lore");
                List<String> tLore = new ArrayList<>();
                for (String line : rawLore) {
                    if (line.contains("{buffs}")) {
                        if (!t.buffLore.isEmpty()) {
                            for (String bLine : t.buffLore) {
                                tLore.add(plugin.color(bLine));
                            }
                        } else {
                            tLore.add(plugin.color(guiConfig.getString("territory-item.buffs-empty", " &8- &7Tidak ada efek/buff aktif.")));
                        }
                    } else {
                        tLore.add(plugin.color(line
                                .replace("{region_id}", t.regionId)
                                .replace("{status}", statusStr)
                                .replace("{coords}", coordsStr)));
                    }
                }
                tMeta.setLore(tLore);
                terrItem.setItemMeta(tMeta);
            }
            gui.setItem(availableSlots.get(slotIndex), terrItem);
            slotIndex++;
        }

        // ── Tombol Halaman Sebelumnya ──
        if (page > 0) {
            int prevSlot = guiConfig.getInt("prev-page-item.slot", 48);
            String prevMatStr = guiConfig.getString("prev-page-item.material", "FEATHER");
            Material prevMat = Material.matchMaterial(prevMatStr);
            if (prevMat == null) prevMat = Material.FEATHER;
            ItemStack prevItem = new ItemStack(prevMat);
            ItemMeta prevMeta = prevItem.getItemMeta();
            if (prevMeta != null) {
                prevMeta.setDisplayName(plugin.color(guiConfig.getString("prev-page-item.name", "&b&l« Halaman Sebelumnya")));
                List<String> prevLore = new ArrayList<>();
                for (String line : guiConfig.getStringList("prev-page-item.lore")) {
                    prevLore.add(plugin.color(line));
                }
                prevMeta.setLore(prevLore);
                prevItem.setItemMeta(prevMeta);
            }
            gui.setItem(prevSlot, prevItem);
        }

        // ── Tombol Halaman Berikutnya ──
        if ((page + 1) * slotsPerPage < totalTerritories) {
            int nextSlot = guiConfig.getInt("next-page-item.slot", 50);
            String nextMatStr = guiConfig.getString("next-page-item.material", "FEATHER");
            Material nextMat = Material.matchMaterial(nextMatStr);
            if (nextMat == null) nextMat = Material.FEATHER;
            ItemStack nextItem = new ItemStack(nextMat);
            ItemMeta nextMeta = nextItem.getItemMeta();
            if (nextMeta != null) {
                nextMeta.setDisplayName(plugin.color(guiConfig.getString("next-page-item.name", "&b&lHalaman Berikutnya »")));
                List<String> nextLore = new ArrayList<>();
                for (String line : guiConfig.getStringList("next-page-item.lore")) {
                    nextLore.add(plugin.color(line));
                }
                nextMeta.setLore(nextLore);
                nextItem.setItemMeta(nextMeta);
            }
            gui.setItem(nextSlot, nextItem);
        }

        // ── Tombol Kembali (slot 49 = tengah baris bawah) ──
        String backMatStr = guiConfig.getString("back-item.material", "ARROW");
        Material backMat = Material.matchMaterial(backMatStr);
        if (backMat == null) backMat = Material.ARROW;
        ItemStack backItem = new ItemStack(backMat);
        ItemMeta backMeta = backItem.getItemMeta();
        if (backMeta != null) {
            backMeta.setDisplayName(plugin.color(guiConfig.getString("back-item.name", "&c&l\u00ab Kembali")));
            List<String> backLore = new ArrayList<>();
            for (String line : guiConfig.getStringList("back-item.lore")) {
                backLore.add(plugin.color(line));
            }
            backMeta.setLore(backLore);
            backItem.setItemMeta(backMeta);
        }
        gui.setItem(backSlot, backItem);

        // ── Filler ──
        applyFiller(gui, guiConfig);

        player.openInventory(gui);
    }

    public void openLogsGUI(Player player) {
        UUID guildId = GuildsHook.getGuildId(player);
        if (guildId == null) return;

        org.bukkit.configuration.file.FileConfiguration guiConfig = plugin.getConfigManager().getLogsConfig();
        String guildName = GuildsHook.getGuildName(player);

        String title = guiConfig.getString("title", "&8War Logs: {guild}").replace("{guild}", guildName);
        int size = guiConfig.getInt("size", 45);
        Inventory gui = Bukkit.createInventory(new GuildLogsHolder(), size, plugin.color(title));

        // ── Info Guild Item (slot 4) ──
        int infoSlot = guiConfig.getInt("info-item.slot", 4);
        String infoMatStr = guiConfig.getString("info-item.material", "BOOK");
        Material infoMat = Material.matchMaterial(infoMatStr);
        if (infoMat == null) infoMat = Material.BOOK;
        ItemStack infoItem = new ItemStack(infoMat);
        ItemMeta infoMeta = infoItem.getItemMeta();
        if (infoMeta != null) {
            infoMeta.setDisplayName(plugin.color(guiConfig.getString("info-item.name", "&6&lLogs Guild: &e{guild}")
                    .replace("{guild}", guildName)));
            List<String> rawInfoLore = guiConfig.getStringList("info-item.lore");
            List<String> infoLore = new ArrayList<>();
            for (String line : rawInfoLore) {
                infoLore.add(plugin.color(line));
            }
            infoMeta.setLore(infoLore);
            infoItem.setItemMeta(infoMeta);
        }
        gui.setItem(infoSlot, infoItem);

        // ── Paper Logs ──
        List<String> logsList = plugin.getDataManager().getGuildLogs(guildId);
        List<Integer> availableSlots = guiConfig.getIntegerList("log-item.slots");
        String logMatStr = guiConfig.getString("log-item.material", "PAPER");
        Material logMat = Material.matchMaterial(logMatStr);
        if (logMat == null) logMat = Material.PAPER;

        int itemsToShow = Math.min(logsList.size(), availableSlots.size());
        for (int i = 0; i < itemsToShow; i++) {
            String logEntry = logsList.get(i);
            String[] parts = logEntry.split("\\|", 2);
            String rawTime = parts.length > 0 ? parts[0] : "";
            String rawMsg = parts.length > 1 ? parts[1] : "";
            String time = formatTimestamp(guiConfig, rawTime);
            List<String> msgLines = plugin.getDataManager().formatLogMessageList(guiConfig, rawMsg);

            ItemStack paper = new ItemStack(logMat);
            ItemMeta pMeta = paper.getItemMeta();
            if (pMeta != null) {
                pMeta.setDisplayName(plugin.color(guiConfig.getString("log-item.name", "&e&lAktivitas Guild")));
                List<String> lore = new ArrayList<>();
                String detailsFormat = guiConfig.getString("log-item.details-format", " &8- &f{item}");
                for (String line : guiConfig.getStringList("log-item.lore")) {
                    if (line.contains("{details}")) {
                        // Check if this is a periodic-rewards log
                        if (rawMsg.startsWith("periodic-rewards;") && rawMsg.contains(";")) {
                            String[] tokens = rawMsg.split(";");
                            if (tokens.length > 1) {
                                String[] itemsList = tokens[1].split(",");
                                for (String itemStr : itemsList) {
                                    lore.add(plugin.color(detailsFormat.replace("{item}", itemStr.trim())));
                                }
                            }
                        }
                    } else if (line.contains("{message}")) {
                        for (String msgLine : msgLines) {
                            lore.add(plugin.color(line
                                    .replace("{time}", time)
                                    .replace("{message}", msgLine)));
                        }
                    } else {
                        lore.add(plugin.color(line
                                .replace("{time}", time)));
                    }
                }
                pMeta.setLore(lore);
                paper.setItemMeta(pMeta);
            }
            gui.setItem(availableSlots.get(i), paper);
        }

        // ── Tombol Kembali (slot 40) ──
        int backSlot = guiConfig.getInt("back-item.slot", 40);
        String backMatStr = guiConfig.getString("back-item.material", "ARROW");
        Material backMat = Material.matchMaterial(backMatStr);
        if (backMat == null) backMat = Material.ARROW;
        ItemStack backItem = new ItemStack(backMat);
        ItemMeta backMeta = backItem.getItemMeta();
        if (backMeta != null) {
            backMeta.setDisplayName(plugin.color(guiConfig.getString("back-item.name", "&c&l« Kembali")));
            List<String> backLore = new ArrayList<>();
            for (String line : guiConfig.getStringList("back-item.lore")) {
                backLore.add(plugin.color(line));
            }
            backMeta.setLore(backLore);
            backItem.setItemMeta(backMeta);
        }
        gui.setItem(backSlot, backItem);

        // ── Filler ──
        applyFiller(gui, guiConfig);

        player.openInventory(gui);
    }

    private String formatTimestamp(org.bukkit.configuration.file.FileConfiguration guiConfig, String rawTime) {
        String formatStr = guiConfig.getString("timestamp-format", "yyyy-MM-dd HH:mm:ss");
        try {
            java.text.SimpleDateFormat sdfSource = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            java.util.Date date = sdfSource.parse(rawTime);
            java.text.SimpleDateFormat sdfTarget = new java.text.SimpleDateFormat(formatStr);
            return sdfTarget.format(date);
        } catch (Exception e) {
            return rawTime; // Fallback to raw if parsing fails
        }
    }
}
