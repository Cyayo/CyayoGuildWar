package id.cyayo.guildwar.hook;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

public class GuildsHook {

    private static final Logger log = Logger.getLogger("CyayoGuildWar");

    // ── Cache API object agar tidak selalu Class.forName setiap panggilan ──
    private static Object cachedApi = null;

    private static boolean isGuildsAvailable() {
        org.bukkit.plugin.Plugin p = Bukkit.getPluginManager().getPlugin("Guilds");
        return p != null && p.isEnabled();
    }

    private static Object getApi() {
        if (cachedApi != null) return cachedApi;
        try {
            Class<?> guildsClass = Class.forName("me.glaremasters.guilds.Guilds");
            cachedApi = guildsClass.getMethod("getApi").invoke(null);
        } catch (Exception e) {
            log.warning("[GuildsHook] Gagal mendapatkan Guilds API: " + e.getMessage());
        }
        return cachedApi;
    }

    /** Dipanggil saat plugin enable. */
    public static void checkIntegration() {
        org.bukkit.plugin.Plugin p = Bukkit.getPluginManager().getPlugin("Guilds");
        if (p == null) {
            log.warning("[GuildsHook] Plugin 'Guilds' tidak ditemukan. Fitur guild nonaktif.");
            return;
        }
        if (!p.isEnabled()) {
            log.warning("[GuildsHook] Plugin 'Guilds' ditemukan tapi tidak aktif!");
            return;
        }
        log.info("[GuildsHook] Guilds v" + p.getDescription().getVersion() + " terdeteksi.");
        Object api = getApi();
        if (api != null) {
            log.info("[GuildsHook] Integrasi OK! Menggunakan getGuildByPlayerId(UUID).");
        } else {
            log.warning("[GuildsHook] Gagal mendapatkan API instance!");
        }
    }

    // ────────────────────────────────────────────────────────────
    //  Helper: dapatkan Guild object dari player
    //  Guilds v4: getGuildByPlayerId(UUID)
    //  Fallback : getGuild(OfflinePlayer)
    // ────────────────────────────────────────────────────────────
    private static Object getGuildObject(Player player) {
        if (!isGuildsAvailable()) return null;
        Object api = getApi();
        if (api == null) return null;

        Class<?> apiClass = api.getClass();

        // Guilds v4 primary
        try {
            Object guild = apiClass.getMethod("getGuildByPlayerId", UUID.class)
                    .invoke(api, player.getUniqueId());
            if (guild != null) return guild;
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            log.warning("[GuildsHook] getGuildByPlayerId error: " + e.getMessage());
        }

        // Fallback: getGuild(OfflinePlayer) — Player extends OfflinePlayer
        try {
            Object guild = apiClass.getMethod("getGuild", org.bukkit.OfflinePlayer.class)
                    .invoke(api, player);
            if (guild != null) return guild;
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            log.warning("[GuildsHook] getGuild(OfflinePlayer) error: " + e.getMessage());
        }

        // Fallback: getGuild(UUID) — mungkin player UUID di beberapa versi
        try {
            Object guild = apiClass.getMethod("getGuild", UUID.class)
                    .invoke(api, player.getUniqueId());
            if (guild != null) return guild;
        } catch (NoSuchMethodException ignored) {
        } catch (Exception ignored) {
        }

        // Player tidak punya guild — tidak perlu log, ini normal
        return null;
    }

