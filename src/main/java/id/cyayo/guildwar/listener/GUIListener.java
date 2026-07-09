package id.cyayo.guildwar.listener;

import id.cyayo.guildwar.CyayoGuildWar;
import id.cyayo.guildwar.config.ConfigManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class GUIListener implements Listener {

    private final CyayoGuildWar plugin;

    public GUIListener(CyayoGuildWar plugin) {
        this.plugin = plugin;
        instance = this;
    }

    public static class GuildVaultHolder implements org.bukkit.inventory.InventoryHolder {
        private final UUID guildId;
        private final int page;

        public GuildVaultHolder(UUID guildId, int page) {
            this.guildId = guildId;
            this.page = page;
        }

        public UUID getGuildId() {
            return guildId;
        }

        public int getPage() {
            return page;
        }

        @Override
        public org.bukkit.inventory.Inventory getInventory() {
            return null;
        }
    }

    private static GUIListener instance;
    private static final java.util.Map<UUID, java.util.Map<Integer, org.bukkit.inventory.Inventory>> activeVaults = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<UUID, Long> clickCooldowns = new java.util.concurrent.ConcurrentHashMap<>();

    public static void openVaultGUI(Player player, UUID guildId, int page) {
        if (instance == null) return;
        
        org.bukkit.configuration.file.FileConfiguration cfg = instance.plugin.getConfigManager().getVaultConfig();
        java.util.Map<Integer, org.bukkit.inventory.Inventory> guildPages = activeVaults.computeIfAbsent(guildId, k -> new java.util.concurrent.ConcurrentHashMap<>());
        
        org.bukkit.inventory.Inventory inv = guildPages.get(page);
        if (inv == null) {
            String gName = id.cyayo.guildwar.hook.GuildsHook.getGuildNameById(guildId);
            if (gName == null || gName.equals("None")) {
                gName = instance.plugin.getDataManager().getGuildNameFromCache(guildId);
            }
            if (gName == null || gName.equals("None")) {
                gName = "Unknown";
            }
            String title = instance.plugin.color(cfg.getString("title", "&8War Vault - {guild} (Hal. {page})")
                    .replace("{guild}", gName)
                    .replace("{page}", String.valueOf(page + 1)));
            int size = cfg.getInt("size", 54);
            
            inv = org.bukkit.Bukkit.createInventory(new GuildVaultHolder(guildId, page), size, title);
            populateVaultPage(inv, guildId, page);
            guildPages.put(page, inv);
        }
        
        player.openInventory(inv);
    }

    private static void populateVaultPage(org.bukkit.inventory.Inventory inv, UUID guildId, int page) {
        if (instance == null) return;
        org.bukkit.configuration.file.FileConfiguration cfg = instance.plugin.getConfigManager().getVaultConfig();
        
        inv.clear();
        
        String fillerMatStr = cfg.getString("filler.material", "GRAY_STAINED_GLASS_PANE");
        Material fillerMat = Material.matchMaterial(fillerMatStr);
        if (fillerMat == null) fillerMat = Material.GRAY_STAINED_GLASS_PANE;
        ItemStack fillerItem = new ItemStack(fillerMat);
        org.bukkit.inventory.meta.ItemMeta fillerMeta = fillerItem.getItemMeta();
        if (fillerMeta != null) {
            fillerMeta.setDisplayName(instance.plugin.color(cfg.getString("filler.name", " ")));
            fillerItem.setItemMeta(fillerMeta);
        }
        for (int slot : cfg.getIntegerList("filler.slots")) {
            if (slot >= 0 && slot < inv.getSize()) {
                inv.setItem(slot, fillerItem);
            }
        }
        
        int infoSlot = cfg.getInt("info-item.slot", 4);
        String infoMatStr = cfg.getString("info-item.material", "BOOK");
        Material infoMat = Material.matchMaterial(infoMatStr);
        if (infoMat == null) infoMat = Material.BOOK;
        ItemStack infoItem = new ItemStack(infoMat);
        org.bukkit.inventory.meta.ItemMeta infoMeta = infoItem.getItemMeta();
        if (infoMeta != null) {
            String guildName = "Unknown";
            String tier = "1";
            int terrCount = 0;
            
            Player onlineMember = null;
            for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                if (guildId.equals(id.cyayo.guildwar.hook.GuildsHook.getGuildId(p))) {
                    onlineMember = p;
                    break;
                }
            }
            if (onlineMember != null) {
                guildName = id.cyayo.guildwar.hook.GuildsHook.getGuildName(onlineMember);
                tier = id.cyayo.guildwar.hook.GuildsHook.getGuildTier(onlineMember);
            }
            
            for (ConfigManager.TerritoryInfo t : instance.plugin.getConfigManager().getTerritories().values()) {
                id.cyayo.guildwar.data.DataManager.OwnershipInfo ownership = instance.plugin.getDataManager().getOwnership(t.regionId);
                if (guildId.equals(ownership.ownerGuildId)) {
                    terrCount++;
                }
            }
            
            infoMeta.setDisplayName(instance.plugin.color(cfg.getString("info-item.name", "&6&lInformasi Guild: {guild}")
                    .replace("{guild}", guildName)));
            List<String> rawLore = cfg.getStringList("info-item.lore");
            List<String> coloredLore = new ArrayList<>();
            for (String line : rawLore) {
                coloredLore.add(instance.plugin.color(line
                        .replace("{tier}", tier)
                        .replace("{territories_count}", String.valueOf(terrCount))));
            }
            infoMeta.setLore(coloredLore);
            infoItem.setItemMeta(infoMeta);
        }
        if (infoSlot >= 0 && infoSlot < inv.getSize()) {
            inv.setItem(infoSlot, infoItem);
        }
        
        if (page > 0) {
            int backSlot = cfg.getInt("back-page-item.slot", 48);
            String backMatStr = cfg.getString("back-page-item.material", "ARROW");
            Material backMat = Material.matchMaterial(backMatStr);
            if (backMat == null) backMat = Material.ARROW;
            ItemStack backItem = new ItemStack(backMat);
            org.bukkit.inventory.meta.ItemMeta backMeta = backItem.getItemMeta();
            if (backMeta != null) {
                backMeta.setDisplayName(instance.plugin.color(cfg.getString("back-page-item.name", "&c&l\u00ab Halaman Sebelumnya")
                        .replace("{prev_page}", String.valueOf(page))));
                backItem.setItemMeta(backMeta);
            }
            if (backSlot >= 0 && backSlot < inv.getSize()) {
                inv.setItem(backSlot, backItem);
            }
        }
        
        int nextSlot = cfg.getInt("next-page-item.slot", 50);
        String nextMatStr = cfg.getString("next-page-item.material", "ARROW");
        Material nextMat = Material.matchMaterial(nextMatStr);
        if (nextMat == null) nextMat = Material.ARROW;
        ItemStack nextItem = new ItemStack(nextMat);
        org.bukkit.inventory.meta.ItemMeta nextMeta = nextItem.getItemMeta();
        if (nextMeta != null) {
            nextMeta.setDisplayName(instance.plugin.color(cfg.getString("next-page-item.name", "&a&lHalaman Berikutnya \u00bb")
                    .replace("{next_page}", String.valueOf(page + 2))));
            nextItem.setItemMeta(nextMeta);
        }
        if (nextSlot >= 0 && nextSlot < inv.getSize()) {
            inv.setItem(nextSlot, nextItem);
        }

        List<ItemStack> vaultItems = instance.plugin.getDataManager().getVaultItems(guildId);
        List<Integer> rewardSlots = cfg.getIntegerList("reward-slots");
        int slotsPerPage = rewardSlots.size();
        
        for (int i = 0; i < slotsPerPage; i++) {
            int itemIndex = page * slotsPerPage + i;
            int targetSlot = rewardSlots.get(i);
            
            if (itemIndex < vaultItems.size()) {
                inv.setItem(targetSlot, vaultItems.get(itemIndex));
            } else {
                inv.setItem(targetSlot, null);
            }
        }
    }

    public static void updateActiveVault(UUID guildId, List<ItemStack> vaultItems) {
        if (instance == null) return;
        
        java.util.Map<Integer, org.bukkit.inventory.Inventory> guildPages = activeVaults.get(guildId);
        if (guildPages == null || guildPages.isEmpty()) return;
        
        org.bukkit.configuration.file.FileConfiguration cfg = instance.plugin.getConfigManager().getVaultConfig();
        List<Integer> rewardSlots = cfg.getIntegerList("reward-slots");
        int slotsPerPage = rewardSlots.size();
        
        org.bukkit.Bukkit.getScheduler().runTask(instance.plugin, () -> {
            for (java.util.Map.Entry<Integer, org.bukkit.inventory.Inventory> entry : guildPages.entrySet()) {
                int page = entry.getKey();
                org.bukkit.inventory.Inventory inv = entry.getValue();
                
                for (int i = 0; i < slotsPerPage; i++) {
                    int itemIndex = page * slotsPerPage + i;
                    int targetSlot = rewardSlots.get(i);
                    
                    if (itemIndex < vaultItems.size()) {
                        inv.setItem(targetSlot, vaultItems.get(itemIndex));
                    } else {
                        inv.setItem(targetSlot, null);
                    }
                }
            }
        });
    }

    private static void saveVaultFromPage(UUID guildId, int page, org.bukkit.inventory.Inventory inv) {
        if (instance == null) return;
        org.bukkit.configuration.file.FileConfiguration cfg = instance.plugin.getConfigManager().getVaultConfig();
        List<Integer> rewardSlots = cfg.getIntegerList("reward-slots");
        int slotsPerPage = rewardSlots.size();
        
        List<ItemStack> pageItems = new ArrayList<>();
        for (int slot : rewardSlots) {
            ItemStack item = inv.getItem(slot);
            pageItems.add(item);
        }
        
        List<ItemStack> allItems = instance.plugin.getDataManager().getVaultItems(guildId);
        
        int maxIndexRequired = (page + 1) * slotsPerPage;
        while (allItems.size() < maxIndexRequired) {
            allItems.add(null);
        }
        
        for (int i = 0; i < slotsPerPage; i++) {
            int targetIndex = page * slotsPerPage + i;
            allItems.set(targetIndex, pageItems.get(i));
        }
        
        for (int i = allItems.size() - 1; i >= 0; i--) {
            ItemStack item = allItems.get(i);
            if (item == null || item.getType() == Material.AIR) {
                allItems.remove(i);
            } else {
                break;
            }
        }
        
        instance.plugin.getDataManager().saveVaultItems(guildId, allItems);
        updateActiveVault(guildId, allItems);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        if (event.getInventory().getHolder() instanceof GuildVaultHolder holder) {
            synchronized (event.getInventory()) {
                UUID clickerUuid = player.getUniqueId();
                long now = System.currentTimeMillis();
                long lastClick = clickCooldowns.getOrDefault(clickerUuid, 0L);
                if (now - lastClick < 250) {
                    event.setCancelled(true);
                    return;
                }
                clickCooldowns.put(clickerUuid, now);
                
                UUID guildId = holder.getGuildId();
                int page = holder.getPage();
                
                org.bukkit.configuration.file.FileConfiguration cfg = plugin.getConfigManager().getVaultConfig();
                List<Integer> rewardSlots = cfg.getIntegerList("reward-slots");
                int backSlot = cfg.getInt("back-page-item.slot", 48);
                int nextSlot = cfg.getInt("next-page-item.slot", 50);
                
                if (event.getClickedInventory() == event.getView().getTopInventory()) {
                    int slot = event.getSlot();
                    
                    if (slot == backSlot && page > 0) {
                        event.setCancelled(true);
                        saveVaultFromPage(guildId, page, event.getInventory());
                        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                            openVaultGUI(player, guildId, page - 1);
                        });
                        return;
                    }
                    
                    if (slot == nextSlot) {
                        event.setCancelled(true);
                        saveVaultFromPage(guildId, page, event.getInventory());
                        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                            openVaultGUI(player, guildId, page + 1);
                        });
                        return;
                    }
                    
                    if (!rewardSlots.contains(slot)) {
                        event.setCancelled(true);
                        return;
                    }
                    
                    String roleName = id.cyayo.guildwar.hook.GuildsHook.getGuildRoleName(player);
                    List<String> withdrawRanks = cfg.getStringList("permissions.withdraw");
                    boolean canWithdraw = player.hasPermission("cyayoguildwar.admin.vault") || player.hasPermission("cyayoguildwar.admin");
                    if (!canWithdraw) {
                        for (String r : withdrawRanks) {
                            if (r.equalsIgnoreCase(roleName)) {
                                canWithdraw = true;
                                break;
                            }
                        }
                    }
                    
                    if (!canWithdraw) {
                        event.setCancelled(true);
                        player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + 
                                "&cRole guild kamu tidak diperbolehkan mengambil item dari War Vault!"));
                        return;
                    }
                    
                    ItemStack actualItem = event.getClickedInventory().getItem(slot);
                    if (actualItem == null || actualItem.getType() == Material.AIR) {
                        event.setCancelled(true);
                        return;
                    }
                    
                    switch (event.getAction()) {
                        case PICKUP_ALL:
                        case PICKUP_HALF:
                        case PICKUP_ONE:
                        case PICKUP_SOME:
                        case MOVE_TO_OTHER_INVENTORY:
                            break;
                        default:
                            event.setCancelled(true);
                            return;
                    }
                } else {
                    if (event.getAction() == org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                        event.setCancelled(true);
                        return;
                    }
                    if (event.getAction() == org.bukkit.event.inventory.InventoryAction.HOTBAR_SWAP || 
                        event.getAction() == org.bukkit.event.inventory.InventoryAction.HOTBAR_MOVE_AND_READD) {
                        event.setCancelled(true);
                        return;
                    }
                }
            }
            return;
        }

        if (event.getInventory().getHolder() instanceof id.cyayo.guildwar.command.GuildWarInfoHolder) {
            event.setCancelled(true);
            
            int actionSlot = plugin.getConfigManager().getTerritoryInfoConfig().getInt("action-item.slot", 16);
            if (event.getSlot() == actionSlot) {
                player.closeInventory();
                
                ConfigManager.TerritoryInfo territory = plugin.getConfigManager().getTerritoryByLocation(player.getLocation());
                if (territory == null) {
                    player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + 
                            plugin.getConfig().getString("messages.no-region")));
                    return;
                }

                plugin.getWarManager().startWar(player, territory);
            }
        } else if (event.getInventory().getHolder() instanceof id.cyayo.guildwar.command.GuildStatsHolder) {
            event.setCancelled(true);

            if (event.getClickedInventory() != event.getView().getTopInventory()) return;

            int slot = event.getSlot();
            int bannerSlot = plugin.getConfigManager().getStatsConfig().getInt("banner-item.slot", 13);
            int terrSlot   = plugin.getConfigManager().getStatsConfig().getInt("territory-item.slot", 22);
            int logsSlot   = plugin.getConfigManager().getStatsConfig().getInt("logs-item.slot", 31);

            if (slot == logsSlot) {
                org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    id.cyayo.guildwar.command.WarCommand wc =
                            (id.cyayo.guildwar.command.WarCommand) plugin.getCommand("war").getExecutor();
                    wc.openLogsGUI(player);
                }, 1L);
                return;
            }

            if (slot == terrSlot) {
                org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    id.cyayo.guildwar.command.WarCommand wc =
                            (id.cyayo.guildwar.command.WarCommand) plugin.getCommand("war").getExecutor();
                    wc.openTerritoryDetailGUI(player);
                }, 1L);
                return;
            }

            if (slot != bannerSlot) return;

            ItemStack cursorItem = event.getCursor();
            boolean hasBannerOnCursor = cursorItem != null
                    && cursorItem.getType() != Material.AIR
                    && cursorItem.getType().name().contains("BANNER");

            if (!hasBannerOnCursor) {
                player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") +
                        "&ePindahkan banner dari inventory kamu ke cursor (klik), lalu klik slot ini untuk mengganti!"));
                return;
            }

            UUID guildId = id.cyayo.guildwar.hook.GuildsHook.getGuildId(player);
            if (guildId == null) {
                player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") +
                        plugin.getConfig().getString("messages.not-in-guild")));
                return;
            }

            // check permission to manage banner
            String playerRole = id.cyayo.guildwar.hook.GuildsHook.getGuildRoleName(player);
            boolean canManage = playerRole != null && (playerRole.equalsIgnoreCase("LEADER") || playerRole.equalsIgnoreCase("CO_LEADER") || playerRole.equalsIgnoreCase("OFFICER") || playerRole.equalsIgnoreCase("MODERATOR"));
            if (!canManage) {
                player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") +
                        "&cKamu tidak memiliki izin kelola banner di guild kamu!"));
                return;
            }

            org.bukkit.inventory.meta.BannerMeta meta = (org.bukkit.inventory.meta.BannerMeta) cursorItem.getItemMeta();
            org.bukkit.DyeColor baseColor = org.bukkit.DyeColor.WHITE;
            String matName = cursorItem.getType().name();
            for (org.bukkit.DyeColor color : org.bukkit.DyeColor.values()) {
                if (matName.startsWith(color.name() + "_")) {
                    baseColor = color;
                    break;
                }
            }

            plugin.getDataManager().saveGuildBanner(guildId, baseColor, meta != null ? meta.getPatterns() : new java.util.ArrayList<>());
            plugin.getWarManager().updateAllGuildBanners(guildId);

            event.getView().setCursor(null);

            player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") +
                    "&aBanner Guild kamu berhasil diperbarui!"));

            org.bukkit.scheduler.BukkitRunnable BukrunnableRunnable = new org.bukkit.scheduler.BukkitRunnable() {
                @Override
                public void run() {
                    plugin.getCommand("war").execute(player, "war", new String[]{"stats"});
                }
            };
            BukrunnableRunnable.runTaskLater(plugin, 1L);
        } else if (event.getInventory().getHolder() instanceof id.cyayo.guildwar.command.GuildTerritoryDetailHolder) {
            event.setCancelled(true);

            if (event.getClickedInventory() != event.getView().getTopInventory()) return;

            id.cyayo.guildwar.command.GuildTerritoryDetailHolder holder = (id.cyayo.guildwar.command.GuildTerritoryDetailHolder) event.getInventory().getHolder();
            int currentPage = holder.getPage();

            int slot = event.getSlot();
            int backSlot = plugin.getConfigManager().getTerritoryDetailConfig().getInt("back-item.slot", 49);
            int prevSlot = plugin.getConfigManager().getTerritoryDetailConfig().getInt("prev-page-item.slot", 48);
            int nextSlot = plugin.getConfigManager().getTerritoryDetailConfig().getInt("next-page-item.slot", 50);

            if (slot == backSlot) {
                org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    plugin.getCommand("war").execute(player, "war", new String[]{"stats"});
                }, 1L);
            } else if (slot == prevSlot) {
                org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    id.cyayo.guildwar.command.WarCommand wc =
                            (id.cyayo.guildwar.command.WarCommand) plugin.getCommand("war").getExecutor();
                    wc.openTerritoryDetailGUI(player, currentPage - 1);
                }, 1L);
            } else if (slot == nextSlot) {
                org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    id.cyayo.guildwar.command.WarCommand wc =
                            (id.cyayo.guildwar.command.WarCommand) plugin.getCommand("war").getExecutor();
                    wc.openTerritoryDetailGUI(player, currentPage + 1);
                }, 1L);
            }
        } else if (event.getInventory().getHolder() instanceof id.cyayo.guildwar.command.GuildLogsHolder) {
            event.setCancelled(true);

            if (event.getClickedInventory() != event.getView().getTopInventory()) return;

            int slot = event.getSlot();
            int backSlot = plugin.getConfigManager().getLogsConfig().getInt("back-item.slot", 40);

            if (slot == backSlot) {
                org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    id.cyayo.guildwar.command.WarCommand wc =
                            (id.cyayo.guildwar.command.WarCommand) plugin.getCommand("war").getExecutor();
                    wc.openStatsGUI(player);
                }, 1L);
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof GuildVaultHolder) {
            event.setCancelled(true);
            return;
        }
        if (event.getInventory().getHolder() instanceof id.cyayo.guildwar.command.GuildWarInfoHolder ||
            event.getInventory().getHolder() instanceof id.cyayo.guildwar.command.GuildStatsHolder ||
            event.getInventory().getHolder() instanceof id.cyayo.guildwar.command.GuildLogsHolder ||
            event.getInventory().getHolder() instanceof id.cyayo.guildwar.command.GuildTerritoryDetailHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof GuildVaultHolder holder) {
            UUID guildId = holder.getGuildId();
            int page = holder.getPage();
            
            saveVaultFromPage(guildId, page, event.getInventory());
            
            org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                java.util.Map<Integer, org.bukkit.inventory.Inventory> guildPages = activeVaults.get(guildId);
                if (guildPages != null) {
                    boolean hasViewers = false;
                    for (org.bukkit.inventory.Inventory inv : guildPages.values()) {
                        if (!inv.getViewers().isEmpty()) {
                            hasViewers = true;
                            break;
                        }
                    }
                    if (!hasViewers) {
                        activeVaults.remove(guildId);
                    }
                }
            }, 10L);
        }
    }

    private static final java.util.Queue<PendingDeposit> depositQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static boolean depositTaskRunning = false;

    public static class PendingDeposit {
        public final UUID guildId;
        public final ItemStack item;

        public PendingDeposit(UUID guildId, ItemStack item) {
            this.guildId = guildId;
            this.item = item;
        }
    }

    public static void depositItemToVault(UUID guildId, ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return;
        depositQueue.add(new PendingDeposit(guildId, item.clone()));
        startDepositTask();
    }

    private static void startDepositTask() {
        if (depositTaskRunning || instance == null) return;
        depositTaskRunning = true;

        new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                PendingDeposit deposit = depositQueue.poll();
                if (deposit == null) {
                    depositTaskRunning = false;
                    cancel();
                    return;
                }
                processSingleDeposit(deposit.guildId, deposit.item);
            }
        }.runTaskTimer(instance.plugin, 1L, 1L);
    }

    private static void processSingleDeposit(UUID guildId, ItemStack item) {
        org.bukkit.configuration.file.FileConfiguration cfg = instance.plugin.getConfigManager().getVaultConfig();
        List<Integer> rewardSlots = cfg.getIntegerList("reward-slots");

        java.util.Map<Integer, org.bukkit.inventory.Inventory> guildPages = activeVaults.get(guildId);
        if (guildPages != null && !guildPages.isEmpty()) {
            ItemStack remaining = item.clone();

            // Try to stack on existing items in active page inventories
            for (org.bukkit.inventory.Inventory inv : guildPages.values()) {
                for (int slot : rewardSlots) {
                    ItemStack existing = inv.getItem(slot);
                    if (existing != null && existing.getType() != Material.AIR && existing.isSimilar(remaining)) {
                        int maxStack = existing.getMaxStackSize();
                        int currentAmount = existing.getAmount();
                        if (currentAmount < maxStack) {
                            int space = maxStack - currentAmount;
                            int toAdd = Math.min(remaining.getAmount(), space);
                            existing.setAmount(currentAmount + toAdd);
                            remaining.setAmount(remaining.getAmount() - toAdd);
                            if (remaining.getAmount() <= 0) {
                                for (java.util.Map.Entry<Integer, org.bukkit.inventory.Inventory> entry : guildPages.entrySet()) {
                                    saveVaultFromPage(guildId, entry.getKey(), entry.getValue());
                                }
                                return;
                            }
                        }
                    }
                }
            }

            // Try to place in empty slots in active page inventories
            for (org.bukkit.inventory.Inventory inv : guildPages.values()) {
                for (int slot : rewardSlots) {
                    ItemStack existing = inv.getItem(slot);
                    if (existing == null || existing.getType() == Material.AIR) {
                        inv.setItem(slot, remaining);
                        for (java.util.Map.Entry<Integer, org.bukkit.inventory.Inventory> entry : guildPages.entrySet()) {
                            saveVaultFromPage(guildId, entry.getKey(), entry.getValue());
                        }
                        return;
                    }
                }
            }

            // If it couldn't fit in open pages, add it to the database list directly
            List<ItemStack> allItems = instance.plugin.getDataManager().getVaultItems(guildId);
            id.cyayo.guildwar.data.DataManager.addAndStackItem(allItems, remaining);
            instance.plugin.getDataManager().saveVaultItems(guildId, allItems);
            updateActiveVault(guildId, allItems);
        } else {
            // The vault is closed. Modify the database list directly.
            List<ItemStack> allItems = instance.plugin.getDataManager().getVaultItems(guildId);
            id.cyayo.guildwar.data.DataManager.addAndStackItem(allItems, item);
            instance.plugin.getDataManager().saveVaultItems(guildId, allItems);
        }
    }
}
