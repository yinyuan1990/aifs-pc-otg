package com.acard.acard.store;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * 帧存储抽象：提供写入原始帧与读取最近 N 帧能力。
 * 保持与运行模式无关（classpath / JPMS），便于替换不同后端实现。
 */
public interface FrameStore {
  /** 写入一帧（原始图像与时间戳，毫秒） */
  void appendFrame(BufferedImage src, long timestamp);

  /** 读取最近 N 帧（时间顺序） */
  List<BufferedImage> getLastNImages(int n);

  /** 释放资源（LMDB 环境、文件句柄等） */
  void close();
}