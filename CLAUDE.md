# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目

xunwen(询问)— 基于 Lucene 全文检索的 JavaFX 桌面应用。Java 25,JPMS 模块化(`module-info.java`)。UI 栈:JavaFX 27 + ControlsFX + Ikonli + AtlantaFX;日志 SLF4J + Logback;测试 JUnit 5。

## 常用命令

- 运行应用:`mvn javafx:run`
- 全量验证:`mvn verify`(编译 + 测试 + spotless 格式检查,格式不合规会失败)
- 格式化单个文件:`java -jar tools/google-java-format-1.33.0-all-deps.jar --replace <file>` — 编辑 `.java` 文件后 PostToolUse hook 会自动执行,无需手动跑
- 全项目格式化:`mvn spotless:apply`
- 单个测试:`mvn test -Dtest=<TestClass>`

## 架构约束

- **JPMS**:引入新依赖时必须同时在 `src/main/java/module-info.java` 加 `requires`;新增包含 FXML controller 的包时需在 `module-info.java` 中 `opens` 给 `javafx.fxml`。
- **格式**:Spotless 强制 google-java-format(GOOGLE 风格),版本钉在 1.33.0 — pom 配置与 `tools/` 下 hook 用的 jar 是同一个版本,改一边必须同步改另一边。
- **入口**:`Launcher.main` → `Application.launch(HelloApplication.class)`,这是 JPMS 下启动 JavaFX 的标准模式;`javafx-maven-plugin` 的 mainClass 为 `cn.guoxy.xunwen/cn.guoxy.xunwen.HelloApplication`。

## 代码约束
- 生成或修改代码时，优先使用 import 导入，除非遇到同名类冲突，否则不要写全类名。