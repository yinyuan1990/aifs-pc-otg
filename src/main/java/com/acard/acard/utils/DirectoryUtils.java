package com.acard.acard.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

/**
 * 文件夹操作工具类
 * 提供清空、删除文件夹等常用操作
 */
public class DirectoryUtils {

    /**
     * 清空文件夹内容，保留根目录
     * - 删除文件夹内所有文件和子文件夹
     * - 但保留根目录本身
     * 
     * @param dirPath 要清空的文件夹路径
     * @return true 清空成功，false 清空失败
     */
    public static boolean clearDirectory(String dirPath) {
        return clearDirectory(Paths.get(dirPath));
    }

    /**
     * 清空文件夹内容，保留根目录
     * - 删除文件夹内所有文件和子文件夹
     * - 但保留根目录本身
     * 
     * @param root 要清空的文件夹路径
     * @return true 清空成功，false 清空失败
     */
    public static boolean clearDirectory(Path root) {
        try {
            if (root == null || !Files.exists(root)) {
                System.out.println("⚠️ 文件夹不存在: " + root);
                return false;
            }

            if (!Files.isDirectory(root)) {
                System.out.println("⚠️ 路径不是文件夹: " + root);
                return false;
            }

            // ⭐ 第一步：读取所有现有文件（拍快照）
            System.out.println("📸 拍摄文件快照: " + root);
            java.util.List<Path> snapshot = Files.walk(root)
                    .sorted(java.util.Comparator.reverseOrder()) // 先文件后目录
                    .filter(p -> !p.equals(root))                // 不删除根目录
                    .collect(java.util.stream.Collectors.toList());

            System.out.println("📋 快照中有 " + snapshot.size() + " 个文件/文件夹");

            // ⭐ 第二步：只删除快照中的文件
            int deletedCount = 0;
            int failedCount = 0;

            for (Path p : snapshot) {
                try {
                    if (Files.deleteIfExists(p)) {
                        deletedCount++;
                    }
                } catch (IOException e) {
                    System.err.println("⚠️ 删除失败: " + p.getFileName() + " - " + e.getMessage());
                    failedCount++;
                }
            }

            System.out.println("✅ 清空完成: " + root);
            System.out.println("   - 已删除: " + deletedCount + " 个");
            System.out.println("   - 失败: " + failedCount + " 个");

            return failedCount == 0;

        } catch (IOException e) {
            System.err.println("❌ 清空文件夹失败: " + root + ", " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 完全删除文件夹（包括根目录）
     * - 删除文件夹及其所有内容
     * 
     * @param dirPath 要删除的文件夹路径
     * @return true 删除成功，false 删除失败
     */
    public static boolean deleteDirectory(String dirPath) {
        return deleteDirectory(Paths.get(dirPath));
    }

    /**
     * 完全删除文件夹（包括根目录）
     * - 删除文件夹及其所有内容
     * 
     * @param root 要删除的文件夹路径
     * @return true 删除成功，false 删除失败
     */
    public static boolean deleteDirectory(Path root) {
        try {
            if (root == null || !Files.exists(root)) {
                System.out.println("⚠️ 文件夹不存在: " + root);
                return false;
            }

            // 递归删除所有内容，包括根目录
            Files.walk(root)
                    .sorted(Comparator.reverseOrder()) // 先删除文件，再删除目录
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            System.err.println("⚠️ 删除失败: " + p + ", " + e.getMessage());
                        }
                    });

            System.out.println("✅ 文件夹已删除: " + root);
            return true;

        } catch (IOException e) {
            System.err.println("❌ 删除文件夹失败: " + root + ", " + e.getMessage());
            return false;
        }
    }

    /**
     * 创建文件夹（如果不存在）
     * 
     * @param dirPath 文件夹路径
     * @return true 创建成功或已存在，false 创建失败
     */
    public static boolean createDirectory(String dirPath) {
        return createDirectory(Paths.get(dirPath));
    }

    /**
     * 创建文件夹（如果不存在）
     * 
     * @param root 文件夹路径
     * @return true 创建成功或已存在，false 创建失败
     */
    public static boolean createDirectory(Path root) {
        try {
            if (Files.exists(root)) {
                return true;
            }

            Files.createDirectories(root);
            System.out.println("✅ 文件夹已创建: " + root);
            return true;

        } catch (IOException e) {
            System.err.println("❌ 创建文件夹失败: " + root + ", " + e.getMessage());
            return false;
        }
    }

    /**
     * 获取文件夹大小（字节）
     * 
     * @param dirPath 文件夹路径
     * @return 文件夹大小（字节），失败返回-1
     */
    public static long getDirectorySize(String dirPath) {
        return getDirectorySize(Paths.get(dirPath));
    }

    /**
     * 获取文件夹大小（字节）
     * 
     * @param root 文件夹路径
     * @return 文件夹大小（字节），失败返回-1
     */
    public static long getDirectorySize(Path root) {
        try {
            if (root == null || !Files.exists(root) || !Files.isDirectory(root)) {
                return -1;
            }

            return Files.walk(root)
                    .filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .sum();

        } catch (IOException e) {
            System.err.println("❌ 获取文件夹大小失败: " + root + ", " + e.getMessage());
            return -1;
        }
    }

    /**
     * 格式化文件大小显示
     * 
     * @param bytes 字节数
     * @return 格式化的大小字符串（如：1.5MB）
     */
    public static String formatSize(long bytes) {
        if (bytes < 0) return "未知";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}