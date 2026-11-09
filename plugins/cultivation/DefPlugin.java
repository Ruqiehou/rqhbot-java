package cultivation;

import com.rqhbot.plugins.PluginPaths;
import com.rqhbot.sdk.BotClient;
import com.rqhbot.sdk.BotExecutor;
import com.rqhbot.sdk.pluginsystem.PluginBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * DEF 修仙插件 - 开灵、打坐、突破、排行榜等功能
 */
public class DefPlugin extends PluginBase {
    
    private static final Logger logger = LoggerFactory.getLogger(DefPlugin.class);
    
    // 数据目录
    private final String dataDir = PluginPaths.dataDir("def").toString();
    
    // 关键词
    private final List<String> rankKeywords = List.of("修仙排行榜", "修仙榜单", "修仙排名");
    private final List<String> statsKeywords = List.of("修仙统计", "修仙数据", "我的修为", "境界", "修为");
    private final List<String> cultivateKeywords = List.of("开灵", "打坐", "突破", "修炼", "修行", "修仙");
    private final List<String> helpKeywords = List.of("修仙帮助", "def帮助");
    
    // 修仙系统
    private final CultivationSystem cultivationSystem = new CultivationSystem();
    
    public DefPlugin() {
        this.name = "def";
        this.version = "2.0.0";
        this.description = "DEF 修仙插件 - 开灵、打坐、突破、排行榜等功能";
        this.author = "rqh";
        this.enabled = true;
    }
    
