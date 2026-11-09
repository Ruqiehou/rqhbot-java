package cultivation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Random;

/**
 * 玩家类 - 保存玩家修仙数据
 */
public class Player {
    
    private static final Logger logger = LoggerFactory.getLogger(Player.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Random random = new Random();
    
    public long userId;
    public String username;
    public int realmIndex = -1;
    public double exp = 0;
    public double totalExpGained = 0;
    public int totalBreakthroughs = 0;
    public String currentRealmName;
    public double nextThreshold;
    
    private transient List<String> realms;
    private transient List<Double> thresholds;
    
    public Player(long userId, String username, List<String> realms, List<Double> thresholds) {
        this.userId = userId;
        this.username = username;
        this.realms = realms;
        this.thresholds = thresholds;
        this.currentRealmName = "凡人";
        this.nextThreshold = thresholds.isEmpty() ? 100 : thresholds.get(0);
    }
    
    /**
     * 从 JSON 创建玩家对象
     */
    public static Player fromJson(String json, List<String> realms, List<Double> thresholds) throws IOException {
        JsonNode node = objectMapper.readTree(json);
        
        Player player = new Player(
            node.get("userId").asLong(),
            node.get("username").asText(),
            realms,
            thresholds
        );
        
        player.realmIndex = node.get("realmIndex").asInt();
        player.exp = node.get("exp").asDouble();
        player.totalExpGained = node.get("totalExpGained").asDouble();
        player.totalBreakthroughs = node.get("totalBreakthroughs").asInt();
        player.currentRealmName = node.get("currentRealmName").asText();
        player.nextThreshold = node.get("nextThreshold").asDouble();
        
        return player;
    }
    
    /**
     * 转换为 JSON
     */
    public String toJson() throws IOException {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("userId", userId);
        node.put("username", username);
        node.put("realmIndex", realmIndex);
        node.put("exp", exp);
        node.put("totalExpGained", totalExpGained);
        node.put("totalBreakthroughs", totalBreakthroughs);
        node.put("currentRealmName", currentRealmName);
        node.put("nextThreshold", nextThreshold);
        return objectMapper.writeValueAsString(node);
    }
    
    /**
     * 开灵 - 开始修仙
     */
    public String openSoul() {
        if (realmIndex >= 0) {
            return "✨ 你已踏入仙途，当前境界：" + currentRealmName;
        }
        
        realmIndex = 0;
        exp = 0;
        currentRealmName = realms.get(0);
        nextThreshold = thresholds.get(0);
        
        return "✨ 开灵成功！你踏上了修仙之路\n当前境界：练气期";
    }
    
    /**
     * 打坐 - 修炼获得修为
     */
    public String meditate() {
        if (realmIndex < 0) {
            return "❌ 你还未开灵，请先使用「开灵」";
        }
        
        // 基础修炼量
        double baseExp = 10 + (realmIndex * 5);
        double bonus = 1 + (random.nextDouble() * 0.5);
        double gained = baseExp * bonus;
        
        exp += gained;
        totalExpGained += gained;
        
        StringBuilder msg = new StringBuilder("🧘 打坐修炼\n");
        msg.append("获得修为：").append((long)gained).append("\n");
        msg.append("总修为：").append((long)exp).append("/").append((long)nextThreshold);
        
        if (exp >= nextThreshold && realmIndex < realms.size() - 1) {
            msg.append("\n✨ 修为已充足，可以「突破」了！");
        }
        
        return msg.toString();
    }
    
    /**
     * 突破 - 尝试突破境界
     */
    public String attemptBreakthrough() {
        if (realmIndex < 0) {
            return "❌ 你还未开灵，请先使用「开灵」";
        }
        
        if (exp < nextThreshold) {
            return "❌ 修为不足，无法突破！\n当前修为：" + (long)exp + "/" + (long)nextThreshold;
        }
        
        // 突破成功率
        double successRate = 0.7 - (realmIndex * 0.03);
        successRate = Math.max(0.3, successRate);
        
        if (random.nextDouble() < successRate) {
            realmIndex++;
            exp = 0;
            totalBreakthroughs++;
            
            if (realmIndex < realms.size()) {
                currentRealmName = realms.get(realmIndex);
                nextThreshold = thresholds.get(realmIndex);
                
                return "🎉 突破成功！\n当前境界：" + currentRealmName + "\n修为：" + (long)exp + "/" + (long)nextThreshold;
            } else {
                currentRealmName = "仙帝";
                nextThreshold = Double.POSITIVE_INFINITY;
                return "🎉 恭喜！你已达到巅峰境界：仙帝！";
            }
        } else {
            // 突破失败，损失部分修为
            double lost = exp * 0.1;
            exp -= lost;
            return "❌ 突破失败！\n损失修为：" + (long)lost + "\n请继续努力修炼！";
        }
    }
}
