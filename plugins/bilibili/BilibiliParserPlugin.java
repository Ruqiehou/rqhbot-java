package bilibili;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rqhbot.plugins.PluginPaths;
import com.rqhbot.sdk.BotClient;
import com.rqhbot.sdk.BotExecutor;
import com.rqhbot.sdk.pluginsystem.PluginBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B站视频解析插件 - 自动识别 BV/AV 号并获取视频信息
 */
public class BilibiliParserPlugin extends PluginBase {

    private static final Logger logger = LoggerFactory.getLogger(BilibiliParserPlugin.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final HttpClient httpClient = HttpClient.newHttpClient();

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
    private static final String REFERER = "https://www.bilibili.com/";

    private static final Pattern BV_PATTERN = Pattern.compile("BV([a-zA-Z0-9]{10})");
    private static final Pattern AV_PATTERN = Pattern.compile("[Aa][Vv](\\d+)");
    private static final Pattern SHORT_URL_PATTERN = Pattern.compile("b23\\.tv/([a-zA-Z0-9]+)");

    public BilibiliParserPlugin() {
        this.name = "bilibili-parser";
        this.version = "1.0.0";
        this.description = "B站视频解析 - 自动识别 BV/AV 号并获取视频信息";
        this.author = "rqh";
        this.enabled = true;
    }

    @Override
    public CompletableFuture<Void> onLoad(BotClient bot) {
        logger.info("插件 {} 已加载", name);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> onUnload(BotClient bot) {
        logger.info("插件 {} 已卸载", name);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> onMessage(BotClient bot, Map<String, Object> message) {
        String rawMessage = ((String) message.getOrDefault("raw_message", "")).trim();
        if (rawMessage.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        // 清除 CQ 码后提取纯文本
        String text = rawMessage.replaceAll("\\[CQ:[^\\]]+\\]", "").trim();

        if ("bilibili帮助".equals(text) || "B站帮助".equals(text) || "b站帮助".equals(text)) {
            sendMessage(bot, message, getHelpText());
            return CompletableFuture.completedFuture(null);
        }

        // 尝试匹配 BV 号
        Matcher bvMatcher = BV_PATTERN.matcher(text);
        if (bvMatcher.find()) {
            String videoParam = "bvid=BV" + bvMatcher.group(1);
            processVideo(bot, message, videoParam);
            return CompletableFuture.completedFuture(null);
        }

        // 尝试匹配 AV 号
        Matcher avMatcher = AV_PATTERN.matcher(text);
        if (avMatcher.find()) {
            String videoParam = "aid=" + avMatcher.group(1);
            processVideo(bot, message, videoParam);
            return CompletableFuture.completedFuture(null);
        }

        // 尝试匹配短链接
        Matcher shortMatcher = SHORT_URL_PATTERN.matcher(text);
        if (shortMatcher.find()) {
            BotExecutor.async(() -> {
                String resolved = resolveShortUrl("https://b23.tv/" + shortMatcher.group(1));
                if (resolved != null) {
                    processVideo(bot, message, resolved);
                }
            });
        }

        return CompletableFuture.completedFuture(null);
    }

    /**
     * 解析 B 站短链接，返回 video 参数（bvid=xxx 或 aid=xxx）
     */
    private String resolveShortUrl(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .method("GET", HttpRequest.BodyPublishers.noBody())
                    .header("User-Agent", USER_AGENT)
                    .build();

            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            // 302 重定向中提取 Location
            if (response.statusCode() == 302 || response.previousResponse().isPresent()) {
                URI location = response.uri();
                String locationStr = location.toString();
                Matcher bvMatcher = Pattern.compile("video/(?:av(\\d+)|BV([a-zA-Z0-9]{10}))").matcher(locationStr);
                if (bvMatcher.find()) {
                    if (bvMatcher.group(1) != null) {
                        return "aid=" + bvMatcher.group(1);
                    } else {
                        return "bvid=" + bvMatcher.group(2);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("解析短链接失败：{}", e.getMessage());
        }
        return null;
    }

    /**
     * 获取视频信息并发送
     */
    private void processVideo(BotClient bot, Map<String, Object> message, String videoParam) {
        BotExecutor.async(() -> {
            try {
                String apiUrl = "https://api.bilibili.com/x/web-interface/view?" + videoParam;
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl))
                        .header("User-Agent", USER_AGENT)
                        .header("Referer", REFERER)
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    sendMessage(bot, message, "获取视频信息失败，请稍后再试");
                    return;
                }

                JsonNode root = objectMapper.readTree(response.body());
                if (root.has("code") && root.get("code").asInt() != 0) {
                    sendMessage(bot, message, "视频不存在或接口异常");
                    return;
                }

                JsonNode data = root.get("data");
                if (data == null) {
                    sendMessage(bot, message, "未获取到视频信息");
                    return;
                }

                String title = data.path("title").asText("");
                String desc = data.path("desc").asText("");
                if (desc.length() > 100) {
                    desc = desc.substring(0, 100) + "...";
                }
                String bvid = data.path("bvid").asText("");
                long aid = data.path("aid").asLong(0);

                JsonNode stat = data.path("stat");
                long views = stat.path("view").asLong(0);
                long likes = stat.path("like").asLong(0);
                long favorites = stat.path("favorite").asLong(0);
                long coins = stat.path("coin").asLong(0);

                String infoText = String.format(
                        "\n[%s]\n简介: %s\n播放: %d | 点赞: %d\n收藏: %d | 投币: %d\n链接: https://www.bilibili.com/video/%s",
                        title, desc, views, likes, favorites, coins,
                        bvid.isEmpty() ? "av" + aid : bvid
                );

                // 下载封面图
                String coverUrl = data.path("pic").asText("");
                if (coverUrl.startsWith("//")) {
                    coverUrl = "https:" + coverUrl;
                }

                if (!coverUrl.isEmpty()) {
                    String finalCoverUrl = coverUrl;
                    String coverPath = downloadCover(finalCoverUrl, bvid.isEmpty() ? "av" + aid : bvid);
                    if (coverPath != null) {
                        sendImageWithText(bot, message, coverPath, infoText);
                        return;
                    }
                }

                sendMessage(bot, message, infoText);
            } catch (Exception e) {
                logger.error("处理B站视频出错：{}", e.getMessage(), e);
                sendMessage(bot, message, "处理视频信息时发生错误");
            }
        });
    }

    /**
     * 下载封面图到插件数据目录，发送后由 sendImageWithText 清理。
     */
    private String downloadCover(String coverUrl, String videoId) {
        try {
            Path tmpDir = PluginPaths.dataDir("bilibili").resolve("tmp");
            Files.createDirectories(tmpDir);
            String safeId = videoId.replaceAll("[^a-zA-Z0-9]", "_");
            Path coverPath = tmpDir.resolve("bili_cover_" + safeId + "_" + System.currentTimeMillis() + ".jpg");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(coverUrl))
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", REFERER)
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                Files.write(coverPath, response.body());
                return coverPath.toAbsolutePath().toString();
            }
        } catch (Exception e) {
            logger.error("下载封面图失败：{}", e.getMessage());
        }
        return null;
    }

    private String getHelpText() {
        return """
📺 B站解析帮助

自动识别消息中的：
- BV 号（如 BV1xx411c7mD）
- AV 号（如 av170001）
- b23.tv 短链接

发送含以上内容的消息即可解析视频信息与封面。
命令：
- bilibili帮助 / B站帮助
""";
    }

    private void sendMessage(BotClient bot, Map<String, Object> message, String content) {
        String messageType = (String) message.get("message_type");
        long userId = getLong(message, "user_id");
        long groupId = getLong(message, "group_id");

        BotExecutor.async(() -> {
            try {
                if ("group".equals(messageType)) {
                    bot.getApi().sendGroupMessage(groupId, content);
                } else {
                    bot.getApi().sendPrivateMessage(userId, content);
                }
            } catch (Exception e) {
                logger.error("发送消息失败：{}", e.getMessage());
            }
        });
    }

    private void sendImageWithText(BotClient bot, Map<String, Object> message, String imagePath, String text) {
        String messageType = (String) message.get("message_type");
        long userId = getLong(message, "user_id");
        long groupId = getLong(message, "group_id");

        BotExecutor.async(() -> {
            try {
                if ("group".equals(messageType)) {
                    bot.getApi().sendGroupImage(groupId, imagePath, null, null, text).join();
                } else {
                    bot.getApi().sendPrivateImage(userId, imagePath, null, null, text).join();
                }
            } catch (Exception e) {
                logger.error("发送图片消息失败：{}", e.getMessage());
            } finally {
                try {
                    Files.deleteIfExists(Path.of(imagePath));
                } catch (Exception e) {
                    logger.debug("清理临时封面失败：{}", e.getMessage());
                }
            }
        });
    }

    private long getLong(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String text) {
            try { return Long.parseLong(text); } catch (NumberFormatException e) { return 0L; }
        }
        return 0L;
    }
}
