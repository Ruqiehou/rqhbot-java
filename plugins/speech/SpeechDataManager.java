package speech;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * rqhspeech 数据管理类 - 用户数据、日/周统计、排行榜
 */
public class SpeechDataManager {

    private static final Logger logger = LoggerFactory.getLogger(SpeechDataManager.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final SpeechConfig config;

    public SpeechDataManager(SpeechConfig config) {
        this.config = config;
    }

    // ========== 用户数据 ==========

    public boolean userExists(String userId) {
        return Files.exists(config.getUserFile(userId));
    }

    public Map<String, Object> createNewUser(String userId, String username) {
        Map<String, Object> userData = new LinkedHashMap<>();
        userData.put("user_id", userId);
        userData.put("username", username);
        userData.put("create_time", config.getCurrentDateTime());
        userData.put("last_update", config.getCurrentDateTime());
        userData.put("total_messages", 0);
        userData.put("total_weeks", 0);
        return userData;
    }

    public Map<String, Object> loadUserData(String userId) {
        Path file = config.getUserFile(userId);
        if (!Files.exists(file)) return null;
        try {
            return objectMapper.readValue(file.toFile(), new TypeReference<>() {});
        } catch (Exception e) {
            logger.error("加载用户数据失败 {}：{}", userId, e.getMessage());
            return null;
        }
    }

    public void saveUserData(String userId, Map<String, Object> data) {
        data.put("last_update", config.getCurrentDateTime());
        try {
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(config.getUserFile(userId).toFile(), data);
        } catch (Exception e) {
            logger.error("保存用户数据失败 {}：{}", userId, e.getMessage());
        }
    }

    public String getDisplayName(Map<String, Object> userData) {
        if (userData == null) return "未知用户";
        Object name = userData.getOrDefault("username", userData.getOrDefault("用户名", "未知用户"));
        return name.toString();
    }

    public void setUsername(String userId, String newUsername) {
        Map<String, Object> data = loadUserData(userId);
        if (data != null) {
            data.put("username", newUsername);
            saveUserData(userId, data);
        }
    }

    public void deleteUser(String userId) {
        try {
            Files.deleteIfExists(config.getUserFile(userId));
        } catch (IOException e) {
            logger.error("删除用户失败 {}：{}", userId, e.getMessage());
        }
    }

    public String searchUserByUsername(String username) {
        try (Stream<Path> files = Files.list(config.getUsersDir())) {
            return files.filter(p -> p.toString().endsWith(".json"))
                    .map(p -> {
                        try { return objectMapper.<Map<String, Object>>readValue(p.toFile(), new TypeReference<>() {}); }
                        catch (Exception e) { return null; }
                    })
                    .filter(Objects::nonNull)
                    .filter(data -> username.equals(data.get("username")))
                    .map(data -> (String) data.get("user_id"))
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    // ========== 发言更新 ==========

    public void updateUserMessage(String userId, String groupId) {
        String currentDate = config.getCurrentDate();
        String weekKey = SpeechConfig.getCurrentWeekKey();
        LocalDate now = LocalDate.now();
        LocalDate weekStart = now.minusDays(now.getDayOfWeek().getValue() - 1);

        // 更新每日数据
        Path dailyFile = config.getDailyFile(currentDate);
        Map<String, Object> dailyData = loadJson(dailyFile);
        if (dailyData == null) dailyData = new LinkedHashMap<>();
        dailyData.putIfAbsent("date", currentDate);
        dailyData.putIfAbsent("users", new LinkedHashMap<>());

        @SuppressWarnings("unchecked")
        Map<String, Object> dailyUsers = (Map<String, Object>) dailyData.get("users");
        dailyUsers.putIfAbsent(userId, new LinkedHashMap<>());
        @SuppressWarnings("unchecked")
        Map<String, Object> userDaily = (Map<String, Object>) dailyUsers.get(userId);
        userDaily.putIfAbsent("groups", new LinkedHashMap<>());
        @SuppressWarnings("unchecked")
        Map<String, Object> groups = (Map<String, Object>) userDaily.get("groups");
        groups.merge(groupId, 1, (old, inc) -> ((Number) old).intValue() + ((Number) inc).intValue());
        saveJson(dailyFile, dailyData);

        // 更新每周数据
        Path weeklyFile = config.getWeeklyFile(weekKey);
        Map<String, Object> weeklyData = loadJson(weeklyFile);
        if (weeklyData == null) {
            weeklyData = new LinkedHashMap<>();
            weeklyData.put("week_key", weekKey);
            weeklyData.put("week_start", weekStart.format(SpeechConfig.DATE_FORMAT));
            weeklyData.put("week_end", weekStart.plusDays(6).format(SpeechConfig.DATE_FORMAT));
            weeklyData.put("users", new LinkedHashMap<>());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> weeklyUsers = (Map<String, Object>) weeklyData.get("users");
        weeklyUsers.putIfAbsent(userId, new LinkedHashMap<>());
        @SuppressWarnings("unchecked")
        Map<String, Object> userWeekly = (Map<String, Object>) weeklyUsers.get(userId);
        userWeekly.putIfAbsent("groups", new LinkedHashMap<>());
        @SuppressWarnings("unchecked")
        Map<String, Object> weeklyGroups = (Map<String, Object>) userWeekly.get("groups");
        weeklyGroups.putIfAbsent(groupId, new LinkedHashMap<>());
        @SuppressWarnings("unchecked")
        Map<String, Object> groupData = (Map<String, Object>) weeklyGroups.get(groupId);
        groupData.putIfAbsent("daily", new LinkedHashMap<>());
        groupData.putIfAbsent("total", 0);

        @SuppressWarnings("unchecked")
        Map<String, Object> dailyDetail = (Map<String, Object>) groupData.get("daily");
        dailyDetail.merge(currentDate, 1, (old, inc) -> ((Number) old).intValue() + 1);
        groupData.put("total", ((Number) groupData.get("total")).intValue() + 1);

        saveJson(weeklyFile, weeklyData);
    }

    // ========== 排行榜查询 ==========

    public List<Map.Entry<String, Integer>> getDailyRankings(String groupId, String dateStr) {
        if (dateStr == null) dateStr = config.getCurrentDate();
        Path dailyFile = config.getDailyFile(dateStr);
        Map<String, Object> dailyData = loadJson(dailyFile);
        if (dailyData == null) return List.of();

        @SuppressWarnings("unchecked")
        Map<String, Object> users = (Map<String, Object>) dailyData.get("users");
        if (users == null) return List.of();

        List<Map.Entry<String, Integer>> rankings = new ArrayList<>();
        users.forEach((uid, userData) -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> ud = (Map<String, Object>) userData;
            @SuppressWarnings("unchecked")
            Map<String, Object> groups = (Map<String, Object>) ud.get("groups");
            if (groups != null && groups.containsKey(groupId)) {
                int count = ((Number) groups.get(groupId)).intValue();
                rankings.add(Map.entry(uid, count));
            }
        });

        rankings.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return rankings;
    }

    public List<Map.Entry<String, Integer>> getWeeklyRankings(String groupId, String weekKey) {
        if (weekKey == null) weekKey = SpeechConfig.getCurrentWeekKey();
        Path weeklyFile = config.getWeeklyFile(weekKey);
        Map<String, Object> weeklyData = loadJson(weeklyFile);
        if (weeklyData == null) return List.of();

        @SuppressWarnings("unchecked")
        Map<String, Object> users = (Map<String, Object>) weeklyData.get("users");
        if (users == null) return List.of();

        List<Map.Entry<String, Integer>> rankings = new ArrayList<>();
        users.forEach((uid, userData) -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> ud = (Map<String, Object>) userData;
            @SuppressWarnings("unchecked")
            Map<String, Object> groups = (Map<String, Object>) ud.get("groups");
            if (groups != null && groups.containsKey(groupId)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> gd = (Map<String, Object>) groups.get(groupId);
                int total = ((Number) gd.getOrDefault("total", 0)).intValue();
                if (total > 0) rankings.add(Map.entry(uid, total));
            }
        });

        rankings.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return rankings;
    }

    public List<Map.Entry<String, Integer>> getMonthlyRankings(String groupId, int year, int month) {
        Map<String, Integer> userTotals = new HashMap<>();
        LocalDate start;
        LocalDate end;
        if (month > 0) {
            start = LocalDate.of(year, month, 1);
            end = start.plusMonths(1).minusDays(1);
        } else {
            start = LocalDate.of(year, 1, 1);
            end = LocalDate.of(year, 12, 31);
        }

        // 遍历该月所有周的周数据
        try (Stream<Path> yearDirs = Files.list(config.getWeeklyDataDir())) {
            yearDirs.filter(Files::isDirectory).forEach(yearDir -> {
                try (Stream<Path> files = Files.list(yearDir)) {
                    files.filter(p -> p.toString().endsWith(".json")).forEach(weeklyFile -> {
                        Map<String, Object> weeklyData = loadJson(weeklyFile);
                        if (weeklyData == null) return;
                        @SuppressWarnings("unchecked")
                        Map<String, Object> users = (Map<String, Object>) weeklyData.get("users");
                        if (users == null) return;

                        users.forEach((uid, userData) -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> ud = (Map<String, Object>) userData;
                            @SuppressWarnings("unchecked")
                            Map<String, Object> groups = (Map<String, Object>) ud.get("groups");
                            if (groups == null || !groups.containsKey(groupId)) return;
                            @SuppressWarnings("unchecked")
                            Map<String, Object> gd = (Map<String, Object>) groups.get(groupId);
                            @SuppressWarnings("unchecked")
                            Map<String, Object> daily = (Map<String, Object>) gd.get("daily");
                            if (daily == null) return;

                            daily.forEach((date, count) -> {
                                LocalDate d = LocalDate.parse(date, SpeechConfig.DATE_FORMAT);
                                if (!d.isBefore(start) && !d.isAfter(end)) {
                                    userTotals.merge(uid, ((Number) count).intValue(), Integer::sum);
                                }
                            });
                        });
                    });
                } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}

        return userTotals.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .map(e -> Map.entry(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }

    public List<Map.Entry<String, Integer>> getYearlyRankings(String groupId, int year) {
        return getMonthlyRankings(groupId, year, -1); // -1 表示全年
    }

    // 获取用户在指定群的今日发言数
    public int getUserDailyCount(String userId, String groupId) {
        Map<String, Object> dailyData = loadJson(config.getDailyFile(config.getCurrentDate()));
        if (dailyData == null) return 0;
        @SuppressWarnings("unchecked")
        Map<String, Object> users = (Map<String, Object>) dailyData.get("users");
        if (users == null || !users.containsKey(userId)) return 0;
        @SuppressWarnings("unchecked")
        Map<String, Object> ud = (Map<String, Object>) users.get(userId);
        @SuppressWarnings("unchecked")
        Map<String, Object> groups = (Map<String, Object>) ud.get("groups");
        if (groups == null) return 0;
        return ((Number) groups.getOrDefault(groupId, 0)).intValue();
    }

    // 获取用户在指定群的本周发言数
    public int getUserWeeklyCount(String userId, String groupId) {
        Map<String, Object> weeklyData = loadJson(config.getWeeklyFile(SpeechConfig.getCurrentWeekKey()));
        if (weeklyData == null) return 0;
        @SuppressWarnings("unchecked")
        Map<String, Object> users = (Map<String, Object>) weeklyData.get("users");
        if (users == null || !users.containsKey(userId)) return 0;
        @SuppressWarnings("unchecked")
        Map<String, Object> ud = (Map<String, Object>) users.get(userId);
        @SuppressWarnings("unchecked")
        Map<String, Object> groups = (Map<String, Object>) ud.get("groups");
        if (groups == null || !groups.containsKey(groupId)) return 0;
        @SuppressWarnings("unchecked")
        Map<String, Object> gd = (Map<String, Object>) groups.get(groupId);
        return ((Number) gd.getOrDefault("total", 0)).intValue();
    }

    // 获取用户在指定群的本周每日明细
    @SuppressWarnings("unchecked")
    public Map<String, Integer> getUserWeeklyDailyDetail(String userId, String groupId) {
        Map<String, Object> weeklyData = loadJson(config.getWeeklyFile(SpeechConfig.getCurrentWeekKey()));
        if (weeklyData == null) return Map.of();
        Map<String, Object> users = (Map<String, Object>) weeklyData.get("users");
        if (users == null || !users.containsKey(userId)) return Map.of();
        Map<String, Object> ud = (Map<String, Object>) users.get(userId);
        Map<String, Object> groups = (Map<String, Object>) ud.get("groups");
        if (groups == null || !groups.containsKey(groupId)) return Map.of();
        Map<String, Object> gd = (Map<String, Object>) groups.get(groupId);
        Map<String, Object> daily = (Map<String, Object>) gd.get("daily");
        if (daily == null) return Map.of();

        Map<String, Integer> result = new TreeMap<>();
        daily.forEach((date, count) -> result.put(date, ((Number) count).intValue()));
        return result;
    }

    // ========== JSON 工具 ==========

    private Map<String, Object> loadJson(Path file) {
        if (!Files.exists(file)) return null;
        try {
            return objectMapper.readValue(file.toFile(), new TypeReference<>() {});
        } catch (Exception e) {
            logger.error("加载JSON失败 {}：{}", file, e.getMessage());
            return null;
        }
    }

    private void saveJson(Path file, Map<String, Object> data) {
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), data);
        } catch (Exception e) {
            logger.error("保存JSON失败 {}：{}", file, e.getMessage());
        }
    }
}
