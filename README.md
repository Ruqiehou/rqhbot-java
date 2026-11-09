# RqhBot Java

## 介绍

RqhBot 是一个基于 OneBot 11 协议的 QQ 群聊机器人，使用 Java 21 开发，通过 WebSocket 连接协议端（支持 **NapCat**、**SnowLuma** 等）实现消息收发。支持插件化架构，内置 4 个功能插件，可轻松扩展。

**核心特性：**
- WebSocket 长连接，自动重连、API 调用自动重试
- 插件热注册，自动发现 `plugins/` 目录下的插件类
- 统一有界业务线程池（`BotExecutor`），避免占用公共池
- 命令装饰器 API，一行代码注册命令
- 支持打包为 Windows EXE（Launch4j）
- 完整的事件模型：群消息、私聊消息、通知、请求
- 协议端无关：切换 NapCat / SnowLuma 只需改连接配置

## @ 信息说明

RqhBot 通过 OneBot 11 标准的 CQ 码处理 @ 消息。

### 接收 @ 消息

当有人在群里 @ 机器人时，消息中会包含 CQ 码：

```
raw_message: [CQ:at,qq=123456789] 你好
```

在插件中解析 @ 消息：

```java
@Override
public CompletableFuture<Void> onMessage(BotClient bot, Map<String, Object> message) {
    String rawMessage = (String) message.getOrDefault("raw_message", "");
    
    // 判断是否 @ 了机器人
    String selfId = String.valueOf(message.get("self_id"));
    boolean isAtMe = rawMessage.contains("[CQ:at,qq=" + selfId + "]");
    
    if (isAtMe) {
        // 被 @ 时的处理逻辑
        long groupId = ((Number) message.get("group_id")).longValue();
        long userId = ((Number) message.get("user_id")).longValue();
        bot.getApi().sendGroupMessage(groupId, "收到！");
    }
    return CompletableFuture.completedFuture(null);
}
```

### 发送 @ 消息

通过 CQ 码 @ 群内某人：

```java
// @ 某人并发送消息
long targetQQ = 123456789;
String text = "[CQ:at,qq=" + targetQQ + "] 你好！";
bot.getApi().sendGroupMessage(groupId, text);

// 发送纯文本（不 @）
bot.getApi().sendGroupMessage(groupId, "普通消息");
```

### CQ 码速查

| 类型 | 格式 | 示例 |
|------|------|------|
| @某人 | `[CQ:at,qq=QQ号]` | `[CQ:at,qq=123456789]` |
| @全体 | `[CQ:at,qq=all]` | `[CQ:at,qq=all]` |
| 图片 | `[CQ:image,file=URL]` | `[CQ:image,file=https://example.com/img.jpg]` |
| 表情 | `[CQ:face,id=ID]` | `[CQ:face,id=178]` |
| 回复 | `[CQ:reply,id=消息ID]` | `[CQ:reply,id=123456]` |

## 目标

- 提供一个轻量、易扩展的 QQ 机器人 Java SDK
- 实现插件化架构，功能模块独立开发、互不干扰
- 通过 NapCat / SnowLuma 等协议端兼容 OneBot 11 协议
- 支持打包为独立 EXE，方便 Windows 部署分发

## 目录结构

```
rqhbot _java/
├── sdk-java/                    # 核心 SDK
│   ├── src/main/java/com/rqhbot/
│   │   ├── BotMain.java         # 主入口
│   │   ├── plugins/
│   │   │   ├── PluginRegistry.java  # 插件自动发现与注册
│   │   │   └── PluginPaths.java     # 路径工具
│   │   └── sdk/
│   │       ├── BotClient.java   # 机器人客户端
│   │       ├── BotAPI.java      # 消息发送 API
│   │       ├── NapCatClient.java# WebSocket 客户端（通用 OneBot 11）
│   │       ├── BotExecutor.java # 统一有界业务线程池
│   │       ├── pluginsystem/    # 插件系统
│   │       ├── events/          # 事件模型
│   │       ├── interfaces/      # 接口定义
│   │       └── example/         # SDK 使用示例
│   └── pom.xml
├── plugins/                     # 插件源码
│   ├── bilibili/                # B站视频解析
│   ├── cultivation/             # 修仙养成
│   ├── kinkus/                  # 综合功能（签到/天气/新闻/运势/字典/商城）
│   ├── speech/                  # 发言统计排行
│   └── assets/                  # 静态资源（图片等）
├── data/                        # 运行时数据（git 已忽略）
│   ├── kinkus/                  # Kinkus 插件数据（含 config.json：管理员/受限群）
│   └── rqhspeech/               # Speech 插件配置
└── napcat/                      # NapCat 运行环境
```

