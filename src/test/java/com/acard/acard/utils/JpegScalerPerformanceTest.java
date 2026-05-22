package com.acard.acard.utils;

/**
 * JPEG 缩放器性能对比测试
 * 
 * 使用方式：
 * 1. 确保 runtime/captures/ssl 目录下有 JPEG 文件
 * 2. 运行 main 方法
 * 3. 查看性能对比结果
 */
public class JpegScalerPerformanceTest {

    public static void main(String[] args) {
        System.out.println("=".repeat(80));
        System.out.println("JPEG 缩放器性能对比测试");
        System.out.println("=".repeat(80));
        
        // 测试文件路径
        String testDir = "runtime/captures/ssl";
        String outputDirV1 = "runtime/test_output_v1";
        String outputDirV2 = "runtime/test_output_v2";
        
        // 创建输出目录
        new java.io.File(outputDirV1).mkdirs();
        new java.io.File(outputDirV2).mkdirs();
        
        // 获取测试文件（前100个）
        java.io.File dir = new java.io.File(testDir);
        java.io.File[] files = dir.listFiles((d, name) -> name.endsWith(".jpeg"));
        
        if (files == null || files.length == 0) {
            System.err.println("❌ 测试目录为空: " + testDir);
            System.err.println("   请确保目录下有 JPEG 文件");
            return;
        }
        
        int testCount = Math.min(100, files.length);
        System.out.println("📊 测试文件数量: " + testCount);
        System.out.println();
        
        // ============= 测试原版 GStreamerJpegScaler =============
        System.out.println("🔧 测试原版 GStreamerJpegScaler...");
        System.out.println("-".repeat(80));
        
        long v1TotalTime = 0;
        long v1MinTime = Long.MAX_VALUE;
        long v1MaxTime = 0;
        int v1SuccessCount = 0;
        
        GStreamerJpegScaler scalerV1 = GStreamerJpegScaler.getInstance();
        System.out.println("   配置: " + scalerV1.getConfigInfo());
        
        // 预热（首次调用会慢一些）
        String warmupSource = files[0].getAbsolutePath();
        String warmupTarget = outputDirV1 + "/warmup.jpeg";
        scalerV1.scaleAndSave(warmupSource, warmupTarget);
        System.out.println("   预热完成\n");
        
        long v1StartTime = System.nanoTime();
        
        for (int i = 0; i < testCount; i++) {
            String source = files[i].getAbsolutePath();
            String target = outputDirV1 + "/test_" + i + ".jpeg";
            
            long startTime = System.nanoTime();
            String result = scalerV1.scaleAndSave(source, target);
            long elapsed = (System.nanoTime() - startTime) / 1_000_000;
            
            if (result != null) {
                v1SuccessCount++;
                v1TotalTime += elapsed;
                v1MinTime = Math.min(v1MinTime, elapsed);
                v1MaxTime = Math.max(v1MaxTime, elapsed);
            }
            
            if ((i + 1) % 10 == 0) {
                System.out.print(".");
            }
        }
        
        long v1Duration = (System.nanoTime() - v1StartTime) / 1_000_000;
        double v1AvgTime = v1SuccessCount > 0 ? (double) v1TotalTime / v1SuccessCount : 0;
        double v1Throughput = v1Duration > 0 ? (double) v1SuccessCount * 1000 / v1Duration : 0;
        
        System.out.println("\n");
        System.out.println("📈 原版性能统计:");
        System.out.println("   成功数量: " + v1SuccessCount + "/" + testCount);
        System.out.println("   总耗时: " + v1Duration + "ms");
        System.out.println("   平均耗时: " + String.format("%.2f", v1AvgTime) + "ms");
        System.out.println("   最快耗时: " + v1MinTime + "ms");
        System.out.println("   最慢耗时: " + v1MaxTime + "ms");
        System.out.println("   吞吐量: " + String.format("%.2f", v1Throughput) + " 帧/秒");
        System.out.println();
        
        // ============= 测试 V2 GStreamerJpegScalerV2 =============
        System.out.println("🚀 测试 V2 GStreamerJpegScalerV2...");
        System.out.println("-".repeat(80));
        
        long v2TotalTime = 0;
        long v2MinTime = Long.MAX_VALUE;
        long v2MaxTime = 0;
        int v2SuccessCount = 0;
        
        GStreamerJpegScalerV2 scalerV2 = GStreamerJpegScalerV2.getInstance();
        System.out.println("   配置: " + scalerV2.getConfigInfo());
        
        // 等待 Pipeline 就绪
        int waitCount = 0;
        while (!scalerV2.isReady() && waitCount < 50) {
            try {
                Thread.sleep(100);
                waitCount++;
            } catch (InterruptedException e) {
                break;
            }
        }
        
        if (!scalerV2.isReady()) {
            System.err.println("❌ V2 Pipeline 未就绪，跳过测试");
        } else {
            System.out.println("   Pipeline 就绪\n");
            
            long v2StartTime = System.nanoTime();
            
            for (int i = 0; i < testCount; i++) {
                String source = files[i].getAbsolutePath();
                String target = outputDirV2 + "/test_" + i + ".jpeg";
                
                long startTime = System.nanoTime();
                String result = scalerV2.scaleAndSave(source, target);
                long elapsed = (System.nanoTime() - startTime) / 1_000_000;
                
                if (result != null) {
                    v2SuccessCount++;
                    v2TotalTime += elapsed;
                    v2MinTime = Math.min(v2MinTime, elapsed);
                    v2MaxTime = Math.max(v2MaxTime, elapsed);
                }
                
                if ((i + 1) % 10 == 0) {
                    System.out.print(".");
                }
            }
            
            long v2Duration = (System.nanoTime() - v2StartTime) / 1_000_000;
            double v2AvgTime = v2SuccessCount > 0 ? (double) v2TotalTime / v2SuccessCount : 0;
            double v2Throughput = v2Duration > 0 ? (double) v2SuccessCount * 1000 / v2Duration : 0;
            
            System.out.println("\n");
            System.out.println("📈 V2 性能统计:");
            System.out.println("   成功数量: " + v2SuccessCount + "/" + testCount);
            System.out.println("   总耗时: " + v2Duration + "ms");
            System.out.println("   平均耗时: " + String.format("%.2f", v2AvgTime) + "ms");
            System.out.println("   最快耗时: " + v2MinTime + "ms");
            System.out.println("   最慢耗时: " + v2MaxTime + "ms");
            System.out.println("   吞吐量: " + String.format("%.2f", v2Throughput) + " 帧/秒");
            System.out.println();
            
            // ============= 性能对比 =============
            System.out.println("=".repeat(80));
            System.out.println("⚡ 性能对比总结");
            System.out.println("=".repeat(80));
            
            if (v1SuccessCount > 0 && v2SuccessCount > 0) {
                double speedup = v1AvgTime / v2AvgTime;
                double throughputImprovement = v2Throughput / v1Throughput;
                
                System.out.println("📊 平均耗时:");
                System.out.println("   原版: " + String.format("%.2f", v1AvgTime) + "ms");
                System.out.println("   V2版: " + String.format("%.2f", v2AvgTime) + "ms");
                System.out.println("   提升: " + String.format("%.2f", speedup) + "x");
                System.out.println();
                
                System.out.println("📊 吞吐量:");
                System.out.println("   原版: " + String.format("%.2f", v1Throughput) + " 帧/秒");
                System.out.println("   V2版: " + String.format("%.2f", v2Throughput) + " 帧/秒");
                System.out.println("   提升: " + String.format("%.2f", throughputImprovement) + "x");
                System.out.println();
                
                System.out.println("📊 总耗时:");
                System.out.println("   原版: " + v1Duration + "ms");
                System.out.println("   V2版: " + v2Duration + "ms");
                System.out.println("   节省: " + (v1Duration - v2Duration) + "ms");
                System.out.println();
                
                if (speedup >= 5.0) {
                    System.out.println("🚀🚀🚀 V2 版本性能提升显著！建议立即升级！");
                } else if (speedup >= 2.0) {
                    System.out.println("🚀🚀 V2 版本性能明显提升！推荐升级！");
                } else if (speedup >= 1.2) {
                    System.out.println("🚀 V2 版本有一定性能提升");
                } else {
                    System.out.println("⚠️ V2 版本性能提升不明显，可能受限于硬件或测试环境");
                }
            }
        }
        
        System.out.println("=".repeat(80));
        System.out.println("✅ 测试完成");
        System.out.println("=".repeat(80));
    }
}