    public static UUID getGuildIdByName(String name) {
        if (!isGuildsAvailable()) return null;
        Object api = getApi();
        if (api == null) return null;
        try {
            Object guild = api.getClass().getMethod("getGuild", String.class).invoke(api, name);
            if (guild != null) {
                Object id;
                try {
                    id = guild.getClass().getMethod("getId").invoke(guild);
                } catch (NoSuchMethodException ex2) {
                    id = guild.getClass().getMethod("getID").invoke(guild);
                }
                if (id instanceof UUID) return (UUID) id;
            }
        } catch (Exception e) {
            try {
                Object guildHandler = api.getClass().getMethod("getGuildHandler").invoke(api);
                Object guild = guildHandler.getClass().getMethod("getGuild", String.class).invoke(guildHandler, name);
                if (guild != null) {
                    Object id;
                    try {
                        id = guild.getClass().getMethod("getId").invoke(guild);
                    } catch (NoSuchMethodException ex3) {
                        id = guild.getClass().getMethod("getID").invoke(guild);
                    }
                    if (id instanceof UUID) return (UUID) id;
                }
            } catch (Exception ex) {
                log.warning("[GuildsHook] Gagal mencari guild dengan nama " + name + ": " + ex.getMessage());
            }
        }
        return null;
    }

    public static String getGuildNameById(UUID guildId) {
        if (!isGuildsAvailable() || guildId == null) return "None";
        Object api = getApi();
        if (api == null) return "None";
        try {
            Object guild = api.getClass().getMethod("getGuild", UUID.class).invoke(api, guildId);
            if (guild != null) {
                Object name = guild.getClass().getMethod("getName").invoke(guild);
                return name != null ? name.toString() : "None";
            }
        } catch (Exception e) {
            try {
                Object guildHandler = api.getClass().getMethod("getGuildHandler").invoke(api);
                Object guild = guildHandler.getClass().getMethod("getGuild", UUID.class).invoke(guildHandler, guildId);
                if (guild != null) {
                    Object name = guild.getClass().getMethod("getName").invoke(guild);
                    return name != null ? name.toString() : "None";
                }
            } catch (Exception ignored) {}
        }
        return "None";
    }

    // ────────────────────────────────────────────────────────────

    public static boolean hasGuild(Player player) {
        return getGuildId(player) != null;
    }

    public static UUID getGuildId(Player player) {
        Object guild = getGuildObject(player);
        if (guild == null) return null;
        try {
            Object id;
            try {
                id = guild.getClass().getMethod("getId").invoke(guild);
            } catch (NoSuchMethodException e) {
                id = guild.getClass().getMethod("getID").invoke(guild);
            }
            if (id instanceof UUID) return (UUID) id;
        } catch (Exception e) {
            log.warning("[GuildsHook] getId error: " + e.getMessage());
        }
        return null;
    }

    public static String getGuildName(Player player) {
        Object guild = getGuildObject(player);
        if (guild == null) return "None";
        try {
            Object name = guild.getClass().getMethod("getName").invoke(guild);
            return name != null ? name.toString() : "None";
        } catch (Exception e) {
            return "None";
        }
    }

    public static String getGuildPrefix(Player player) {
        Object guild = getGuildObject(player);
        if (guild == null) return "None";
        try {
            Object prefix = guild.getClass().getMethod("getPrefix").invoke(guild);
            return prefix != null ? prefix.toString() : getGuildName(player);
        } catch (Exception e) {
            return getGuildName(player);
        }
    }

    public static String getGuildTier(Player player) {
        Object guild = getGuildObject(player);
        if (guild == null) return "1";
        try {
            Object tierObj = guild.getClass().getMethod("getTier").invoke(guild);
            if (tierObj != null) {
                try {
                    return tierObj.getClass().getMethod("getName").invoke(tierObj).toString();
                } catch (Exception ignored) {
                    return tierObj.getClass().getMethod("getLevel").invoke(tierObj).toString();
                }
            }
        } catch (Exception ignored) {}
        return "1";
    }

