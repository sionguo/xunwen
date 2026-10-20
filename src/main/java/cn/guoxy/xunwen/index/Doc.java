package cn.guoxy.xunwen.index;

public record Doc(
    String path,
    String name,
    String ext,
    String hash,
    long mtime,
    long size,
    String status,
    String categoryPath,
    String content,
    float[] vector) {}
