package speech;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rqhbot.plugins.PluginPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * rqhspeech 配置管理类
 */
public final class SpeechConfig {

    private static final Logger logger = LoggerFactory.getLogger(SpeechConfig.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // 目录结构
    private final Path baseDir;
    private final Path dataDir;
    private final Path usersDir;
    private final Path statsDir;
    private final Path dailyDataDir;
    private final Path weeklyDataDir;
    private final Path archivesDir;
    private final Path rankingsDir;
    private final Path csvDir;
    private final Path configDir;
    private final Path logsDir;

    // 配置
    private Set<String> allowedGroups = new HashSet<>();
    private Set<String> adminUsers = new HashSet<>();
    private Set<String> operatorUsers = new HashSet<>();
    private boolean useWhitelistMode = false;

    // 排行榜配置
    private final int rankingTopN = 20;
    private final int rankingDailyTopN = 15;
    private final int rankingWeeklyTopN = 10;
    private final int rankingMonthlyTopN = 10;
    private final int rankingSeasonalTopN = 10;
    private final int rankingYearlyTopN = 10;

    // 日期格式
    public static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    public static final DateTimeFormatter DATETIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // 单例
    private static volatile SpeechConfig instance;

    private SpeechConfig() {
        baseDir = PluginPaths.dataDir("rqhspeech");
        dataDir = baseDir;
        usersDir = dataDir.resolve("users");
        statsDir = dataDir.resolve("stats");
        dailyDataDir = statsDir.resolve("daily");
        weeklyDataDir = statsDir.resolve("weekly");
        archivesDir = dataDir.resolve("archives");
        rankingsDir = archivesDir.resolve("rankings");
        csvDir = archivesDir.resolve("csv");
        configDir = PluginPaths.configDir("rqhspeech");
        logsDir = dataDir.resolve("logs");

        initDirectories();
        loadConfig();
    }

    public static SpeechConfig getInstance() {
        if (instance == null) {
            synchronized (SpeechConfig.class) {
                if (instance == null) {
                    instance = new SpeechConfig();
                }
            }
        }
        return instance;
    }

    private void initDirectories() {
        try {
            Files.createDirectories(usersDir);
            Files.createDirectories(dailyDataDir);
            Files.createDirectories(weeklyDataDir);
            Files.createDirectories(rankingsDir);
            Files.createDirectories(csvDir);
            Files.createDirectories(configDir);
            Files.createDirectories(logsDir);
        } catch (IOException e) {
            logger.error("创建目录失败：{}", e.getMessage());
        }
    }

    private void loadConfig() {
        Path configFile = configDir.resolve("speech_config.json");
        if (Files.exists(configFile)) {
            try {
                Map<String, Object> config = objectMapper.readValue(configFile.toFile(), new TypeReference<>() {});
                allowedGroups = toStringSet(config.get("allowed_groups"));
                adminUsers = toStringSet(config.get("admin_users"));
                operatorUsers = toStringSet(config.get("operator_users"));
                useWhitelistMode = (Boolean) config.getOrDefault("use_whitelist_mode", false);
            } catch (Exception e) {
                logger.error("加载配置失败：{}", e.getMessage());
            }
        } else {
            saveConfig();
        }
    }

    private void saveConfig() {
        Path configFile = configDir.resolve("speech_config.json");
        try {
            Map<String, Object> config = Map.of(
                    "allowed_groups", new ArrayList<>(allowedGroups),
                    "admin_users", new ArrayList<>(adminUsers),
                    "operator_users", new ArrayList<>(operatorUsers),
                    "use_whitelist_mode", useWhitelistMode
            );
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(configFile.toFile(), config);
        } catch (Exception e) {
            logger.error("保存配置失败：{}", e.getMessage());
        }
    }

    private Set<String> toStringSet(Object value) {
        Set<String> result = new HashSet<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null) {
                    result.add(item.toString());
                }
            }
        }
        return result;
    }

    // ========== 目录访问 ==========

    public Path getUsersDir() { return usersDir; }
    public Path getDailyDataDir() { return dailyDataDir; }
    public Path getWeeklyDataDir() { return weeklyDataDir; }
    public Path getRankingsDir() { return rankingsDir; }
    public Path getConfigDir() { return configDir; }

    // ========== 群组管理 ==========

    public boolean isAllowedGroup(String groupId) {
        if (!useWhitelistMode) return true;
        return allowedGroups.contains(groupId);
    }

    public void addAllowedGroup(String groupId) {
        allowedGroups.add(groupId);
        saveConfig();
    }

    public void removeAllowedGroup(String groupId) {
        allowedGroups.remove(groupId);
        saveConfig();
    }

    public Set<String> getAllowedGroups() { return Collections.unmodifiableSet(allowedGroups); }

    // ========== 权限管理 ==========

    public boolean isAdmin(String userId) { return adminUsers.contains(userId); }
    public boolean isOperator(String userId) { return operatorUsers.contains(userId); }
    public boolean isAnyAdmin(String userId) { return isAdmin(userId) || isOperator(userId); }

    public void addAdmin(String userId) { adminUsers.add(userId); saveConfig(); }
    public void removeAdmin(String userId) { adminUsers.remove(userId); saveConfig(); }
    public void addOperator(String userId) { operatorUsers.add(userId); saveConfig(); }
    public void removeOperator(String userId) { operatorUsers.remove(userId); saveConfig(); }

    public Set<String> getAdminUsers() { return Collections.unmodifiableSet(adminUsers); }
    public Set<String> getOperatorUsers() { return Collections.unmodifiableSet(operatorUsers); }

    // ========== 排行榜配置 ==========

    public int getRankingDailyTopN() { return rankingDailyTopN; }
    public int getRankingWeeklyTopN() { return rankingWeeklyTopN; }
    public int getRankingMonthlyTopN() { return rankingMonthlyTopN; }
    public int getRankingSeasonalTopN() { return rankingSeasonalTopN; }
    public int getRankingYearlyTopN() { return rankingYearlyTopN; }

    // ========== 白名单模式 ==========

    public boolean isUseWhitelistMode() { return useWhitelistMode; }
    public void toggleWhitelistMode() { useWhitelistMode = !useWhitelistMode; saveConfig(); }

    // ========== 工具方法 ==========

    public String getCurrentDate() { return LocalDate.now().format(DATE_FORMAT); }
    public String getCurrentDateTime() { return LocalDateTime.now().format(DATETIME_FORMAT); }

    public Path getUserFile(String userId) { return usersDir.resolve("user_" + userId + ".json"); }

    public Path getDailyFile(String dateStr) {
        String year = dateStr.split("-")[0];
        Path yearDir = dailyDataDir.resolve(year);
        try { Files.createDirectories(yearDir); } catch (IOException ignored) {}
        return yearDir.resolve("daily_" + dateStr + ".json");
    }

    public Path getWeeklyFile(String weekKey) {
        String year = weekKey.split("-W")[0];
        Path yearDir = weeklyDataDir.resolve(year);
        try { Files.createDirectories(yearDir); } catch (IOException ignored) {}
        return yearDir.resolve("weekly_" + weekKey + ".json");
    }

    public static String getCurrentWeekKey() {
        LocalDate now = LocalDate.now();
        LocalDate weekStart = now.minusDays(now.getDayOfWeek().getValue() - 1);
        return String.format("%d-W%02d", weekStart.getYear(), now.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    public static int getCurrentSeason() {
        return (LocalDate.now().getMonthValue() - 1) / 3 + 1;
    }

    public static String getSeasonName(int season) {
        return switch (season) {
            case 1 -> "春季";
            case 2 -> "夏季";
            case 3 -> "秋季";
            case 4 -> "冬季";
            default -> "未知";
        };
    }
}
