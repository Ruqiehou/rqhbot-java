package kinkus;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rqhbot.plugins.PluginPaths;
import com.rqhbot.sdk.BotClient;
import com.rqhbot.sdk.BotExecutor;
import com.rqhbot.sdk.pluginsystem.PluginBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Kinkus 综合功能插件 - 运势、签到、字典、天气、新闻等
 */
public class KinkusPlugin extends PluginBase {

    private static final Logger logger = LoggerFactory.getLogger(KinkusPlugin.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static final Random random = new Random();

    // 用户数据落盘间隔（秒）
    private static final long FLUSH_INTERVAL_SECONDS = 30;

    // 数据目录
    private Path dataDir;
    private Path assetsDir;

    // 用户数据内存缓存：避免每条消息都读写文件造成的写放大与并发竞争
    private final Map<Long, Map<String, Object>> userCache = new ConcurrentHashMap<>();
    private final Set<Long> dirtyUsers = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService flushScheduler;

    // 外置配置（data/kinkus/config.json）
    private Set<Long> adminUsers = Set.of();
    private Set<Long> restrictedGroups = Set.of();

    // 缓存数据
    private List<String> fortunes = List.of();
    private Map<String, String> dictionary = Map.of();
    private String helpText = "";
    private Map<String, Map<String, Object>> shopItems = Map.of();

    public KinkusPlugin() {
        this.name = "kinkus";
        this.version = "2.0.0";
        this.description = "综合功能插件 - 运势、签到、字典、天气、新闻等";
        this.author = "rqh";
        this.enabled = true;
    }

    @Override
    public CompletableFuture<Void> onLoad(BotClient bot) {
        logger.info("插件 {} 已加载，版本：{}", name, version);
        dataDir = PluginPaths.dataDir("kinkus");
        assetsDir = PluginPaths.assetsDir("kinkus");

        try {
            Files.createDirectories(dataDir);
            Files.createDirectories(dataDir.resolve("users"));
        } catch (IOException e) {
            logger.error("创建数据目录失败：{}", e.getMessage());
        }

        // 加载资源文件
        fortunes = loadJsonList(dataDir.resolve("csys.json"), new TypeReference<>() {});
        dictionary = loadJsonMap(dataDir.resolve("csmsword.json"), new TypeReference<>() {});
        shopItems = loadJsonMap(dataDir.resolve("point_shop.json"), new TypeReference<>() {});
        helpText = loadTextFile(dataDir.resolve("help.md"));

        if (fortunes.isEmpty()) {
            fortunes = List.of("大吉大利", "诸事顺利", "心想事成", "万事如意");
        }

        // 加载外置配置（管理员 / 受限群）
        loadConfig();

        // 启动周期性落盘，合并积分与发言修改，降低磁盘写入频次
        flushScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kinkus-flush");
            t.setDaemon(true);
            return t;
        });
        flushScheduler.scheduleAtFixedRate(this::flushDirtyUsers,
                FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);

        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> onUnload(BotClient bot) {
        flushDirtyUsers();
        if (flushScheduler != null) {
            flushScheduler.shutdownNow();
        }
        logger.info("插件 {} 已卸载", name);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> onMessage(BotClient bot, Map<String, Object> message) {
        String messageType = (String) message.get("message_type");
        if (!"group".equals(messageType)) {
            return CompletableFuture.completedFuture(null);
        }

        long groupId = getLong(message, "group_id");
        long userId = getLong(message, "user_id");
        String rawMessage = ((String) message.getOrDefault("raw_message", "")).trim();

        if (rawMessage.isEmpty()) return CompletableFuture.completedFuture(null);

        boolean restricted = restrictedGroups.contains(groupId);

        // 受限群关闭积分相关功能
        if (restricted && isEconomyCommand(rawMessage)) {
            sendGroupMsg(bot, groupId, "本群已关闭积分相关功能");
            return CompletableFuture.completedFuture(null);
        }

        // 更新用户发言计数（受限群不计积分，避免刷分）
        if (!restricted) {
            updateUserMessageCount(userId);
        }

        // 命令处理
        switch (rawMessage) {
            case "运势" -> handleFortune(bot, groupId);
            case "随机图" -> handleRandomImage(bot, groupId);
            case "新闻" -> handleNews(bot, groupId);
            case "签到" -> handleSignIn(bot, groupId, userId);
            case "我的积分" -> handleMyPoints(bot, groupId, userId);
            case "我的信息" -> handleMyInfo(bot, groupId, userId);
            case "积分排行" -> handlePointRank(bot, groupId);
            case "积分商城" -> handleShop(bot, groupId);
            case "签到帮助", "指南" -> handleHelp(bot, groupId);
            default -> {
                if (rawMessage.startsWith("天气预报")) {
                    handleWeatherForecast(bot, groupId, rawMessage.substring(4).trim());
                } else if (rawMessage.startsWith("天气")) {
                    handleWeather(bot, groupId, rawMessage.substring(2).trim());
                } else if (rawMessage.startsWith("正查")) {
                    handleDictLookup(bot, groupId, rawMessage.substring(2).trim(), "forward");
                } else if (rawMessage.startsWith("反查")) {
                    handleDictLookup(bot, groupId, rawMessage.substring(2).trim(), "reverse");
                } else if (rawMessage.startsWith("粗查")) {
                    handleDictLookup(bot, groupId, rawMessage.substring(2).trim(), "fuzzy");
                } else if (rawMessage.startsWith("转写")) {
                    handleNumberToChinese(bot, groupId, rawMessage.substring(2).trim());
                } else if (rawMessage.startsWith("兑换")) {
                    handleRedeem(bot, groupId, userId, rawMessage.substring(2).trim());
                } else if (rawMessage.startsWith("设置名字")) {
                    handleSetName(bot, groupId, userId, rawMessage.substring(4).trim());
                } else if (rawMessage.startsWith("爬")) {
                    handleSearch(bot, groupId, rawMessage.substring(1).trim());
                }
            }
        }

        return CompletableFuture.completedFuture(null);
    }

    // ==================== 功能处理 ====================

    private void handleFortune(BotClient bot, long groupId) {
        String fortune = fortunes.get(random.nextInt(fortunes.size()));
        String imagePath = getRandomImagePath();
        if (imagePath != null) {
            sendGroupImageWithText(bot, groupId, imagePath, fortune);
        } else {
            sendGroupMsg(bot, groupId, fortune);
        }
    }

    private void handleRandomImage(BotClient bot, long groupId) {
        String imagePath = getRandomImagePath();
        if (imagePath != null) {
            sendGroupImageWithText(bot, groupId, imagePath, "这是一张随机图片");
        } else {
            sendGroupMsg(bot, groupId, "暂无图片");
        }
    }

    private void handleNews(BotClient bot, long groupId) {
        BotExecutor.async(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("https://api.52vmy.cn/api/wl/60s/new"))
                        .header("User-Agent", "Mozilla/5.0")
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                JsonNode root = objectMapper.readTree(response.body());

                if (root.has("code") && root.get("code").asInt() == 200) {
                    JsonNode data = root.get("data");
                    if (data != null && data.isArray()) {
                        StringBuilder sb = new StringBuilder("📰 60秒读懂世界\n\n");
                        int count = 0;
                        for (JsonNode item : data) {
                            if (count >= 10) break;
                            if (item.has("title")) {
                                sb.append(count + 1).append(". ").append(item.get("title").asText()).append("\n");
                                count++;
                            }
                        }
                        sendGroupMsg(bot, groupId, sb.toString());
                        return;
                    }
                }
                sendGroupMsg(bot, groupId, "获取新闻失败");
            } catch (Exception e) {
                logger.error("获取新闻失败：{}", e.getMessage());
                sendGroupMsg(bot, groupId, "获取新闻时出错");
            }
        });
    }

    private void handleWeather(BotClient bot, long groupId, String city) {
        if (city.isEmpty()) {
            sendGroupMsg(bot, groupId, "请输入城市名，例如：天气 北京");
            return;
        }

        BotExecutor.async(() -> {
            try {
                String url = "https://api.52vmy.cn/api/query/tian/three?city=" +
                        URLEncoder.encode(city, StandardCharsets.UTF_8) + "&type=weather";
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", "Mozilla/5.0")
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                sendGroupMsg(bot, groupId, formatWeather(city, response.body()));
            } catch (Exception e) {
                logger.error("查询天气失败：{}", e.getMessage());
                sendGroupMsg(bot, groupId, "查询天气时出错");
            }
        });
    }

    /**
     * 将第三方天气接口返回的 JSON 解析为可读文本，避免直接向群里发送原始 JSON。
     */
    private String formatWeather(String city, String body) {
        String header = "📍 " + city + " 天气\n";
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.path("data");
            if (data.isMissingNode() || data.isNull() || !data.isObject()) {
                return header + "暂未获取到数据";
            }
            String text = firstText(data, "weather", "text_day", "weather_day", "text", "type");
            String high = firstText(data, "temp_max", "high", "temp_high", "temperature", "wendu");
            String low = firstText(data, "temp_min", "low", "temp_low");
            String wind = firstText(data, "wind", "wc_day", "wind_dir", "wdir");
            String humidity = firstText(data, "humidity", "shidu");

            StringBuilder sb = new StringBuilder(header);
            if (!text.isEmpty()) sb.append("天气：").append(text).append("\n");
            if (!high.isEmpty() || !low.isEmpty()) {
                sb.append("气温：").append(low.isEmpty() ? "-" : low)
                  .append(" ~ ").append(high.isEmpty() ? "-" : high).append("\n");
            }
            if (!wind.isEmpty()) sb.append("风况：").append(wind).append("\n");
            if (!humidity.isEmpty()) sb.append("湿度：").append(humidity).append("\n");

            if (sb.length() == header.length()) {
                return header + "暂未获取到数据";
            }
            return sb.toString().trim();
        } catch (Exception e) {
            logger.error("解析天气数据失败：{}", e.getMessage());
            return header + "数据解析失败";
        }
    }

    private String firstText(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (!value.isMissingNode() && !value.isNull()) {
                String text = value.asText("");
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return "";
    }

    private void handleWeatherForecast(BotClient bot, long groupId, String city) {
        if (city.isEmpty()) {
            sendGroupMsg(bot, groupId, "请输入城市名，例如：天气预报 北京");
            return;
        }

        BotExecutor.async(() -> {
            try {
                String url = "https://api.52vmy.cn/api/query/tian/three?city=" +
                        URLEncoder.encode(city, StandardCharsets.UTF_8) + "&type=forecast";
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", "Mozilla/5.0")
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                JsonNode root = objectMapper.readTree(response.body());

                if (root.has("code") && root.get("code").asInt() == 200) {
                    JsonNode data = root.get("data");
                    StringBuilder msg = new StringBuilder("📍 " + city + " 未来天气预报\n");
                    if (data.has("forecast") && data.get("forecast").isArray()) {
                        JsonNode forecast = data.get("forecast");
                        for (int i = 0; i < Math.min(3, forecast.size()); i++) {
                            JsonNode day = forecast.get(i);
                            msg.append("第").append(i + 1).append("天：");
                            msg.append(day.has("text_day") ? day.get("text_day").asText() : "未知");
                            msg.append(" ");
                            msg.append(day.has("temp_max") ? day.get("temp_max").asText() + "°C" : "");
                            msg.append("\n");
                        }
                    }
                    msg.append("数据来源：52vmy API");
                    sendGroupMsg(bot, groupId, msg.toString());
                } else {
                    sendGroupMsg(bot, groupId, "天气预报数据加载失败");
                }
            } catch (Exception e) {
                logger.error("查询天气预报失败：{}", e.getMessage());
                sendGroupMsg(bot, groupId, "查询天气预报时出错");
            }
        });
    }

    private void handleSearch(BotClient bot, long groupId, String query) {
        if (query.isEmpty()) return;
        BotExecutor.async(() -> {
            try {
                String url = "https://www.baidu.com/s?wd=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
                sendGroupMsg(bot, groupId, "🔍 搜索「" + query + "」\n" + url);
            } catch (Exception e) {
                sendGroupMsg(bot, groupId, "搜索失败");
            }
        });
    }

    private void handleDictLookup(BotClient bot, long groupId, String word, String mode) {
        if (word.isEmpty()) {
            sendGroupMsg(bot, groupId, "请输入要查询的词");
            return;
        }

        switch (mode) {
            case "forward" -> {
                if (dictionary.containsKey(word)) {
                    sendGroupMsg(bot, groupId, word + " 是：" + dictionary.get(word));
                } else {
                    sendGroupMsg(bot, groupId, "抱歉，没有找到 " + word);
                }
            }
            case "reverse" -> {
                List<String> results = new ArrayList<>();
                dictionary.forEach((k, v) -> { if (v.equals(word)) results.add(k); });
                if (!results.isEmpty()) {
                    sendGroupMsg(bot, groupId, word + " 可能是：" + String.join(", ", results));
                } else {
                    sendGroupMsg(bot, groupId, "抱歉，没有找到 " + word + " 的单词");
                }
            }
            case "fuzzy" -> {
                List<String> matches = new ArrayList<>();
                dictionary.forEach((k, v) -> { if (k.contains(word)) matches.add(k); });
                if (!matches.isEmpty()) {
                    StringBuilder sb = new StringBuilder("找到 " + matches.size() + " 个包含【" + word + "】的单词：\n");
                    for (int i = 0; i < Math.min(matches.size(), 20); i++) {
                        sb.append(i + 1).append(". ").append(matches.get(i)).append("\n");
                    }
                    sendGroupMsg(bot, groupId, sb.toString());
                } else {
                    sendGroupMsg(bot, groupId, "未找到包含【" + word + "】的单词");
                }
            }
        }
    }

    private void handleNumberToChinese(BotClient bot, long groupId, String numStr) {
        if (numStr.isEmpty()) {
            sendGroupMsg(bot, groupId, "请输入数字，例如：转写 12345");
            return;
        }
        try {
            String chinese = NumberToChineseConverter.convert(numStr);
            sendGroupMsg(bot, groupId, numStr + " 转写为 " + chinese);
        } catch (Exception e) {
            sendGroupMsg(bot, groupId, "转换失败：" + e.getMessage());
        }
    }

    // ==================== 签到积分系统 ====================

    private void handleSignIn(BotClient bot, long groupId, long userId) {
        Map<String, Object> userData = getUserData(userId);

        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        int points;
        String username;
        Object totalPoints;
        synchronized (userData) {
            if (today.equals(userData.getOrDefault("last_sign_in", ""))) {
                sendGroupMsg(bot, groupId, "你今天已经签到过了");
                return;
            }
            points = 88 + random.nextInt(99913);
            userData.put("points", ((Number) userData.getOrDefault("points", 0)).intValue() + points);
            userData.put("last_sign_in", today);
            username = (String) userData.getOrDefault("username", "用户" + userId);
            totalPoints = userData.get("points");
        }
        dirtyUsers.add(userId);

        String msg = String.format("签到成功！获得%d积分\n用户：%s\n当前积分：%s",
                points, username, totalPoints);

        String imagePath = getRandomImagePath();
        if (imagePath != null) {
            sendGroupImageWithText(bot, groupId, imagePath, msg);
        } else {
            sendGroupMsg(bot, groupId, msg);
        }
    }

    private void handleMyPoints(BotClient bot, long groupId, long userId) {
        Map<String, Object> userData = getUserData(userId);
        String username = (String) userData.getOrDefault("username", "用户" + userId);
        sendGroupMsg(bot, groupId, username + "，你的积分：" + userData.getOrDefault("points", 0));
    }

    private void handleMyInfo(BotClient bot, long groupId, long userId) {
        Map<String, Object> userData = getUserData(userId);
        String info = String.format("用户名：%s\n积分：%s\n状态：%s\n上次签到：%s\n发言次数：%s",
                userData.getOrDefault("username", "用户" + userId),
                userData.getOrDefault("points", 0),
                userData.getOrDefault("status", "初来乍到"),
                userData.getOrDefault("last_sign_in", "无"),
                userData.getOrDefault("message_count", 0));
        sendGroupMsg(bot, groupId, info);
    }

    private void handlePointRank(BotClient bot, long groupId) {
        // 先把缓存中的改动落盘，保证排行榜数据是最新的
        flushDirtyUsers();

        Path usersDir = dataDir.resolve("users");
        if (!Files.exists(usersDir)) {
            sendGroupMsg(bot, groupId, "暂无用户数据");
            return;
        }

        try {
            List<Map.Entry<String, Integer>> rankings = new ArrayList<>();
            try (var stream = Files.list(usersDir)) {
                stream.filter(p -> p.toString().endsWith(".json"))
                      .forEach(p -> {
                          try {
                              Map<String, Object> data = objectMapper.readValue(p.toFile(),
                                      new TypeReference<>() {});
                              String userId = p.getFileName().toString().replace("user_", "").replace(".json", "");
                              int points = ((Number) data.getOrDefault("points", 0)).intValue();
                              rankings.add(Map.entry(userId, points));
                          } catch (Exception ignored) {}
                      });
            }

            rankings.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

            StringBuilder sb = new StringBuilder("🏆 积分排行榜 🏆\n");
            for (int i = 0; i < Math.min(rankings.size(), 10); i++) {
                Map.Entry<String, Integer> entry = rankings.get(i);
                Path userFile = getUserDataFile(Long.parseLong(entry.getKey()));
                String name = "用户" + entry.getKey();
                if (Files.exists(userFile)) {
                    try {
                        Map<String, Object> data = objectMapper.readValue(userFile.toFile(), new TypeReference<>() {});
                        name = (String) data.getOrDefault("username", name);
                    } catch (Exception ignored) {}
                }
                sb.append(i + 1).append(". ").append(name).append(": ").append(entry.getValue()).append("分\n");
            }
            sendGroupMsg(bot, groupId, sb.toString());
        } catch (Exception e) {
            logger.error("读取排行榜失败：{}", e.getMessage());
            sendGroupMsg(bot, groupId, "读取排行榜失败");
        }
    }

    private void handleShop(BotClient bot, long groupId) {
        if (shopItems.isEmpty()) {
            sendGroupMsg(bot, groupId, "积分商城暂无商品");
            return;
        }
        StringBuilder sb = new StringBuilder("🏪 积分商城 🏪\n\n");
        shopItems.forEach((id, item) -> {
            sb.append(id).append(". ").append(item.get("name"))
              .append(" - ").append(item.get("price")).append("积分\n")
              .append("   ").append(item.get("description")).append("\n\n");
        });
        sb.append("发送「兑换+商品编号」来兑换，例如：兑换1");
        sendGroupMsg(bot, groupId, sb.toString());
    }

    private void handleRedeem(BotClient bot, long groupId, long userId, String itemId) {
        if (!shopItems.containsKey(itemId)) {
            sendGroupMsg(bot, groupId, "无效的商品编号");
            return;
        }

        Map<String, Object> item = shopItems.get(itemId);
        int price = ((Number) item.get("price")).intValue();
        Map<String, Object> userData = getUserData(userId);

        synchronized (userData) {
            int currentPoints = ((Number) userData.getOrDefault("points", 0)).intValue();
            if (currentPoints < price) {
                sendGroupMsg(bot, groupId, "积分不足，当前：" + currentPoints + "，需要：" + price);
                return;
            }
            userData.put("points", currentPoints - price);
        }
        dirtyUsers.add(userId);

        switch (itemId) {
            case "1" -> { // 随机图片
                String imagePath = getRandomImagePath();
                if (imagePath != null) {
                    sendGroupImageWithText(bot, groupId, imagePath,
                            "兑换成功！剩余积分：" + readPoints(userData));
                } else {
                    mutateUserData(userId, data -> data.put("points",
                            ((Number) data.getOrDefault("points", 0)).intValue() + price));
                    sendGroupMsg(bot, groupId, "图库为空，积分已退还");
                }
            }
            case "2", "4" -> { // 称号
                List<String> titles = new ArrayList<>(dictionary.keySet());
                if (!titles.isEmpty()) {
                    String title = titles.get(random.nextInt(titles.size()));
                    mutateUserData(userId, data -> data.put("status", title));
                    sendGroupMsg(bot, groupId, "兑换成功！获得称号「" + title + "」，剩余积分：" + readPoints(userData));
                } else {
                    mutateUserData(userId, data -> data.put("points",
                            ((Number) data.getOrDefault("points", 0)).intValue() + price));
                    sendGroupMsg(bot, groupId, "暂无可用称号，积分已退还");
                }
            }
            case "3" -> { // 运势
                String fortune = fortunes.get(random.nextInt(fortunes.size()));
                sendGroupMsg(bot, groupId, "今日运势：" + fortune + "，剩余积分：" + readPoints(userData));
            }
            case "5", "6" -> { // 礼包
                int bonus = itemId.equals("5") ? 1000000 + random.nextInt(49999999) : 1000 + random.nextInt(49001);
                mutateUserData(userId, data -> data.put("points",
                        ((Number) data.getOrDefault("points", 0)).intValue() + bonus));
                sendGroupMsg(bot, groupId, "获得礼包：" + bonus + "积分，当前积分：" + readPoints(userData));
            }
            default -> {
                // 未实现商品：退还已扣积分
                mutateUserData(userId, data -> data.put("points",
                        ((Number) data.getOrDefault("points", 0)).intValue() + price));
                sendGroupMsg(bot, groupId, "该商品暂未实现，积分已退还");
            }
        }
    }

    private Object readPoints(Map<String, Object> userData) {
        synchronized (userData) {
            return userData.getOrDefault("points", 0);
        }
    }

    private void handleSetName(BotClient bot, long groupId, long userId, String newName) {
        if (newName.isEmpty()) {
            sendGroupMsg(bot, groupId, "请输入新名字，例如：设置名字 快乐小天使");
            return;
        }
        mutateUserData(userId, data -> data.put("username", newName));
        sendGroupMsg(bot, groupId, "名字已更新为：" + newName);
    }

    private void handleHelp(BotClient bot, long groupId) {
        if (!helpText.isEmpty()) {
            sendGroupMsg(bot, groupId, helpText);
        } else {
            sendGroupMsg(bot, groupId, "帮助文档加载失败");
        }
    }

    // ==================== 工具方法 ====================

    private void updateUserMessageCount(long userId) {
        mutateUserData(userId, data -> {
            int count = ((Number) data.getOrDefault("message_count", 0)).intValue() + 99 + random.nextInt(901);
            int points = ((Number) data.getOrDefault("points", 0)).intValue() + 999 + random.nextInt(9101);
            data.put("message_count", count);
            data.put("points", points);
        });
    }

    // ==================== 配置与缓存 ====================

    /**
     * 加载外置配置（管理员、受限群），不存在时生成默认配置。
     */
    private void loadConfig() {
        Path configFile = dataDir.resolve("config.json");
        if (Files.exists(configFile)) {
            try {
                JsonNode root = objectMapper.readTree(configFile.toFile());
                adminUsers = readLongSet(root.get("admin_users"));
                restrictedGroups = readLongSet(root.get("restricted_groups"));
                logger.info("kinkus 配置已加载：管理员 {} 人，受限群 {} 个", adminUsers.size(), restrictedGroups.size());
            } catch (Exception e) {
                logger.error("加载 kinkus 配置失败：{}", e.getMessage());
            }
        } else {
            saveDefaultConfig(configFile);
        }
    }

    private void saveDefaultConfig(Path configFile) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.putArray("admin_users").add(2654278608L);
            root.putArray("restricted_groups").add(673906569L);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(configFile.toFile(), root);
            adminUsers = readLongSet(root.get("admin_users"));
            restrictedGroups = readLongSet(root.get("restricted_groups"));
            logger.info("已生成默认 kinkus 配置：{}", configFile);
        } catch (Exception e) {
            logger.error("生成默认 kinkus 配置失败：{}", e.getMessage());
            adminUsers = Set.of(2654278608L);
            restrictedGroups = Set.of(673906569L);
        }
    }

    private Set<Long> readLongSet(JsonNode node) {
        Set<Long> result = new LinkedHashSet<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                try {
                    result.add(item.isNumber() ? item.asLong() : Long.parseLong(item.asText().trim()));
                } catch (NumberFormatException ignored) {
                    // 跳过非法项
                }
            }
        }
        return result;
    }

    /**
     * 判断是否为积分/经济类命令（受限群关闭这些功能）。
     */
    private boolean isEconomyCommand(String raw) {
        return raw.equals("签到") || raw.equals("我的积分") || raw.equals("我的信息")
                || raw.equals("积分排行") || raw.equals("积分商城")
                || raw.startsWith("兑换") || raw.startsWith("设置名字");
    }

    /**
     * 获取用户数据（优先读内存缓存，未命中再读文件）。
     */
    private Map<String, Object> getUserData(long userId) {
        return userCache.computeIfAbsent(userId, id -> loadUserData(getUserDataFile(id)));
    }

    /**
     * 在锁保护下修改用户数据并标记为待落盘。
     */
    private void mutateUserData(long userId, Consumer<Map<String, Object>> mutator) {
        Map<String, Object> data = getUserData(userId);
        synchronized (data) {
            mutator.accept(data);
        }
        dirtyUsers.add(userId);
    }

    /**
     * 将脏用户数据批量写盘（周期性调用，卸载时也会调用）。
     */
    private void flushDirtyUsers() {
        for (Long userId : new ArrayList<>(dirtyUsers)) {
            if (!dirtyUsers.remove(userId)) {
                continue;
            }
            Map<String, Object> data = userCache.get(userId);
            if (data == null) {
                continue;
            }
            synchronized (data) {
                saveUserData(getUserDataFile(userId), data);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadUserData(Path file) {
        if (Files.exists(file)) {
            try {
                return objectMapper.readValue(file.toFile(), new TypeReference<>() {});
            } catch (Exception e) {
                logger.error("读取用户数据失败：{}", e.getMessage());
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("points", 0);
        data.put("last_sign_in", "");
        data.put("status", "初来乍到");
        data.put("message_count", 0);
        data.put("username", "");
        return data;
    }

    private void saveUserData(Path file, Map<String, Object> data) {
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), data);
        } catch (Exception e) {
            logger.error("保存用户数据失败：{}", e.getMessage());
        }
    }

    private Path getUserDataFile(long userId) {
        return dataDir.resolve("users").resolve("user_" + userId + ".json");
    }

    private String getRandomImagePath() {
        Path imageDir = assetsDir.resolve("tup");
        if (!Files.exists(imageDir)) return null;

        try {
            List<Path> images = new ArrayList<>();
            try (var stream = Files.list(imageDir)) {
                stream.filter(p -> {
                    String name = p.getFileName().toString().toLowerCase();
                    return name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".gif") || name.endsWith(".jpeg");
                }).forEach(images::add);
            }
            if (images.isEmpty()) return null;
            return images.get(random.nextInt(images.size())).toAbsolutePath().toString();
        } catch (Exception e) {
            logger.error("获取随机图片失败：{}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T loadJsonList(Path file, TypeReference<T> ref) {
        if (Files.exists(file)) {
            try {
                return objectMapper.readValue(file.toFile(), ref);
            } catch (Exception e) {
                logger.error("加载 {} 失败：{}", file, e.getMessage());
            }
        }
        return (T) List.of();
    }

    @SuppressWarnings("unchecked")
    private <T> T loadJsonMap(Path file, TypeReference<T> ref) {
        if (Files.exists(file)) {
            try {
                return objectMapper.readValue(file.toFile(), ref);
            } catch (Exception e) {
                logger.error("加载 {} 失败：{}", file, e.getMessage());
            }
        }
        return (T) Map.of();
    }

    private String loadTextFile(Path file) {
        if (Files.exists(file)) {
            try {
                return Files.readString(file);
            } catch (Exception e) {
                logger.error("加载 {} 失败：{}", file, e.getMessage());
            }
        }
        return "";
    }

    private long getLong(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0L; }
        }
        return 0L;
    }

    private void sendGroupMsg(BotClient bot, long groupId, String text) {
        BotExecutor.async(() -> {
            try { bot.getApi().sendGroupMessage(groupId, text); }
            catch (Exception e) { logger.error("发送群消息失败：{}", e.getMessage()); }
        });
    }

    private void sendGroupImageWithText(BotClient bot, long groupId, String imagePath, String text) {
        BotExecutor.async(() -> {
            try { bot.getApi().sendGroupImage(groupId, imagePath, null, null, text); }
            catch (Exception e) { logger.error("发送图片失败：{}", e.getMessage()); }
        });
    }
}





