package dev.tgbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.management.ManagementFactory;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public final class TelegramBot {

    private static final String HELP = """
            🤖 Управление сервером

            /online — кто на сервере
            /status — TPS, память, аптайм
            /say <текст> — сообщение в игровой чат
            /kick <игрок> [причина]
            /ban <игрок> [причина]
            /unban <игрок>
            /whitelist <add|remove|list|on|off> [игрок]
            /cmd <команда> — любая консольная команда
            /save — сохранить мир
            /stop confirm — остановить сервер

            Уведомления:
            /notify on|off — вкл/выкл уведомления о входе/выходе
            /exclude list|add|remove <игрок> — исключения (маска: Bot_*)
            """;

    private final TgBridgePlugin plugin;
    private final String base;
    private final HttpClient http;
    private int failCount = 0;

    private volatile boolean running;
    private Thread thread;
    private long offset = 0;

    public TelegramBot(TgBridgePlugin plugin, String token) {
        this.plugin = plugin;

        String api = plugin.getConfig().getString("api-url", "").trim();
        if (api.isEmpty()) api = "https://api.telegram.org";
        while (api.endsWith("/")) api = api.substring(0, api.length() - 1);
        this.base = api + "/bot" + token + "/";

        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20));
        String host = plugin.getConfig().getString("proxy.host", "").trim();
        int port = plugin.getConfig().getInt("proxy.port", 0);
        if (!host.isEmpty() && port > 0) {
            b.proxy(ProxySelector.of(new InetSocketAddress(host, port)));
            String user = plugin.getConfig().getString("proxy.username", "");
            String pass = plugin.getConfig().getString("proxy.password", "");
            if (!user.isEmpty()) {
                // разрешаем Basic-авторизацию для прокси при HTTPS
                System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
                b.authenticator(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(user, pass.toCharArray());
                    }
                });
            }
            plugin.getLogger().info("Telegram: используется HTTP-прокси " + host + ":" + port);
        }
        this.http = b.build();
    }

    // ================= жизненный цикл =================

    public void start() {
        running = true;
        thread = new Thread(this::pollLoop, "TgBridge-Poll");
        thread.setDaemon(true);
        thread.start();
        registerMenu();
    }

    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    // ================= отправка =================

    public void broadcast(String text) {
        for (long chat : plugin.notifyChats()) send(chat, text);
    }

    public void broadcastBlocking(String text) {
        for (long chat : plugin.notifyChats()) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(base + "sendMessage"))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form("chat_id", Long.toString(chat), "text", text)))
                        .build();
                http.send(req, HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {
            }
        }
    }

    public void send(long chatId, String text) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (String part : split(text)) {
            chain = chain.thenCompose(v -> post(chatId, part));
        }
    }

    private CompletableFuture<Void> post(long chatId, String text) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "sendMessage"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(
                        "chat_id", Long.toString(chatId),
                        "text", text,
                        "disable_web_page_preview", "true")))
                .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                .<Void>handle((r, e) -> {
                    if (e != null) {
                        plugin.getLogger().warning("Telegram: не удалось отправить сообщение (" + e.getClass().getSimpleName() + ")");
                    } else if (r.statusCode() != 200) {
                        plugin.getLogger().warning("Telegram: sendMessage HTTP " + r.statusCode() + " (чат " + chatId + ")");
                    }
                    return null;
                });
    }

    private static List<String> split(String text) {
        List<String> parts = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            parts.add("(пусто)");
            return parts;
        }
        for (int i = 0; i < text.length(); i += 4000) {
            parts.add(text.substring(i, Math.min(text.length(), i + 4000)));
        }
        return parts;
    }

    private static String form(String... kv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(kv[i], StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(kv[i + 1], StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private void registerMenu() {
        try {
            String[][] cmds = {
                    {"online", "Кто на сервере"},
                    {"status", "TPS, память, аптайм"},
                    {"say", "Сообщение в игровой чат"},
                    {"kick", "Кикнуть игрока"},
                    {"ban", "Забанить игрока"},
                    {"unban", "Разбанить игрока"},
                    {"whitelist", "Белый список"},
                    {"cmd", "Консольная команда"},
                    {"notify", "Вкл/выкл уведомления"},
                    {"exclude", "Исключения уведомлений"},
                    {"save", "Сохранить мир"},
                    {"stop", "Остановить сервер"},
                    {"help", "Справка"}
            };
            JsonArray arr = new JsonArray();
            for (String[] c : cmds) {
                JsonObject o = new JsonObject();
                o.addProperty("command", c[0]);
                o.addProperty("description", c[1]);
                arr.add(o);
            }
            JsonObject body = new JsonObject();
            body.add("commands", arr);
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "setMyCommands"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            http.sendAsync(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
        }
    }

    // ================= получение =================

    private void pollLoop() {
        skipPending();
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(base + "getUpdates?timeout=30&offset=" + offset))
                        .timeout(Duration.ofSeconds(45))
                        .GET()
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
                if (!root.has("ok") || !root.get("ok").getAsBoolean()) {
                    plugin.getLogger().warning("Telegram API вернул ошибку (HTTP " + resp.statusCode()
                            + "). Проверьте bot-token; 409 = бот запущен ещё где-то.");
                    sleepQuiet(10_000);
                    continue;
                }
                failCount = 0;
                for (JsonElement el : root.getAsJsonArray("result")) {
                    JsonObject upd = el.getAsJsonObject();
                    offset = upd.get("update_id").getAsLong() + 1;
                    if (upd.has("message")) {
                        try {
                            handleMessage(upd.getAsJsonObject("message"));
                        } catch (Exception ex) {
                            plugin.getLogger().warning("Ошибка обработки сообщения: " + ex);
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (!running) return;
                failCount++;
                if (failCount == 1 || failCount % 12 == 0) {
                    plugin.getLogger().warning("Telegram: " + e.getClass().getSimpleName() + ", повтор через 10 с");
                    if (e instanceof java.net.http.HttpTimeoutException || e instanceof java.net.ConnectException) {
                        plugin.getLogger().warning("Сервер не может подключиться к Telegram (api.telegram.org недоступен: блокировка, "
                                + "фаервол или IPv6). Укажите proxy или api-url в config.yml и сделайте /tgbot reload.");
                    }
                }
                sleepQuiet(10_000);
            }
        }
    }

    /** Пропускаем сообщения, накопившиеся, пока сервер был выключен. */
    private void skipPending() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "getUpdates?offset=-1&timeout=0"))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            JsonArray res = root.getAsJsonArray("result");
            if (res != null && res.size() > 0) {
                offset = res.get(0).getAsJsonObject().get("update_id").getAsLong() + 1;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
        }
    }

    private void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ================= команды =================

    private void sync(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

    private void handleMessage(JsonObject m) {
        if (!m.has("text") || !m.has("from") || !m.has("chat")) return;
        long chatId = m.getAsJsonObject("chat").get("id").getAsLong();
        long userId = m.getAsJsonObject("from").get("id").getAsLong();
        String text = m.get("text").getAsString().trim();
        if (!text.startsWith("/")) return;

        // защита от выполнения устаревших команд
        long date = m.has("date") ? m.get("date").getAsLong() : 0;
        if (date > 0 && System.currentTimeMillis() / 1000 - date > 120) return;

        String[] parts = text.split("\\s+", 2);
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        int at = cmd.indexOf('@');
        if (at > 0) cmd = cmd.substring(0, at);
        String args = parts.length > 1 ? parts[1].trim() : "";

        if (!plugin.isAdmin(userId)) {
            if (cmd.equals("/start") || cmd.equals("/id")) {
                send(chatId, "Ваш Telegram ID: " + userId
                        + "\nВпишите его в admins в config.yml плагина и выполните /tgbot reload.");
            }
            return;
        }

        plugin.getLogger().info("Telegram [" + userId + "]: " + text);
        handleCommand(chatId, cmd, args);
    }

    private void handleCommand(long chatId, String cmd, String args) {
        switch (cmd) {
            case "/start", "/help" -> send(chatId, HELP);
            case "/online" -> sync(() -> {
                Collection<? extends Player> players = Bukkit.getOnlinePlayers();
                String names = players.stream().map(Player::getName).sorted().collect(Collectors.joining(", "));
                send(chatId, "👥 Онлайн: " + players.size() + "/" + Bukkit.getMaxPlayers()
                        + (players.isEmpty() ? "" : "\n" + names));
            });
            case "/status" -> sync(() -> send(chatId, status()));
            case "/say" -> {
                if (args.isEmpty()) {
                    send(chatId, "Использование: /say <текст>");
                } else {
                    String line = args.replaceAll("[\\r\\n]+", " ");
                    sync(() -> Bukkit.broadcast(Component.text("[Telegram] " + line)));
                    send(chatId, "✅ Отправлено в игровой чат");
                }
            }
            case "/kick", "/ban", "/unban", "/whitelist" -> {
                if (args.isEmpty()) {
                    send(chatId, "Использование: " + cmd + " <аргументы>");
                } else {
                    runConsole(chatId, cmd.substring(1) + " " + args);
                }
            }
            case "/save" -> runConsole(chatId, "save-all");
            case "/cmd" -> {
                if (!plugin.getConfig().getBoolean("allow-console-commands", true)) {
                    send(chatId, "⛔ /cmd отключена в config.yml");
                } else if (args.isEmpty()) {
                    send(chatId, "Использование: /cmd <команда>");
                } else {
                    runConsole(chatId, args);
                }
            }
            case "/notify" -> {
                if (args.equalsIgnoreCase("on") || args.equalsIgnoreCase("off")) {
                    plugin.setNotifyEnabled(args.equalsIgnoreCase("on"));
                }
                send(chatId, "🔔 Уведомления о входе/выходе: " + (plugin.isNotifyEnabled() ? "включены" : "выключены"));
            }
            case "/exclude" -> handleExclude(chatId, args);
            case "/stop" -> {
                if (args.equalsIgnoreCase("confirm")) {
                    send(chatId, "⏹ Останавливаю сервер...");
                    Bukkit.getScheduler().runTaskLater(plugin, Bukkit::shutdown, 20L);
                } else {
                    send(chatId, "Для остановки сервера отправьте: /stop confirm");
                }
            }
            default -> send(chatId, "Неизвестная команда. /help — список команд.");
        }
    }

    private void handleExclude(long chatId, String args) {
        String[] a = args.isEmpty() ? new String[0] : args.split("\\s+");
        if (a.length == 0 || a[0].equalsIgnoreCase("list")) {
            List<String> l = plugin.excludedList();
            send(chatId, l.isEmpty() ? "Список исключений пуст." : "🔕 Исключения:\n" + String.join("\n", l));
        } else if (a.length >= 2 && a[0].equalsIgnoreCase("add")) {
            send(chatId, plugin.addExcluded(a[1]) ? "✅ Добавлен в исключения: " + a[1] : "Уже в списке.");
        } else if (a.length >= 2 && a[0].equalsIgnoreCase("remove")) {
            send(chatId, plugin.removeExcluded(a[1]) ? "✅ Удалён из исключений: " + a[1] : "Такого нет в списке.");
        } else {
            send(chatId, "Использование: /exclude list | add <игрок> | remove <игрок>\nМаска: Bot_*");
        }
    }

    private void runConsole(long chatId, String command) {
        String cleaned = command.replaceAll("[\\r\\n]+", " ").trim();
        if (cleaned.startsWith("/")) cleaned = cleaned.substring(1);
        final String c = cleaned;
        sync(() -> {
            List<String> out = new ArrayList<>();
            var sender = Bukkit.createCommandSender(
                    comp -> out.add(PlainTextComponentSerializer.plainText().serialize(comp)));
            boolean ok;
            try {
                ok = Bukkit.dispatchCommand(sender, c);
            } catch (Exception e) {
                send(chatId, "❌ Ошибка: " + e.getMessage());
                return;
            }
            String result = String.join("\n", out).trim();
            if (result.isEmpty()) {
                result = ok ? "✅ Выполнено: /" + c : "❌ Команда не найдена или не выполнена: /" + c;
            }
            send(chatId, result);
        });
    }

    private String status() {
        double[] tps = Bukkit.getTPS();
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / 1048576;
        long max = rt.maxMemory() / 1048576;
        long up = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        return String.format(Locale.ROOT,
                "📊 %s %s%nTPS: %.1f / %.1f / %.1f%nMSPT: %.1f%nRAM: %d / %d МБ%nОнлайн: %d/%d%nАптайм: %dч %dм",
                Bukkit.getName(), Bukkit.getMinecraftVersion(),
                Math.min(20.0, tps[0]), Math.min(20.0, tps[1]), Math.min(20.0, tps[2]),
                Bukkit.getAverageTickTime(),
                used, max,
                Bukkit.getOnlinePlayers().size(), Bukkit.getMaxPlayers(),
                up / 3600, (up % 3600) / 60);
    }
}
