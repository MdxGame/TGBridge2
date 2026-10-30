package dev.tgbridge;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class NotifyListener implements Listener {

    private final TgBridgePlugin plugin;

    public NotifyListener(TgBridgePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!plugin.shouldNotify("join", p)) return;
        int online = Bukkit.getOnlinePlayers().size();
        plugin.broadcast(plugin.format("join-message", p.getName(), online));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (!plugin.shouldNotify("quit", p)) return;
        // в момент события игрок ещё в списке онлайна
        int online = Math.max(0, Bukkit.getOnlinePlayers().size() - 1);
        plugin.broadcast(plugin.format("quit-message", p.getName(), online));
    }
}
