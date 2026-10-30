package dev.tgbridge;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class TgBridgePlugin extends JavaPlugin {

    private volatile TelegramBot bot;
    private final Set<String> excluded = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(new NotifyListener(this), this);

        TgCommand handler = new TgCommand(this);
        var cmd = getCommand("tgbot");
        if (cmd != null) {
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }
        startBot(true);
    }

    @Override
    public void onDisable() {
        TelegramBot b = bot;
        if (b != null) {
            if (getConfig().getBoolean("notifications.server-stop", true)) {
                b.broadcastBlocking("🔴 Сервер остановлен");
            }
            b.stop();
            bot = null;
        }
    }

    public void reloadAll() {
        loadSettings();
        startBot(false);
    }

    private void loadSettings() {
        reloadConfig();
        excluded.clear();
        for (String s : getConfig().getStringList("excluded-players")) {
            String k = s.toLowerCase(Locale.ROOT).trim();
            if (!k.isEmpty()) excluded.add(k);
        }
    }

    private void startBot(boolean announce) {
        TelegramBot old = bot;
        if (old != null) {
            old.stop();
            bot = null;
        }
        String token = getConfig().getString("bot-token", "").trim();
        if (token.isEmpty() || token.startsWith("PASTE")) {
            getLogger().warning("Не указан bot-token в config.yml — бот не запущен.");
            return;
        }
        if (getConfig().getLongList("admins").isEmpty()) {
            getLogger().warning("Список admins пуст: напишите боту /start, узнайте свой ID, впишите его в config.yml и сделайте /tgbot reload.");
        }
        TelegramBot b = new TelegramBot(this, token);
        bot = b;
        b.start();
        if (announce && getConfig().getBoolean("notifications.server-start", true)) {
            b.broadcast("🟢 Сервер запущен");
        }
    }

    public boolean isAdmin(long id) {
        return getConfig().getLongList("admins").contains(id);
    }

    public List<Long> notifyChats() {
        List<Long> chats = getConfig().getLongList("notify-chats");
        return chats.isEmpty() ? getConfig().getLongList("admins") : chats;
    }

    public void broadcast(String text) {
        TelegramBot b = bot;
        if (b != null) b.broadcast(text);
    }

    public boolean isNotifyEnabled() {
        return getConfig().getBoolean("notifications.enabled", true);
    }

    public synchronized void setNotifyEnabled(boolean value) {
        getConfig().set("notifications.enabled", value);
        saveConfig();
    }

    // ---------- исключения ----------

    public boolean isExcluded(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String e : excluded) {
            if (e.endsWith("*")) {
                if (n.startsWith(e.substring(0, e.length() - 1))) return true;
            } else if (e.equals(n)) {
                return true;
            }
        }
        return false;
    }

    public List<String> excludedList() {
        List<String> list = new ArrayList<>(excluded);
        Collections.sort(list);
        return list;
    }

    public synchronized boolean addExcluded(String name) {
        String k = name.toLowerCase(Locale.ROOT).trim();
        if (k.isEmpty()) return false;
        boolean changed = excluded.add(k);
        if (changed) saveExcluded();
        return changed;
    }

    public synchronized boolean removeExcluded(String name) {
        boolean changed = excluded.remove(name.toLowerCase(Locale.ROOT).trim());
        if (changed) saveExcluded();
        return changed;
    }

    private void saveExcluded() {
        getConfig().set("excluded-players", excludedList());
        saveConfig();
    }

    // ---------- уведомления ----------

    /** type: "join" или "quit" */
    public boolean shouldNotify(String type, Player p) {
        if (!isNotifyEnabled()) return false;
        if (!getConfig().getBoolean("notifications." + type, true)) return false;
        if (p.hasPermission("tgbot.silent")) return false;
        return !isExcluded(p.getName());
    }

    public String format(String key, String player, int online) {
        String tpl = getConfig().getString("notifications." + key, "{player}");
        return tpl.replace("{player}", player).replace("{online}", Integer.toString(online));
    }
}
