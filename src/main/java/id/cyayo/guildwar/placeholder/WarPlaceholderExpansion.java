package id.cyayo.guildwar.placeholder;

import id.cyayo.guildwar.CyayoGuildWar;
import id.cyayo.guildwar.config.ConfigManager;
import id.cyayo.guildwar.data.DataManager;
import id.cyayo.guildwar.hook.GuildsHook;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.UUID;

public class WarPlaceholderExpansion extends PlaceholderExpansion {

    private final CyayoGuildWar plugin;

    public WarPlaceholderExpansion(CyayoGuildWar plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "war";
    }

    @Override
    public String getAuthor() {
        return "Cyayo";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public boolean persist() {
        return true; // Keep registered on reload
    }

    @Override
    public String onRequest(OfflinePlayer offlinePlayer, String params) {
        if (offlinePlayer == null || !offlinePlayer.isOnline()) return "";
        Player player = offlinePlayer.getPlayer();

        if (params.equalsIgnoreCase("guild_sr")) {
            UUID guildId = GuildsHook.getGuildId(player);
            if (guildId == null) return "0";
            return String.valueOf(plugin.getDataManager().getGuildSR(guildId));
        }

        if (params.equalsIgnoreCase("current_season")) {
            if (plugin.getSeasonManager() != null) {
                return String.valueOf(plugin.getSeasonManager().getCurrentSeason());
            }
            return "1";
        }

        if (params.toLowerCase().startsWith("top_guild_")) {
            String rankStr = params.substring("top_guild_".length());
            try {
                int rank = Integer.parseInt(rankStr);
                java.util.List<id.cyayo.guildwar.data.DataManager.GuildSrEntry> top = plugin.getDataManager().getTopGuilds();
                int index = rank - 1;
                if (index >= 0 && index < top.size()) {
                    return top.get(index).guildName;
                }
            } catch (Exception ignored) {}
            return "None";
        }

        if (params.toLowerCase().startsWith("top_sr_")) {
            String rankStr = params.substring("top_sr_".length());
            try {
                int rank = Integer.parseInt(rankStr);
                java.util.List<id.cyayo.guildwar.data.DataManager.GuildSrEntry> top = plugin.getDataManager().getTopGuilds();
                int index = rank - 1;
                if (index >= 0 && index < top.size()) {
                    return String.valueOf(top.get(index).srPoints);
                }
            } catch (Exception ignored) {}
            return "0";
        }

        // 1. %war_luck% -> Returns the extra luck if player is in their guild's territory
        if (params.equalsIgnoreCase("luck") || params.equalsIgnoreCase("cyayoluckrng_luck")) {
            UUID guildId = GuildsHook.getGuildId(player);
            if (guildId == null) return "0";

            ConfigManager.TerritoryInfo territory = plugin.getConfigManager().getCachedTerritory(player.getUniqueId());
            if (territory == null) return "0";

            DataManager.OwnershipInfo ownership = plugin.getDataManager().getOwnership(territory.regionId);
            if (guildId.equals(ownership.ownerGuildId)) {
                return String.valueOf(territory.luckBonus);
            }
            return "0";
        }

        // 2. %war_<namaregion>% -> Returns owner info for hologram
        ConfigManager.TerritoryInfo territory = plugin.getConfigManager().getTerritories().get(params.toLowerCase());
        if (territory != null) {
            DataManager.OwnershipInfo ownership = plugin.getDataManager().getOwnership(territory.regionId);
            if (ownership.ownerGuildId == null) {
                String unclaimedFmt = plugin.getConfig().getString("placeholder-formats.unclaimed", "&7Territory ini belum dikuasai");
                return plugin.color(unclaimedFmt);
            }
            
            // Get tier and owner display name
            String tier = "1";
            for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                if (ownership.ownerGuildId.equals(GuildsHook.getGuildId(p))) {
                    tier = GuildsHook.getGuildTier(p);
                    break;
                }
            }

            String displayOwner = org.bukkit.ChatColor.stripColor(plugin.color(ownership.ownerGuildName));
            // Jika dikonfigurasikan menampilkan prefix, coba ambil prefix dari online player pemegang guild tersebut
            String guildMode = plugin.getConfig().getString("placeholder-formats.guild-mode", "name");
            if (guildMode.equalsIgnoreCase("prefix")) {
                for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                    if (ownership.ownerGuildId.equals(GuildsHook.getGuildId(p))) {
                        String prefix = GuildsHook.getGuildPrefix(p);
                        if (prefix != null && !prefix.equals("None")) {
                            displayOwner = prefix; // Biarkan prefix mempertahankan warnanya jika dikehendaki
                        }
                        break;
                    }
                }
            }

            String claimedFmt = plugin.getConfig().getString("placeholder-formats.claimed", "&eTerritory ini dikuasai oleh &b{owner} &e(Tier {tier})")
                    .replace("{owner}", displayOwner)
                    .replace("{tier}", tier);
            return plugin.color(claimedFmt);
        }

        return null;
    }
}
