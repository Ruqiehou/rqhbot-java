package cultivation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rqhbot.plugins.PluginPaths;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * 修仙系统 - 管理玩家数据和境界突破
 */
public class CultivationSystem {
    
    private static final Logger logger = LoggerFactory.getLogger(CultivationSystem.class);
    
    private final String dataDir = PluginPaths.dataDir("def").toString();
    private final List<String> realms = new ArrayList<>();
    private final List<Double> thresholds = new ArrayList<>();
    
    public CultivationSystem() {
        loadRealmsConfig();
    }
    
    /**
     * 加载境界配置
     */
    private void loadRealmsConfig() {
        // 默认境界配置
        realms.addAll(List.of(
            "练气期", "筑基期", "金丹期", "元婴期", "化神期",
            "炼虚期", "合体期", "大乘期", "渡劫期", "散仙",
            "地仙", "天仙", "真仙", "玄仙", "金仙",
            "太乙金仙", "大至金仙", "太乙玄仙", "大至玄仙", "太乙真仙"
        ));
        
        // 生成阈值（斐波那契数列）
        double a = 100, b = 200;
        for (int i = 0; i < realms.size(); i++) {
            thresholds.add(a);
            double temp = a + b;
            a = b;
            b = temp;
        }
    }
    
    /**
     * 加载玩家数据
     */
    public Player loadPlayer(long userId, String username) {
        String filePath = Paths.get(dataDir, "player_" + userId + ".json").toString();
        File file = new File(filePath);
        
        if (file.exists()) {
            try {
                String content = Files.readString(Paths.get(filePath));
                return Player.fromJson(content, realms, thresholds);
            } catch (IOException e) {
                logger.error("读取玩家数据失败：{}", e.getMessage());
            }
        }
        
        // 创建新玩家
        return new Player(userId, username, realms, thresholds);
    }
    
    /**
     * 保存玩家数据
     */
    public void savePlayer(Player player) {
        try {
            Files.createDirectories(Paths.get(dataDir));
            String filePath = Paths.get(dataDir, "player_" + player.userId + ".json").toString();
            Files.writeString(Paths.get(filePath), player.toJson());
        } catch (IOException e) {
            logger.error("保存玩家数据失败：{}", e.getMessage());
        }
    }
    
    /**
     * 获取排行榜
     */
    public String getRanking(int limit) {
        File dir = new File(dataDir);
        if (!dir.exists()) {
            return "暂无玩家数据";
        }
        
        List<Player> players = new ArrayList<>();
        File[] playerFiles = dir.listFiles((d, name) -> name.startsWith("player_") && name.endsWith(".json"));
        
        if (playerFiles != null) {
            for (File file : playerFiles) {
                try {
                    String content = Files.readString(file.toPath());
                    players.add(Player.fromJson(content, realms, thresholds));
                } catch (IOException e) {
                    logger.error("读取玩家数据失败：{}", e.getMessage());
                }
            }
        }
        
        // 按总修为排序
        players.sort((a, b) -> Double.compare(b.totalExpGained, a.totalExpGained));
        
        StringBuilder msg = new StringBuilder("🏆 修仙排行榜\n\n");
        int count = Math.min(limit, players.size());
        
        for (int i = 0; i < count; i++) {
            Player player = players.get(i);
            msg.append(i + 1).append(". ");
            msg.append(player.username).append(" - ");
            msg.append(player.currentRealmName).append("\n");
            msg.append("  总修为：").append((long)player.totalExpGained).append("\n");
        }
        
        if (players.isEmpty()) {
            msg.append("暂无数据");
        }
        
        return msg.toString();
    }
}