## 环境要求

- **Java 21+**
- **Maven 3.8+**
- **协议端（二选一）** — 提供 OneBot 11 正向 WebSocket 接口：
  - **NapCat**（默认，项目附带 `napcat/` 运行环境）
  - **SnowLuma** — 独立 OneBot v11 协议端，默认端口 `3001`，WebUI 默认 `http://localhost:5099`

## 快速开始

### 1. 配置

在 `sdk-java/` 目录下创建 `.env` 文件（NapCat / SnowLuma 通用）：

```env
# 协议端后端标识：napcat / snowluma（可选，默认 napcat，仅影响日志与 --auto-launch 判定）
BACKEND=napcat
WS_URL=ws://127.0.0.1:3001
ACCESS_TOKEN=your_token
QQ_ID=123456789
```

或使用命令行参数：

```bash
java -jar rqhbot.jar --ws-url ws://127.0.0.1:3001 --access-token your_token --qq-id 123456789
```

### 2. 构建运行

```bash
cd sdk-java
mvn clean package -DskipTests
java -jar target/rqhbot-sdk-java-1.0.0.jar
```

### 3. 构建 EXE

项目已配置 Launch4j 插件，打包后直接生成 Windows 可执行文件：

```bash
cd sdk-java
mvn clean package -DskipTests
# 生成 target/RqhBot.exe
```

运行 EXE 需要本机安装 JRE 21+，或在 EXE 同目录放置 `jre/` 文件夹实现免安装运行。

## 连接 SnowLuma

SnowLuma 与 NapCat 一样对外提供 **OneBot 11 正向 WebSocket**，因此 RqhBot **无需改动代码**即可连接。

1. 启动 SnowLuma 并登录机器人 QQ（SnowLuma 会自动 hook 注入桌面版 QQ 进程）。
2. 打开 SnowLuma WebUI（默认 `http://localhost:5099`），在 OneBot 网络配置中开启 **正向 WebSocket 服务端**（`wsServers`），记下端口（默认 `3001`）与访问令牌（可选）。
3. 编辑 `.env`（把连接指向 SnowLuma）：

   ```env
   BACKEND=snowluma
   WS_URL=ws://127.0.0.1:3001
   ACCESS_TOKEN=<SnowLuma 的 OneBot Token，没有则留空>
   QQ_ID=<机器人 QQ 号>
   ```

4. 运行 `start.bat`（或 `java -jar ...`）即可。

> 说明：
> - 使用 SnowLuma 时**不要**加 `--auto-launch`，该参数只用于自动拉起 NapCat；SnowLuma 请按其自身方式（`launcher.bat` / Docker）启动。
> - 若 SnowLuma 与机器人不在同一台机器，把 `WS_URL` 的主机换成 SnowLuma 所在地址即可。
> - 环境变量键也可写成 `SNOWLUMA_WS_URL` / `SNOWLUMA_ACCESS_TOKEN` / `SNOWLUMA_QQ_ID`，与通用的 `WS_URL` 等等价。

## 命令行参数

| 参数 | 说明 |
|------|------|
| `--ws-url <url>` | WebSocket 服务器地址 |
| `--access-token <token>` | 访问令牌 |
| `--qq-id <qq>` | 机器人 QQ 号 |
| `--plugin-dir <dir>` | 插件目录（默认 `plugins`） |
| `--check-plugins` | 仅检查插件发现和注册，不连接 WebSocket |
| `--auto-launch` | 连接失败时自动启动根目录 NapCat（**仅 NapCat 后端**） |
| `--help` | 显示帮助 |

## 内置插件

