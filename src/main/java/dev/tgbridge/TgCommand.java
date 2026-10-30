package dev.tgbridge;

import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.util.List;
import java.util.Locale;

public final class TgCommand implements TabExecutor {

    private final TgBridgePlugin plugin;

    public TgCommand(TgBridgePlugin plugin) {
        this.plugin = plugin;
    }

    private void msg(CommandSender s, String text) {
        s.sendMessage(Component.text(text));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            msg(sender, "/tgbot reload | test | notify <on|off> | exclude <list|add|remove> [игрок]");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reloadAll();
                msg(sender, "TgBridge: конфиг перезагружен, бот перезапущен.");
            }
            case "test" -> {
                plugin.broadcast("✅ Тестовое сообщение от TgBridge");
                msg(sender, "TgBridge: тест отправлен.");
            }
            case "notify" -> {
                if (args.length < 2) {
                    msg(sender, "Уведомления: " + (plugin.isNotifyEnabled() ? "включены" : "выключены"));
                } else {
                    boolean on = args[1].equalsIgnoreCase("on");
                    plugin.setNotifyEnabled(on);
                    msg(sender, "Уведомления " + (on ? "включены" : "выключены"));
                }
            }
            case "exclude" -> {
                if (args.length < 2 || args[1].equalsIgnoreCase("list")) {
                    List<String> l = plugin.excludedList();
                    msg(sender, l.isEmpty() ? "Список исключений пуст." : "Исключения: " + String.join(", ", l));
                } else if (args.length >= 3 && args[1].equalsIgnoreCase("add")) {
                    msg(sender, plugin.addExcluded(args[2]) ? "Добавлен: " + args[2] : "Уже в списке или пусто.");
                } else if (args.length >= 3 && args[1].equalsIgnoreCase("remove")) {
                    msg(sender, plugin.removeExcluded(args[2]) ? "Удалён: " + args[2] : "Такого нет в списке.");
                } else {
                    msg(sender, "/tgbot exclude <list|add|remove> [игрок]");
                }
            }
            default -> msg(sender, "Неизвестная подкоманда.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("reload", "test", "notify", "exclude");
        if (args.length == 2 && args[0].equalsIgnoreCase("notify")) return List.of("on", "off");
        if (args.length == 2 && args[0].equalsIgnoreCase("exclude")) return List.of("list", "add", "remove");
        if (args.length == 3 && args[0].equalsIgnoreCase("exclude") && args[1].equalsIgnoreCase("remove")) {
            return plugin.excludedList();
        }
        return List.of();
    }
}
