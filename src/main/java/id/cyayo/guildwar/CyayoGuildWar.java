package id.cyayo.guildwar;

import id.cyayo.guildwar.command.WarCommand;
import id.cyayo.guildwar.config.ConfigManager;
import id.cyayo.guildwar.data.DataManager;
import id.cyayo.guildwar.listener.GUIListener;
import id.cyayo.guildwar.listener.WarListener;
import id.cyayo.guildwar.manager.BuffManager;
import id.cyayo.guildwar.manager.WarManager;
import id.cyayo.guildwar.manager.WebhookManager;
import id.cyayo.guildwar.placeholder.WarPlaceholderExpansion;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class CyayoGuildWar extends JavaPlugin {

    private static CyayoGuildWar instance;

    private ConfigManager configManager;
    private DataManager dataManager;
    private WebhookManager webhookManager;
    private WarManager warManager;
    private BuffManager buffManager;
    private id.cyayo.guildwar.manager.SeasonManager seasonManager;

    @Override
    public void onEnable() {
        instance = this;

        // Save default config
        saveDefaultConfig();

        // Load Managers
        this.configManager = new ConfigManager(this);
        this.dataManager = new DataManager(this);
        this.webhookManager = new WebhookManager(this);
        this.warManager = new WarManager(this);
        this.buffManager = new BuffManager(this);
        this.seasonManager = new id.cyayo.guildwar.manager.SeasonManager(this);

        // Register Listeners
        getServer().getPluginManager().registerEvents(new WarListener(this), this);
        getServer().getPluginManager().registerEvents(new GUIListener(this), this);
        getServer().getPluginManager().registerEvents(buffManager, this);

        // Register Commands
        WarCommand cmd = new WarCommand(this);
        getCommand("war").setExecutor(cmd);
        getCommand("war").setTabCompleter(cmd);

        id.cyayo.guildwar.command.WarAdminCommand adminCmd = new id.cyayo.guildwar.command.WarAdminCommand(this);
        getCommand("waradmin").setExecutor(adminCmd);
        getCommand("waradmin").setTabCompleter(adminCmd);

        // Check plugin integrations
        id.cyayo.guildwar.hook.GuildsHook.checkIntegration();

        // Register PlaceholderAPI Expansion
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new WarPlaceholderExpansion(this).register();
            getLogger().info("PlaceholderAPI hooked successfully!");
        }

        // Initialize cache for online players
        for (Player p : getServer().getOnlinePlayers()) {
            this.configManager.updateCachedTerritory(p);
        }

        getLogger().info("CyayoGuildWar v" + getDescription().getVersion() + " enabled successfully!");
    }

    @Override
    public void onDisable() {
        // Stop periodic tasks
        if (buffManager != null) {
            buffManager.stopTasks();
        }

        // Restore any players currently in war and cleanup
        if (warManager != null) {
            for (Player p : getServer().getOnlinePlayers()) {
                if (warManager.isPlayerInWar(p.getUniqueId())) {
                    warManager.restorePlayerState(p);
                }
            }
            warManager.cleanupOnDisable();
        }

        getLogger().info("CyayoGuildWar disabled.");
    }

    public void reload() {
        if (buffManager != null) {
            buffManager.stopTasks();
            org.bukkit.event.HandlerList.unregisterAll(buffManager);
        }
        if (warManager != null) {
            warManager.cleanupOnDisable();
        }
        
        configManager.reload();
        dataManager.loadAllData();
        
        // Rekonstruksi instansi WarManager baru agar me-refresh scheduler dan cache
        this.warManager = new WarManager(this);
        
        // Update cache for online players
        for (Player p : getServer().getOnlinePlayers()) {
            configManager.updateCachedTerritory(p);
        }
        
        this.buffManager = new BuffManager(this);
        getServer().getPluginManager().registerEvents(buffManager, this);

        if (this.seasonManager != null) {
            this.seasonManager.reloadSeasonTimer();
        } else {
            this.seasonManager = new id.cyayo.guildwar.manager.SeasonManager(this);
        }
    }

    public static CyayoGuildWar getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public DataManager getDataManager() {
        return dataManager;
    }

    public WebhookManager getWebhookManager() {
        return webhookManager;
    }

    public WarManager getWarManager() {
        return warManager;
    }

    public BuffManager getBuffManager() {
        return buffManager;
    }

    public id.cyayo.guildwar.manager.SeasonManager getSeasonManager() {
        return seasonManager;
    }

    public String color(String message) {
        if (message == null) return "";
        return ChatColor.translateAlternateColorCodes('&', message);
    }
}
