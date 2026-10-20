package cn.guoxy.xunwen.index;

public record StoredDoc(
    String path, String name, String categoryPath, String status, String content) {}
