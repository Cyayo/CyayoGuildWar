package id.cyayo.guildwar.command;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public class GuildTerritoryDetailHolder implements InventoryHolder {
    private final int page;

    public GuildTerritoryDetailHolder(int page) {
        this.page = page;
    }

    public int getPage() {
        return page;
    }

    @Override
    public Inventory getInventory() {
        return null;
    }
}
