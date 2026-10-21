package cn.guoxy.xunwen.pipeline;

import java.nio.file.Path;

/** 一次入库移动的记录：UI 层据此对移动落点设置监视忽略前缀（防回环，FR-11）。 */
public record MoveRecord(String batchId, Path from, Path to) {}
