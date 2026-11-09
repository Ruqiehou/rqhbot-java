package com.rqhbot.plugins;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rqhbot.sdk.BotClient;
import com.rqhbot.sdk.pluginsystem.PluginBase;

/**
 * 动态发现并注册所有 {@link PluginBase} 实现。
 * <p>
 * 不再硬编码插件包名：扫描 classpath 上的 .class / jar，
 * 自动加载可实例化的非抽象插件类。
 */
public final class PluginRegistry {
    private static final Logger logger = LoggerFactory.getLogger(PluginRegistry.class);

    private PluginRegistry() {
    }

    public static void registerAll(BotClient bot) {
        List<Class<? extends PluginBase>> pluginClasses = discoverPlugins();
        if (pluginClasses.isEmpty()) {
            logger.warn("未发现可注册插件，请检查插件源码目录和 classpath");
            return;
        }

        logger.info("发现 {} 个插件类", pluginClasses.size());
        for (Class<? extends PluginBase> pluginClass : pluginClasses) {
            try {
                PluginBase plugin = pluginClass.getDeclaredConstructor().newInstance();
                bot.registerPlugin(plugin);
            } catch (ReflectiveOperationException e) {
                logger.error("插件 {} 实例化失败：{}", pluginClass.getName(), e.getMessage(), e);
            }
        }
    }

    /**
     * 扫描 classpath，返回所有可实例化的插件类（按类名排序）。
     */
    static List<Class<? extends PluginBase>> discoverPlugins() {
        Set<Class<? extends PluginBase>> found = new LinkedHashSet<>();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = PluginRegistry.class.getClassLoader();
        }

        try {
            Enumeration<URL> roots = loader.getResources("");
            while (roots.hasMoreElements()) {
                URL root = roots.nextElement();
                scanUrl(loader, root, found);
            }
        } catch (IOException e) {
            logger.error("扫描插件 classpath 失败：{}", e.getMessage(), e);
        }

        // 再扫 java.class.path，覆盖某些环境下 getResources("") 不完整的情况
        String classpath = System.getProperty("java.class.path");
        if (classpath != null && !classpath.isBlank()) {
            for (String entry : classpath.split(File.pathSeparator)) {
                if (entry == null || entry.isBlank()) {
                    continue;
                }
                File file = new File(entry);
                if (!file.exists()) {
                    continue;
                }
                if (file.isDirectory()) {
                    scanDirectory(loader, file.toPath(), "", found);
                } else if (isJar(file.getName())) {
                    scanJar(loader, file, found);
                }
            }
        }

        List<Class<? extends PluginBase>> plugins = new ArrayList<>(found);
        plugins.sort((a, b) -> a.getName().compareTo(b.getName()));
        return Collections.unmodifiableList(plugins);
    }

    private static void scanUrl(ClassLoader loader, URL url, Set<Class<? extends PluginBase>> found) {
        String protocol = url.getProtocol();
        if ("file".equals(protocol)) {
            try {
                Path path = Path.of(url.toURI());
                if (Files.isDirectory(path)) {
                    scanDirectory(loader, path, "", found);
                } else if (Files.isRegularFile(path) && isJar(path.getFileName().toString())) {
                    scanJar(loader, path.toFile(), found);
                }
            } catch (URISyntaxException e) {
                logger.warn("无法解析 classpath URL：{}", url);
            }
        } else if ("jar".equals(protocol)) {
            // jar:file:/path/to.jar!/
            String spec = url.getFile();
            int bang = spec.indexOf('!');
            if (bang > 0) {
                String jarPath = spec.substring(0, bang);
                if (jarPath.startsWith("file:")) {
                    try {
                        scanJar(loader, Path.of(URI.create(jarPath)).toFile(), found);
                    } catch (Exception e) {
                        logger.warn("扫描 jar URL 失败：{}", url);
                    }
                }
            }
        }
    }

    private static void scanDirectory(
            ClassLoader loader,
            Path root,
            String packageName,
            Set<Class<? extends PluginBase>> found
    ) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> stream = Files.list(root)) {
            stream.forEach(path -> {
                String name = path.getFileName().toString();
                if (Files.isDirectory(path)) {
                    // 跳过明显非代码目录，降低噪音
                    if (name.startsWith(".") || name.equals("META-INF") || name.equals("tmp")) {
                        return;
                    }
                    String childPackage = packageName.isEmpty() ? name : packageName + "." + name;
                    scanDirectory(loader, path, childPackage, found);
                    return;
                }
                if (!name.endsWith(".class") || name.contains("$")) {
                    return;
                }
                String simpleName = name.substring(0, name.length() - ".class".length());
                String className = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
                tryLoadPlugin(loader, className, found);
            });
        } catch (IOException e) {
            logger.warn("扫描目录失败 {}: {}", root, e.getMessage());
        }
    }

    private static void scanJar(ClassLoader loader, File jarFile, Set<Class<? extends PluginBase>> found) {
        if (jarFile == null || !jarFile.isFile()) {
            return;
        }
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName();
                if (!entryName.endsWith(".class") || entryName.contains("$")) {
                    continue;
                }
                // 只关心看起来像插件包的类，避免把整个依赖树都 Class.forName
                if (!looksLikePluginClass(entryName)) {
                    continue;
                }
                String className = entryName
                        .replace('/', '.')
                        .substring(0, entryName.length() - ".class".length());
                tryLoadPlugin(loader, className, found);
            }
        } catch (IOException e) {
            logger.warn("扫描 JAR 失败 {}: {}", jarFile.getAbsolutePath(), e.getMessage());
        }
    }

    /**
     * jar 扫描过滤：插件包通常是简短顶层包（bilibili/kinkus/...）或 com.rqhbot.plugins 下。
     * 排除常见第三方前缀，避免对整个依赖做 Class.forName。
     */
    private static boolean looksLikePluginClass(String entryName) {
        if (entryName.startsWith("META-INF/")) {
            return false;
        }
        if (entryName.startsWith("com/rqhbot/plugins/")) {
            return true;
        }
        // 顶层插件包：xxx/SomePlugin.class，且不是深层依赖路径
        if (entryName.startsWith("org/")
                || entryName.startsWith("com/fasterxml/")
                || entryName.startsWith("com/google/")
                || entryName.startsWith("ch/qos/")
                || entryName.startsWith("jakarta/")
                || entryName.startsWith("java/")
                || entryName.startsWith("javax/")
                || entryName.startsWith("sun/")
                || entryName.startsWith("module-info")) {
            return false;
        }
        // 接受一层或两层包下的 *Plugin*.class
        String fileName = entryName.substring(entryName.lastIndexOf('/') + 1);
        return fileName.endsWith("Plugin.class") || fileName.contains("Plugin");
    }

    @SuppressWarnings("unchecked")
    private static void tryLoadPlugin(
            ClassLoader loader,
            String className,
            Set<Class<? extends PluginBase>> found
    ) {
        try {
            Class<?> candidate = Class.forName(className, false, loader);
            int modifiers = candidate.getModifiers();
            if (!PluginBase.class.isAssignableFrom(candidate)
                    || Modifier.isAbstract(modifiers)
                    || candidate.isInterface()) {
                return;
            }
            candidate.getDeclaredConstructor();
            found.add((Class<? extends PluginBase>) candidate);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            // 非插件类或无法加载，静默跳过
        }
    }

    private static boolean isJar(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".jar") || lower.endsWith(".zip");
    }
}