    public static List<Player> getOnlineMembers(UUID guildId) {
        List<Player> onlineMembers = new ArrayList<>();
        if (guildId == null) return onlineMembers;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (guildId.equals(getGuildId(p))) {
                onlineMembers.add(p);
            }
        }
        return onlineMembers;
    }

    public static String getGuildRoleName(Player player) {
        if (!isGuildsAvailable()) return "Member";
        Object api = getApi();
        if (api == null) return "Member";

        // Guilds v4: getGuildRole(Player) langsung dari API
        try {
            Object role = api.getClass().getMethod("getGuildRole", Player.class).invoke(api, player);
            if (role != null) {
                String roleName = role.getClass().getMethod("getName").invoke(role).toString();
                log.info("[GuildsHook] Pangkat player " + player.getName() + " terdeteksi: " + roleName);
                return roleName;
            }
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            log.warning("[GuildsHook] getGuildRole error: " + e.getMessage());
        }

        // Fallback: dari Guild object -> getMember(UUID) -> getRole()
        Object guild = getGuildObject(player);
        if (guild == null) return "Member";
        try {
            Object member = null;
            Class<?> guildClass = guild.getClass();
            try {
                member = guildClass.getMethod("getMember", UUID.class).invoke(guild, player.getUniqueId());
            } catch (NoSuchMethodException e1) {
                try {
                    member = guildClass.getMethod("getGuildMember", UUID.class).invoke(guild, player.getUniqueId());
                } catch (NoSuchMethodException ignored) {}
            }
            if (member != null) {
                Object role = member.getClass().getMethod("getRole").invoke(member);
                if (role != null) {
                    return role.getClass().getMethod("getName").invoke(role).toString();
                }
            }
        } catch (Exception ignored) {}
        return "Member";
    }

    public static ItemStack getGuildBanner(Player player) {
        Object guild = getGuildObject(player);
        if (guild == null) return null;
        try {
            Object bannerObj = guild.getClass().getMethod("getBanner").invoke(guild);
            if (bannerObj instanceof ItemStack) {
                return (ItemStack) bannerObj;
            } else if (bannerObj instanceof String base64) {
                if (base64.isEmpty()) return null;
                return deserializeItemStackFromBase64(base64);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static ItemStack deserializeItemStackFromBase64(String base64) {
        try {
            java.io.ByteArrayInputStream inputStream =
                    new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(base64));
            org.bukkit.util.io.BukkitObjectInputStream dataInput =
                    new org.bukkit.util.io.BukkitObjectInputStream(inputStream);
            ItemStack item = (ItemStack) dataInput.readObject();
            dataInput.close();
            return item;
        } catch (Exception e) {
            return null;
        }
    }

    public static List<String> getAllGuildNames() {
        List<String> list = new ArrayList<>();
        if (!isGuildsAvailable()) return list;
        Object api = getApi();
        if (api == null) return list;
        try {
            Object guildHandler = null;
            try {
                guildHandler = api.getClass().getMethod("getGuildHandler").invoke(api);
            } catch (Exception ignored) {}

            Object guildsMapObj = null;
            if (guildHandler != null) {
                try {
                    guildsMapObj = guildHandler.getClass().getMethod("getGuilds").invoke(guildHandler);
                } catch (Exception ignored) {}
            }
            if (guildsMapObj == null) {
                try {
                    guildsMapObj = api.getClass().getMethod("getGuilds").invoke(api);
                } catch (Exception ignored) {}
            }

            if (guildsMapObj instanceof java.util.Map) {
                java.util.Map<?, ?> map = (java.util.Map<?, ?>) guildsMapObj;
                for (Object guildObj : map.values()) {
                    if (guildObj != null) {
                        try {
                            String gName = (String) guildObj.getClass().getMethod("getName").invoke(guildObj);
                            if (gName != null && !gName.isEmpty()) {
                                list.add(gName);
                            }
                        } catch (Exception ignored) {}
                    }
                }
            } else if (guildsMapObj instanceof java.util.Collection) {
                java.util.Collection<?> col = (java.util.Collection<?>) guildsMapObj;
                for (Object guildObj : col) {
                    if (guildObj != null) {
                        try {
                            String gName = (String) guildObj.getClass().getMethod("getName").invoke(guildObj);
                            if (gName != null && !gName.isEmpty()) {
                                list.add(gName);
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception e) {
            log.warning("[GuildsHook] Gagal mendapatkan semua nama guild: " + e.getMessage());
        }
        return list;
    }
}
