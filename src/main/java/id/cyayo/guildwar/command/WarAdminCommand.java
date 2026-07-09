package id.cyayo.guildwar.command;

import id.cyayo.guildwar.CyayoGuildWar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class WarAdminCommand implements CommandExecutor, TabCompleter {

    private final CyayoGuildWar plugin;

    public WarAdminCommand(CyayoGuildWar plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("cyayoguildwar.admin")) {
            sender.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + plugin.getConfig().getString("messages.no-permission")));
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(plugin.color("&8&m========================================"));
            sender.sendMessage(plugin.color("&e&lCyayoGuildWar Admin Commands:"));
            sender.sendMessage(plugin.color(" &8» &b/waradmin reload &8- &7Reload plugin & configurations"));
            sender.sendMessage(plugin.color(" &8» &b/waradmin resetseason &8- &7Reset all guild SR ratings"));
            sender.sendMessage(plugin.color(" &8» &b/waradmin resetsr &8- &7Reset all guild SR ratings manually"));
            sender.sendMessage(plugin.color(" &8» &b/waradmin setbanner <region> &8- &7Set block banner koordinat wilayah"));
            sender.sendMessage(plugin.color(" &8» &b/waradmin vault <guild> &8- &7Buka War Vault milik guild lain"));
            sender.sendMessage(plugin.color(" &8» &b/waradmin give <guild> &8- &7Kirim item yang dipegang ke War Vault guild"));
            sender.sendMessage(plugin.color(" &8» &b/waradmin setseason <angka> &8- &7Ubah angka season aktif"));
            sender.sendMessage(plugin.color(" &8» &b/waradmin setowner <region> <guild> &8- &7Bypass set pemilik teritori"));
            sender.sendMessage(plugin.color(" &8» &b/waradmin testbuff &8- &7Picu paksa roll hadiah periodik teritori"));
            sender.sendMessage(plugin.color("&8&m========================================"));
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            plugin.reload();
            sender.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + plugin.getConfig().getString("messages.data-reloaded")));
            return true;
        }

        if (args[0].equalsIgnoreCase("vault")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Hanya pemain yang dapat menggunakan perintah ini.");
                return true;
            }

            if (args.length < 2) {
                player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + "&cPenggunaan: /waradmin vault <nama_guild>"));
                return true;
            }

            String targetGuildName = args[1];
            java.util.UUID targetGuildId = id.cyayo.guildwar.hook.GuildsHook.getGuildIdByName(targetGuildName);
            if (targetGuildId == null) {
                try {
                    targetGuildId = java.util.UUID.fromString(targetGuildName);
                } catch (Exception ignored) {}
            }
            if (targetGuildId == null) {
                player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + "&cGuild dengan nama atau ID tersebut tidak ditemukan!"));
                return true;
            }

            id.cyayo.guildwar.listener.GUIListener.openVaultGUI(player, targetGuildId, 0);
            return true;
        }

        if (args[0].equalsIgnoreCase("give")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Hanya pemain yang dapat menggunakan perintah ini.");
                return true;
            }

            if (args.length < 2) {
                player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + "&cPenggunaan: /waradmin give <nama_guild>"));
                return true;
            }

            String targetGuildName = args[1];
            java.util.UUID targetGuildId = id.cyayo.guildwar.hook.GuildsHook.getGuildIdByName(targetGuildName);
            if (targetGuildId == null) {
                try {
                    targetGuildId = java.util.UUID.fromString(targetGuildName);
                } catch (Exception ignored) {}
            }
            if (targetGuildId == null) {
                player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + "&cGuild dengan nama atau ID tersebut tidak ditemukan!"));
                return true;
            }

            org.bukkit.inventory.ItemStack handItem = player.getInventory().getItemInMainHand();
            if (handItem == null || handItem.getType() == org.bukkit.Material.AIR) {
                player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + "&cKamu harus memegang item yang ingin dikirimkan!"));
                return true;
            }

            org.bukkit.inventory.ItemStack clonedItem = handItem.clone();
            
            // Deposit the item into the vault safely (handles open vault GUI to prevent overwrite race conditions)
            id.cyayo.guildwar.listener.GUIListener.depositItemToVault(targetGuildId, clonedItem);

            // Remove item from hand
            player.getInventory().setItemInMainHand(null);

            String itemDisplayName = getItemName(clonedItem);
            
            String successMsg = plugin.getConfig().getString("messages.admin-give-success", "&aBerhasil mengirimkan &e{amount}x {item} &ake War Vault Guild &b{guild}&a.")
                    .replace("{amount}", String.valueOf(clonedItem.getAmount()))
                    .replace("{item}", itemDisplayName)
                    .replace("{guild}", targetGuildName);
            player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + successMsg));

            // Broadcast alert to target guild
            String prefix = plugin.getConfig().getString("messages.prefix");
            String alertTemplate = plugin.getConfig().getString("messages.admin-give-received", "&aSeorang admin telah mengirimkan &e{amount}x {item} &ake War Vault Guild kamu!");
            String alert = plugin.color(prefix + alertTemplate
                    .replace("{amount}", String.valueOf(clonedItem.getAmount()))
                    .replace("{item}", itemDisplayName));
            for (Player p : id.cyayo.guildwar.hook.GuildsHook.getOnlineMembers(targetGuildId)) {
                p.sendMessage(alert);
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("resetseason")) {
            if (plugin.getSeasonManager() != null) {
                plugin.getSeasonManager().runSeasonReset();
            } else {
                plugin.getDataManager().resetAllSR();
            }
            sender.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + plugin.getConfig().getString("messages.sr-season-reset")));
            return true;
        }

        if (args[0].equalsIgnoreCase("resetsr")) {
            plugin.getDataManager().resetAllSR();
            sender.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + "&aBerhasil me-reset SR seluruh guild ke 0!"));
            return true;
        }

        if (args[0].equalsIgnoreCase("setseason")) {
            if (args.length < 2) {
                sender.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + "&cPenggunaan: /waradmin setseason <angka>"));
                return true;
            }
            try {
                int targetSeason = Integer.parseInt(args[1]);
                if (plugin.getSeasonManager() != null) {
                    plugin.getSeasonManager().setSeasonNumber(targetSeason);
                    sender.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + "&aBerhasil mengubah angka season menjadi &eSeason " + targetSeason));
                } else {
                    sender.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + "&cSeasonManager belum aktif."));
                }
            } catch (NumberFormatException e) {
                sender.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + "&cSeason harus berupa angka bulat!"));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("setowner")) {
            if (args.length < 3) {
                sender.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + "&cPenggunaan: /waradmin setowner <region_id> <nama_guild>"));
                return true;
            }
            String regionId = args[1].toLowerCase();
            if (!plugin.getConfigManager().getTerritories().containsKey(regionId)) {
                sender.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + "&cWilayah " + regionId + " tidak terdaftar di konfigurasi!"));
                return true;
            }

            String targetGuildName = args[2];
            java.util.UUID targetGuildId = id.cyayo.guildwar.hook.GuildsHook.getGuildIdByName(targetGuildName);
            if (targetGuildId == null) {
                try {
                    targetGuildId = java.util.UUID.fromString(targetGuildName);
                } catch (Exception ignored) {}
            }
            if (targetGuildId == null) {
                sender.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + "&cGuild " + targetGuildName + " tidak ditemukan!"));
                return true;
            }

            id.cyayo.guildwar.data.DataManager.OwnershipInfo ownership = plugin.getDataManager().getOwnership(regionId);
            ownership.ownerGuildId = targetGuildId;
            ownership.ownerGuildName = targetGuildName;
            plugin.getDataManager().saveOwnership(regionId, ownership);

            // Update physical banner in the world to the new owner's design
            if (plugin.getWarManager() != null) {
                id.cyayo.guildwar.config.ConfigManager.TerritoryInfo territory = plugin.getConfigManager().getTerritories().get(regionId);
                if (territory != null) {
                    plugin.getWarManager().updateBannerColor(territory, targetGuildId);
                }
            }

            sender.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + "&aBerhasil mengubah pemilik wilayah &e" + regionId + " &amenjadi Guild &b" + targetGuildName));
            return true;
        }

        if (args[0].equalsIgnoreCase("testbuff")) {
            if (args.length < 2) {
                sender.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + "&cPenggunaan: /waradmin testbuff <region_id>"));
                return true;
            }
            String targetRegionId = args[1].toLowerCase();
            if (!plugin.getConfigManager().getTerritories().containsKey(targetRegionId)) {
                sender.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + "&cWilayah " + targetRegionId + " tidak terdaftar di konfigurasi!"));
                return true;
            }

            if (plugin.getBuffManager() != null) {
                plugin.getBuffManager().triggerPeriodicItemsRoll(targetRegionId);
                sender.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + "&aBerhasil memicu paksa roll hadiah periodik teritori &e" + targetRegionId + "&a. Silakan cek konsol server!"));
            } else {
                sender.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + "&cBuffManager belum siap."));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("setbanner")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Hanya pemain yang dapat menggunakan perintah ini.");
                return true;
            }

            if (args.length < 2) {
                player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + plugin.getConfig().getString("messages.usage-setbanner")));
                return true;
            }

            String regionName = args[1].toLowerCase();
            if (!plugin.getConfigManager().getTerritories().containsKey(regionName)) {
                player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + 
                        plugin.getConfig().getString("messages.region-not-registered").replace("{region}", regionName)));
                return true;
            }

            org.bukkit.block.Block target = player.getTargetBlockExact(5);
            if (target == null || !target.getType().name().contains("BANNER")) {
                player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + plugin.getConfig().getString("messages.banner-not-looking")));
                return true;
            }

            plugin.getConfigManager().saveBannerLocation(regionName, target.getLocation());
            player.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + 
                    plugin.getConfig().getString("messages.banner-saved").replace("{region}", regionName)));
            return true;
        }

        sender.sendMessage(plugin.color(plugin.getConfig().getString("messages.prefix") + "&cPerintah tidak dikenal. Ketik &b/waradmin &cuntuk info bantuan."));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("cyayoguildwar.admin")) return new ArrayList<>();

        if (args.length == 1) {
            List<String> list = Arrays.asList("reload", "resetseason", "resetsr", "setbanner", "vault", "give", "setseason", "setowner", "testbuff");
            List<String> completions = new ArrayList<>();
            for (String s : list) {
                if (s.toLowerCase().startsWith(args[0].toLowerCase())) {
                    completions.add(s);
                }
            }
            return completions;
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("setbanner") || args[0].equalsIgnoreCase("setowner") || args[0].equalsIgnoreCase("testbuff"))) {
            List<String> list = new ArrayList<>();
            String input = args[1].toLowerCase();
            for (String key : plugin.getConfigManager().getTerritories().keySet()) {
                if (key.startsWith(input)) {
                    list.add(key);
                }
            }
            return list;
        } else if (args.length == 3 && args[0].equalsIgnoreCase("setowner")) {
            List<String> list = new ArrayList<>();
            String input = args[2].toLowerCase();
            List<String> allGuilds = id.cyayo.guildwar.hook.GuildsHook.getAllGuildNames();
            for (String gName : allGuilds) {
                if (gName != null && !gName.isEmpty()) {
                    if (gName.toLowerCase().startsWith(input) && !list.contains(gName)) {
                        list.add(gName);
                    }
                }
            }
            if (list.isEmpty()) {
                for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                    String gName = id.cyayo.guildwar.hook.GuildsHook.getGuildName(p);
                    if (gName != null && !gName.equals("None") && !gName.isEmpty()) {
                        if (gName.toLowerCase().startsWith(input) && !list.contains(gName)) {
                            list.add(gName);
                        }
                    }
                }
            }
            return list;
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("vault") || args[0].equalsIgnoreCase("give"))) {
            List<String> list = new ArrayList<>();
            String input = args[1].toLowerCase();
            List<String> allGuilds = id.cyayo.guildwar.hook.GuildsHook.getAllGuildNames();
            for (String gName : allGuilds) {
                if (gName != null && !gName.isEmpty()) {
                    if (gName.toLowerCase().startsWith(input) && !list.contains(gName)) {
                        list.add(gName);
                    }
                }
            }
            if (list.isEmpty()) {
                for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                    String gName = id.cyayo.guildwar.hook.GuildsHook.getGuildName(p);
                    if (gName != null && !gName.equals("None") && !gName.isEmpty()) {
                        if (gName.toLowerCase().startsWith(input) && !list.contains(gName)) {
                            list.add(gName);
                        }
                    }
                }
            }
            return list;
        }
        return new ArrayList<>();
    }

    private String getItemName(org.bukkit.inventory.ItemStack item) {
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
}
