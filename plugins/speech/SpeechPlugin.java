package speech;

import com.rqhbot.sdk.BotClient;
import com.rqhbot.sdk.BotExecutor;
import com.rqhbot.sdk.pluginsystem.PluginBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * rqhspeech 群聊发言统计插件
 * 功能：多维度排行榜（日/周/月/季/年）、个人统计、用户管理、白名单
 */
public class SpeechPlugin extends PluginBase {

    private static final Logger logger = LoggerFactory.getLogger(SpeechPlugin.class);
    private SpeechConfig config;
    private SpeechDataManager dataManager;

    public SpeechPlugin() {
        this.name = "rqhspeech";
        this.version = "5.0.0";
        this.description = "群聊发言统计 - 多维度排行榜、个人统计、用户管理";
        this.author = "rqh";
        this.enabled = true;
    }

    @Override
    public CompletableFuture<Void> onLoad(BotClient bot) {
        logger.info("插件 {} 已加载，版本：{}", name, version);
        config = SpeechConfig.getInstance();
        dataManager = new SpeechDataManager(config);

        if (config.isUseWhitelistMode()) {
            logger.info("当前模式：白名单模式（{}个群）", config.getAllowedGroups().size());
        } else {
            logger.info("当前模式：所有群可用");
        }

        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> onUnload(BotClient bot) {
        logger.info("插件 {} 已卸载", name);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> onMessage(BotClient bot, Map<String, Object> message) {
        String messageType = (String) message.get("message_type");
        if (!"group".equals(messageType)) return CompletableFuture.completedFuture(null);

        String groupId = String.valueOf(message.get("group_id"));
        String userId = String.valueOf(message.get("user_id"));
        String rawMessage = ((String) message.getOrDefault("raw_message", "")).trim();

        // 白名单检查
        if (!config.isAllowedGroup(groupId)) return CompletableFuture.completedFuture(null);

        // 自动注册新用户
        if (!dataManager.userExists(userId)) {
            String username = (String) message.getOrDefault("nickname", "用户" + userId);
            dataManager.saveUserData(userId, dataManager.createNewUser(userId, username));
        }

        // 更新发言统计
        dataManager.updateUserMessage(userId, groupId);

        // 命令处理
        if (rawMessage.isEmpty()) return CompletableFuture.completedFuture(null);

        switch (rawMessage) {
            case "注册" -> handleRegister(bot, groupId, userId);
            case "发言日榜" -> handleDailyRank(bot, groupId, null);
            case "我的发言" -> handleMyStats(bot, groupId, userId);
            case "发言周榜" -> handleWeeklyRank(bot, groupId, null);
            case "发言月榜" -> handleMonthlyRank(bot, groupId);
            case "发言季榜" -> handleSeasonalRank(bot, groupId);
            case "发言年榜" -> handleYearlyRank(bot, groupId);
            case "查看白名单" -> handleViewWhitelist(bot, groupId);
            case "发言管理员名单" -> handleViewAdmins(bot, groupId);
            case "发言操作员名单" -> handleViewOperators(bot, groupId);
            default -> {
                if (rawMessage.startsWith("历史日榜")) {
                    String date = rawMessage.substring(4).trim();
                    handleDailyRank(bot, groupId, date);
                } else if (rawMessage.startsWith("历史周榜")) {
                    String weekId = rawMessage.substring(4).trim();
                    handleWeeklyRank(bot, groupId, weekId);
                } else if (rawMessage.startsWith("设置用户名")) {
                    String newName = rawMessage.substring(5).trim();
                    handleSetUsername(bot, groupId, userId, newName);
                } else if (rawMessage.startsWith("查询发言") || rawMessage.startsWith("查发言")) {
                    String param = rawMessage.startsWith("查询发言") ? rawMessage.substring(4).trim() : rawMessage.substring(3).trim();
                    handleQueryUser(bot, groupId, param);
                } else if (rawMessage.startsWith("删除用户") && config.isAdmin(userId)) {
                    String targetId = rawMessage.substring(4).trim();
                    handleDeleteUser(bot, groupId, targetId);
                } else if (rawMessage.startsWith("加白名单") || rawMessage.startsWith("加入白名单")) {
                    String targetGroup = rawMessage.replaceAll("^(加白名单|加入白名单)", "").trim();
                    handleAddGroup(bot, groupId, userId, targetGroup);
                } else if (rawMessage.startsWith("移除白名单") || rawMessage.startsWith("移出白名单")) {
                    String targetGroup = rawMessage.replaceAll("^(移除白名单|移出白名单)", "").trim();
                    handleRemoveGroup(bot, groupId, userId, targetGroup);
                } else if (rawMessage.startsWith("切换白名单模式") && config.isAdmin(userId)) {
                    config.toggleWhitelistMode();
                    sendGroupMsg(bot, groupId, "白名单模式已" + (config.isUseWhitelistMode() ? "开启" : "关闭"));
                } else if (rawMessage.startsWith("发言添加管理员") && config.isAdmin(userId)) {
                    String targetId = rawMessage.substring(7).trim();
                    config.addAdmin(targetId);
                    sendGroupMsg(bot, groupId, "已添加管理员：" + targetId);
                } else if (rawMessage.startsWith("发言移除管理员") && config.isAdmin(userId)) {
                    String targetId = rawMessage.substring(7).trim();
                    config.removeAdmin(targetId);
                    sendGroupMsg(bot, groupId, "已移除管理员：" + targetId);
                } else if (rawMessage.startsWith("发言添加操作员") && config.isAdmin(userId)) {
                    String targetId = rawMessage.substring(7).trim();
                    config.addOperator(targetId);
                    sendGroupMsg(bot, groupId, "已添加操作员：" + targetId);
                } else if (rawMessage.startsWith("发言移除操作员") && config.isAdmin(userId)) {
                    String targetId = rawMessage.substring(7).trim();
                    config.removeOperator(targetId);
                    sendGroupMsg(bot, groupId, "已移除操作员：" + targetId);
                }
            }
        }

        return CompletableFuture.completedFuture(null);
    }

    // ========== 命令处理 ==========

    private void handleRegister(BotClient bot, String groupId, String userId) {
        if (dataManager.userExists(userId)) {
            sendGroupMsg(bot, groupId, "用户已注册");
            return;
        }
        Map<String, Object> existing = dataManager.loadUserData(userId);
        String username = existing != null ? dataManager.getDisplayName(existing) : "用户" + userId;
        dataManager.saveUserData(userId, dataManager.createNewUser(userId, username));
        sendGroupMsg(bot, groupId, "注册成功：" + username + " (" + userId + ")");
    }

    private void handleDailyRank(BotClient bot, String groupId, String dateStr) {
        List<Map.Entry<String, Integer>> rankings = dataManager.getDailyRankings(groupId, dateStr);
        if (rankings.isEmpty()) {
            sendGroupMsg(bot, groupId, "暂无发言记录");
            return;
        }

        String title = dateStr != null ? dateStr + " 发言日榜" : "发言日榜";
        StringBuilder sb = new StringBuilder(title + " (本群)\n\n");

        int limit = Math.min(rankings.size(), config.getRankingDailyTopN());
        for (int i = 0; i < limit; i++) {
            Map.Entry<String, Integer> entry = rankings.get(i);
            String medal = getMedal(i + 1);
            String username = dataManager.getDisplayName(dataManager.loadUserData(entry.getKey()));
            sb.append(medal).append(" [").append(username).append("]: ").append(entry.getValue()).append("条\n");
        }

        sendGroupMsg(bot, groupId, sb.toString());
    }

    private void handleMyStats(BotClient bot, String groupId, String userId) {
        Map<String, Object> userData = dataManager.loadUserData(userId);
        if (userData == null) {
            sendGroupMsg(bot, groupId, "你未注册，请发送「注册」");
            return;
        }

        int todayCount = dataManager.getUserDailyCount(userId, groupId);
        int weekCount = dataManager.getUserWeeklyCount(userId, groupId);
        Map<String, Integer> dailyDetail = dataManager.getUserWeeklyDailyDetail(userId, groupId);

        StringBuilder sb = new StringBuilder("你的发言统计（本群）\n\n");
        sb.append("  今日：").append(todayCount).append(" 条\n");
        sb.append("  本周：").append(weekCount).append(" 条\n\n");

        if (!dailyDetail.isEmpty()) {
            sb.append("本周明细：\n");
            String[] weekdays = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
            dailyDetail.forEach((date, count) -> {
                LocalDate d = LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE);
                String weekday = weekdays[d.getDayOfWeek().getValue() - 1];
                sb.append("  ").append(date).append(" (").append(weekday).append("): ").append(count).append("条\n");
            });
        }

        sendGroupMsg(bot, groupId, sb.toString());
    }

    private void handleWeeklyRank(BotClient bot, String groupId, String weekKey) {
        List<Map.Entry<String, Integer>> rankings = dataManager.getWeeklyRankings(groupId, weekKey);
        if (rankings.isEmpty()) {
            sendGroupMsg(bot, groupId, "暂无发言记录");
            return;
        }

        String title = weekKey != null ? weekKey + " 发言周榜" : "发言周榜";
        StringBuilder sb = new StringBuilder(title + " (本群)\n\n");

        int limit = Math.min(rankings.size(), config.getRankingWeeklyTopN());
        for (int i = 0; i < limit; i++) {
            Map.Entry<String, Integer> entry = rankings.get(i);
            String medal = getMedal(i + 1);
            String username = dataManager.getDisplayName(dataManager.loadUserData(entry.getKey()));
            sb.append(medal).append(" [").append(username).append("]: ").append(entry.getValue()).append("条\n");
        }

        sendGroupMsg(bot, groupId, sb.toString());
    }

    private void handleMonthlyRank(BotClient bot, String groupId) {
        LocalDate now = LocalDate.now();
        List<Map.Entry<String, Integer>> rankings = dataManager.getMonthlyRankings(groupId, now.getYear(), now.getMonthValue());
        if (rankings.isEmpty()) {
            sendGroupMsg(bot, groupId, "暂无月榜记录");
            return;
        }

        StringBuilder sb = new StringBuilder("发言月榜 (" + now.getYear() + "年" + now.getMonthValue() + "月)\n\n");
        int limit = Math.min(rankings.size(), config.getRankingMonthlyTopN());
        for (int i = 0; i < limit; i++) {
            Map.Entry<String, Integer> entry = rankings.get(i);
            String medal = getMedal(i + 1);
            String username = dataManager.getDisplayName(dataManager.loadUserData(entry.getKey()));
            sb.append(medal).append(" [").append(username).append("]: ").append(entry.getValue()).append("条\n");
        }

        sendGroupMsg(bot, groupId, sb.toString());
    }

    private void handleSeasonalRank(BotClient bot, String groupId) {
        LocalDate now = LocalDate.now();
        int season = SpeechConfig.getCurrentSeason();
        int startMonth = (season - 1) * 3 + 1;

        // 合并本季度3个月的数据
        java.util.Map<String, Integer> totals = new java.util.HashMap<>();
        for (int m = startMonth; m < startMonth + 3 && m <= 12; m++) {
            dataManager.getMonthlyRankings(groupId, now.getYear(), m)
                    .forEach(e -> totals.merge(e.getKey(), e.getValue(), Integer::sum));
        }

        List<Map.Entry<String, Integer>> rankings = totals.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .map(e -> Map.entry(e.getKey(), e.getValue()))
                .toList();

        if (rankings.isEmpty()) {
            sendGroupMsg(bot, groupId, "暂无发言记录");
            return;
        }

        String seasonName = SpeechConfig.getSeasonName(season);
        StringBuilder sb = new StringBuilder("发言季榜 (" + now.getYear() + "年" + seasonName + ")\n\n");
        int limit = Math.min(rankings.size(), config.getRankingSeasonalTopN());
        for (int i = 0; i < limit; i++) {
            Map.Entry<String, Integer> entry = rankings.get(i);
            String medal = getMedal(i + 1);
            String username = dataManager.getDisplayName(dataManager.loadUserData(entry.getKey()));
            sb.append(medal).append(" [").append(username).append("]: ").append(entry.getValue()).append("条\n");
        }

        sendGroupMsg(bot, groupId, sb.toString());
    }

    private void handleYearlyRank(BotClient bot, String groupId) {
        int year = LocalDate.now().getYear();
        List<Map.Entry<String, Integer>> rankings = dataManager.getYearlyRankings(groupId, year);
        if (rankings.isEmpty()) {
            sendGroupMsg(bot, groupId, "暂无年榜记录");
            return;
        }

        StringBuilder sb = new StringBuilder("发言年榜 (" + year + "年)\n\n");
        int limit = Math.min(rankings.size(), config.getRankingYearlyTopN());
        for (int i = 0; i < limit; i++) {
            Map.Entry<String, Integer> entry = rankings.get(i);
            String medal = getMedal(i + 1);
            String username = dataManager.getDisplayName(dataManager.loadUserData(entry.getKey()));
            sb.append(medal).append(" [").append(username).append("]: ").append(entry.getValue()).append("条\n");
        }

        sendGroupMsg(bot, groupId, sb.toString());
    }

    private void handleSetUsername(BotClient bot, String groupId, String userId, String newName) {
        if (newName.isEmpty()) {
            sendGroupMsg(bot, groupId, "格式：设置用户名 你的新名字");
            return;
        }
        if (newName.length() > 20) {
            sendGroupMsg(bot, groupId, "用户名过长，请控制在20个字符以内");
            return;
        }
        String oldName = dataManager.getDisplayName(dataManager.loadUserData(userId));
        dataManager.setUsername(userId, newName);
        sendGroupMsg(bot, groupId, "用户名已更新：" + oldName + " -> " + newName);
    }

    private void handleQueryUser(BotClient bot, String groupId, String param) {
        if (param.isEmpty()) {
            sendGroupMsg(bot, groupId, "格式：查询发言 QQ号/用户名");
            return;
        }

        String targetUserId = param.matches("\\d+") ? param : dataManager.searchUserByUsername(param);
        if (targetUserId == null || !dataManager.userExists(targetUserId)) {
            sendGroupMsg(bot, groupId, "未找到该用户（可能未注册）");
            return;
        }

        Map<String, Object> userData = dataManager.loadUserData(targetUserId);
        String username = dataManager.getDisplayName(userData);
        int todayCount = dataManager.getUserDailyCount(targetUserId, groupId);
        int weekCount = dataManager.getUserWeeklyCount(targetUserId, groupId);
        int totalMessages = ((Number) userData.getOrDefault("total_messages", 0)).intValue();

        String result = String.format("%s 的发言统计\n今日：%d 条\n本周：%d 条\n累计：%d 条",
                username, todayCount, weekCount, totalMessages);
        sendGroupMsg(bot, groupId, result);
    }

    private void handleDeleteUser(BotClient bot, String groupId, String targetId) {
        if (targetId.isEmpty()) {
            sendGroupMsg(bot, groupId, "格式：删除用户 QQ号");
            return;
        }
        if (!dataManager.userExists(targetId)) {
            sendGroupMsg(bot, groupId, "用户不存在");
            return;
        }
        dataManager.deleteUser(targetId);
        sendGroupMsg(bot, groupId, "已删除用户：" + targetId);
    }

    private void handleAddGroup(BotClient bot, String groupId, String userId, String targetGroup) {
        if (!config.isAdmin(userId)) {
            sendGroupMsg(bot, groupId, "仅管理员可用");
            return;
        }
        config.addAllowedGroup(targetGroup.isEmpty() ? groupId : targetGroup);
        sendGroupMsg(bot, groupId, "已添加白名单群：" + (targetGroup.isEmpty() ? groupId : targetGroup));
    }

    private void handleRemoveGroup(BotClient bot, String groupId, String userId, String targetGroup) {
        if (!config.isAdmin(userId)) {
            sendGroupMsg(bot, groupId, "仅管理员可用");
            return;
        }
        config.removeAllowedGroup(targetGroup.isEmpty() ? groupId : targetGroup);
        sendGroupMsg(bot, groupId, "已移除白名单群：" + (targetGroup.isEmpty() ? groupId : targetGroup));
    }

    private void handleViewWhitelist(BotClient bot, String groupId) {
        if (config.getAllowedGroups().isEmpty()) {
            sendGroupMsg(bot, groupId, "白名单为空");
            return;
        }
        StringBuilder sb = new StringBuilder("白名单群列表：\n");
        config.getAllowedGroups().forEach(g -> sb.append("  ").append(g).append("\n"));
        sb.append("模式：").append(config.isUseWhitelistMode() ? "白名单模式" : "所有群可用");
        sendGroupMsg(bot, groupId, sb.toString());
    }

    private void handleViewAdmins(BotClient bot, String groupId) {
        if (config.getAdminUsers().isEmpty()) {
            sendGroupMsg(bot, groupId, "管理员列表为空");
            return;
        }
        StringBuilder sb = new StringBuilder("管理员列表：\n");
        config.getAdminUsers().forEach(u -> sb.append("  ").append(u).append("\n"));
        sendGroupMsg(bot, groupId, sb.toString());
    }

    private void handleViewOperators(BotClient bot, String groupId) {
        if (config.getOperatorUsers().isEmpty()) {
            sendGroupMsg(bot, groupId, "操作员列表为空");
            return;
        }
        StringBuilder sb = new StringBuilder("操作员列表：\n");
        config.getOperatorUsers().forEach(u -> sb.append("  ").append(u).append("\n"));
        sendGroupMsg(bot, groupId, sb.toString());
    }

    // ========== 工具方法 ==========

    private String getMedal(int rank) {
        return switch (rank) {
            case 1 -> "\uD83E\uDD47";
            case 2 -> "\uD83E\uDD48";
            case 3 -> "\uD83E\uDD49";
            default -> String.format("%2d.", rank);
        };
    }

    private void sendGroupMsg(BotClient bot, String groupId, String text) {
        BotExecutor.async(() -> {
            try {
                bot.getApi().sendGroupMessage(Long.parseLong(groupId), text);
            } catch (Exception e) {
                logger.error("发送群消息失败：{}", e.getMessage());
            }
        });
    }
}