| 插件 | 命令/关键词 | 说明 |
|------|------------|------|
| **bilibili-parser** | 自动识别 BV/AV/B23.tv 链接 | B站视频解析，获取标题、UP主等信息 |
| **def** | 开灵、打坐、突破、修仙排行榜 | 修仙养成游戏 |
| **kinkus** | 签到、天气、天气预报、新闻、运势、随机图、字典、商城 | 综合功能（签到积分/天气查询/天气预报/新闻/运势/随机图/词典/数字转中文/点券商城） |
| **rqhspeech** | 发言日榜/周榜/月榜/季榜/年榜 | 群聊发言统计，多维度排行榜，白名单管理 |

## 插件开发

### 创建插件

继承 `PluginBase`，放在 `plugins/` 对应包名目录下：

```java
package myplugin;

import com.rqhbot.sdk.BotClient;
import com.rqhbot.sdk.pluginsystem.PluginBase;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class MyPlugin extends PluginBase {

    public MyPlugin() {
        this.name = "myplugin";
        this.version = "1.0.0";
        this.description = "我的插件";
    }

    @Override
    public CompletableFuture<Void> onLoad(BotClient bot) {
        // 插件加载时初始化
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> onUnload(BotClient bot) {
        // 插件卸载时清理
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> onMessage(BotClient bot, Map<String, Object> message) {
        String rawMessage = ((String) message.getOrDefault("raw_message", "")).trim();
        if ("ping".equals(rawMessage)) {
            long groupId = ((Number) message.get("group_id")).longValue();
            bot.getApi().sendGroupMessage(groupId, "pong");
        }
        return CompletableFuture.completedFuture(null);
    }
}
```

### 注册插件

在 [PluginRegistry.java](sdk-java/src/main/java/com/rqhbot/plugins/PluginRegistry.java) 的 `PLUGIN_PACKAGES` 数组中添加包名：

```java
private static final String[] PLUGIN_PACKAGES = {
    "bilibili", "cultivation", "kinkus", "speech", "myplugin"
};
```

### 插件生命周期

```
PluginBase
├── onLoad(BotClient)           → 插件初始化，加载配置和数据
├── onUnload(BotClient)         → 插件卸载，释放资源
├── onMessage(BotClient, msg)   → 处理消息（群聊/私聊）
├── onNotice(BotClient, notice) → 处理通知事件
└── onRequest(BotClient, req)   → 处理请求事件
```

### 路径工具

使用 `PluginPaths` 获取标准目录：

```java
import com.rqhbot.plugins.PluginPaths;

Path configDir = PluginPaths.configDir("myplugin");   // data/myplugin/
Path dataDir   = PluginPaths.dataDir("myplugin");     // data/myplugin/
Path assetsDir = PluginPaths.assetsDir("myplugin");   // plugins/assets/myplugin/
```

## SDK API

### 发送消息

```java
// 群消息
bot.getApi().sendGroupMessage(groupId, "Hello");

// 私聊消息
bot.getApi().sendPrivateMessage(userId, "Hello");

// 发送图片
bot.getApi().sendGroupImage(groupId, null, "https://example.com/img.jpg", null, "图片描述");

// 撤回消息
bot.getApi().deleteMessage(messageId);
```

### 命令注册

```java
bot.command("ping")
    .prefix("/")
    .groupOnly(true)
    .handler((event, match) -> {
        // 处理 "/ping" 命令
        return CompletableFuture.completedFuture(null);
    });
```

### 事件监听

```java
bot.onGroupMessage(data -> {
    // 处理群消息
    return CompletableFuture.completedFuture(null);
});

bot.onPrivateMessage(data -> {
    // 处理私聊消息
    return CompletableFuture.completedFuture(null);
});

bot.onNotice(data -> {
    // 处理通知
    return CompletableFuture.completedFuture(null);
});
```

## 技术栈

| 组件 | 版本 |
|------|------|
| Java | 21 |
| Tyrus (WebSocket) | 2.1.1 |
| Jackson (JSON) | 2.15.2 |
| SLF4J + Logback | 2.0.9 / 1.4.11 |
| JUnit 5 | 5.10.0 |
| Launch4j | 2.4.1 |
