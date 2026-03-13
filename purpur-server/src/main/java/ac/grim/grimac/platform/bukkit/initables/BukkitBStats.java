package ac.grim.grimac.platform.bukkit.initables;

import ac.grim.grimac.manager.init.start.StartableInitable;

// Osmium - no-op: embedded module should not report bStats metrics
public class BukkitBStats implements StartableInitable {
    @Override
    public void start() {}
}
