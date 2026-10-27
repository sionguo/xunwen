package cn.guoxy.xunwen.pipeline;

import cn.guoxy.xunwen.config.GlobalConfig;
import cn.guoxy.xunwen.extract.ContentExtractor;
import cn.guoxy.xunwen.llm.LlmClient;
import java.nio.file.Path;

/**
 * 仓库会话：一个仓库与其处理资源（配置/LLM 客户端/提取器）的装配快照。 经 AppState.sessionFor(repo) 获取——同配置复用 LLM
 * 客户端，配置变更自动失效重建；extractor 应用级共享。 后台任务（入库/重排/审批）以它为唯一构造参数。
 */
public record RepoSession(
    Path repo, GlobalConfig config, LlmClient llm, ContentExtractor extractor) {}