    @Override
    public CompletableFuture<Void> onLoad(BotClient bot) {
        logger.info("插件 {} 已加载", name);
        logger.info("插件版本：{}", version);
        
        // 创建数据目录
        try {
            Files.createDirectories(Paths.get(dataDir));
        } catch (IOException e) {
            logger.error("创建数据目录失败：{}", e.getMessage());
        }
        
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public CompletableFuture<Void> onUnload(BotClient bot) {
        logger.info("插件 {} 卸载中", name);
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public CompletableFuture<Void> onMessage(BotClient bot, Map<String, Object> message) {
        String messageType = (String) message.get("message_type");
        
        if ("group".equals(messageType)) {
            handleGroupMessage(bot, message);
        } else if ("private".equals(messageType)) {
            handlePrivateMessage(bot, message);
        }
        
        return CompletableFuture.completedFuture(null);
    }
    
    private void handleGroupMessage(BotClient bot, Map<String, Object> message) {
        String rawMessage = ((String) message.getOrDefault("raw_message", "")).trim();
        
        processKeywords(bot, message, rawMessage);
    }
    
    private void handlePrivateMessage(BotClient bot, Map<String, Object> message) {
        String rawMessage = ((String) message.getOrDefault("raw_message", "")).trim();
        
        processKeywords(bot, message, rawMessage);
    }
    
    private void processKeywords(BotClient bot, Map<String, Object> message, String rawMessage) {
        // 帮助（最高优先级）
        if (helpKeywords.stream().anyMatch(rawMessage::contains)) {
            if (rawMessage.contains("帮助")) {
                sendMessage(bot, message, getHelpText());
                return;
            }
        }
        
        // 排行榜（优先级最高）
        if (rankKeywords.stream().anyMatch(rawMessage::contains)) {
            if (rawMessage.contains("排行榜") || rawMessage.contains("榜单") || rawMessage.contains("排名")) {
                handleRanking(bot, message);
                return;
            }
        }
        
        // 统计功能
        if (statsKeywords.stream().anyMatch(rawMessage::contains)) {
            if (rawMessage.contains("统计") || rawMessage.contains("数据") || rawMessage.contains("修为") || rawMessage.contains("境界")) {
                handleUserStats(bot, message);
                return;
            }
        }
        
        // 修炼功能（最低优先级）
        if (cultivateKeywords.stream().anyMatch(rawMessage::contains)) {
            if (rawMessage.contains("开灵")) {
                handleOpenSoul(bot, message);
            } else if (rawMessage.contains("打坐")) {
                handleMeditate(bot, message);
            } else if (rawMessage.contains("突破")) {
                handleBreakthrough(bot, message);
            } else if (rawMessage.contains("修炼") || rawMessage.contains("修行") || rawMessage.contains("修仙")) {
                handleCultivate(bot, message);
            }
        }
    }
    
    private void handleOpenSoul(BotClient bot, Map<String, Object> message) {
        BotExecutor.async(() -> {
            try {
                long userId = getLong(message, "user_id");
                String username = getUsername(bot, message, userId);
                Player player = cultivationSystem.loadPlayer(userId, username);
                String result = player.openSoul();
                cultivationSystem.savePlayer(player);
                sendMessage(bot, message, result);
            } catch (Exception e) {
                logger.error("开灵失败", e);
                sendMessage(bot, message, "开灵失败：" + e.getMessage());
            }
        });
    }
    
    private void handleMeditate(BotClient bot, Map<String, Object> message) {
        BotExecutor.async(() -> {
            try {
                long userId = getLong(message, "user_id");
                String username = getUsername(bot, message, userId);
                Player player = cultivationSystem.loadPlayer(userId, username);
                String result = player.meditate();
                cultivationSystem.savePlayer(player);
                sendMessage(bot, message, result);
            } catch (Exception e) {
                logger.error("打坐失败", e);
                sendMessage(bot, message, "打坐失败：" + e.getMessage());
            }
        });
    }
    
    private void handleBreakthrough(BotClient bot, Map<String, Object> message) {
        BotExecutor.async(() -> {
            try {
                long userId = getLong(message, "user_id");
                String username = getUsername(bot, message, userId);
                Player player = cultivationSystem.loadPlayer(userId, username);
                String result = player.attemptBreakthrough();
                cultivationSystem.savePlayer(player);
                sendMessage(bot, message, result);
            } catch (Exception e) {
                logger.error("突破失败", e);
                sendMessage(bot, message, "突破失败：" + e.getMessage());
            }
        });
    }
    
    private void handleCultivate(BotClient bot, Map<String, Object> message) {
        BotExecutor.async(() -> {
            try {
                long userId = getLong(message, "user_id");
                String username = getUsername(bot, message, userId);
                Player player = cultivationSystem.loadPlayer(userId, username);
                
                StringBuilder msg = new StringBuilder("✨ 修炼状态\n\n");
                msg.append("👤 用户：").append(username).append("\n");
                msg.append("境界：").append(player.currentRealmName).append("\n");
                msg.append("修为：").append(player.exp).append("/");
                msg.append(player.nextThreshold == Double.POSITIVE_INFINITY ? "MAX" : player.nextThreshold).append("\n");
                msg.append("总突破：").append(player.totalBreakthroughs).append(" 次\n");
                msg.append("总修为：").append(player.totalExpGained);
                
                sendMessage(bot, message, msg.toString());
            } catch (Exception e) {
                logger.error("查询修炼状态失败", e);
                sendMessage(bot, message, "查询修炼状态失败：" + e.getMessage());
            }
        });
    }
    
    private void handleRanking(BotClient bot, Map<String, Object> message) {
        BotExecutor.async(() -> {
            try {
                String result = cultivationSystem.getRanking(10);
                sendMessage(bot, message, result);
            } catch (Exception e) {
                logger.error("查询排行榜失败", e);
                sendMessage(bot, message, "查询排行榜失败：" + e.getMessage());
            }
        });
    }
    
    private void handleUserStats(BotClient bot, Map<String, Object> message) {
        BotExecutor.async(() -> {
            try {
                long userId = getLong(message, "user_id");
                String username = getUsername(bot, message, userId);
                Player player = cultivationSystem.loadPlayer(userId, username);
                
                StringBuilder msg = new StringBuilder("📊 修仙统计\n\n");
                msg.append("👤 用户：").append(username).append("\n");
                msg.append("境界：").append(player.currentRealmName).append("\n");
                msg.append("当前修为：").append(player.exp).append("\n");
                msg.append("下境界所需：");
                msg.append(player.nextThreshold == Double.POSITIVE_INFINITY ? "MAX" : player.nextThreshold).append("\n");
                msg.append("总突破次数：").append(player.totalBreakthroughs).append("\n");
                msg.append("总获得修为：").append(player.totalExpGained);
                
                sendMessage(bot, message, msg.toString());
            } catch (Exception e) {
                logger.error("查询统计失败", e);
                sendMessage(bot, message, "查询统计失败：" + e.getMessage());
            }
        });
    }
    
    @SuppressWarnings("unchecked")
    private String getUsername(BotClient bot, Map<String, Object> message, long userId) {
        // bot 预留：后续可走 API 补全陌生人/群成员资料
        if (bot == null) {
            return String.valueOf(userId);
        }
        try {
            Object senderObj = message.get("sender");
            if (senderObj instanceof Map<?, ?> senderMap) {
                Map<String, Object> sender = (Map<String, Object>) senderMap;
                String card = stringValue(sender.get("card"));
                if (!card.isEmpty()) {
                    return card;
                }
                String nickname = stringValue(sender.get("nickname"));
                if (!nickname.isEmpty()) {
                    return nickname;
                }
            }
            return String.valueOf(userId);
        } catch (Exception e) {
            logger.error("获取用户名失败", e);
            return String.valueOf(userId);
        }
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
            return "";
        }
        return text;
    }
    
    private String getHelpText() {
        return """
📜 DEF 插件帮助

✨ 修仙功能
- 开灵 - 踏入仙途，开始修仙
- 打坐 - 闭目修炼，增加修为
- 突破 - 尝试突破境界
- 修炼 - 查看修炼状态

🏆 排行榜功能
- 修仙排行榜 - 查看修仙排行榜
- 修仙榜单 - 查看修仙榜单

📊 统计功能
- 修仙统计 - 查看您的修仙统计
- 修仙数据 - 查看修仙数据
- 境界 - 查看当前境界
- 修为 - 查看当前修为

使用方法：直接发送对应关键词即可
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
                } else if ("private".equals(messageType)) {
                    bot.getApi().sendPrivateMessage(userId, content);
                }
            } catch (Exception e) {
                logger.error("发送消息失败", e);
            }
        });
    }

    private long getLong(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
        return 0L;
    }
}
