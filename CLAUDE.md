# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目

xunwen(寻文)— 基于 Lucene 全文检索的 JavaFX 桌面应用。Java 25,classpath 非模块化构建。UI 栈:JavaFX 27 + ControlsFX + Ikonli + AtlantaFX;日志 SLF4J + Logback;测试 JUnit 5。

## 常用命令

- 运行应用:`mvn javafx:run`
- 全量验证:`mvn verify`(编译 + 测试 + spotless 格式检查,格式不合规会失败)
- 格式化单个文件:`java -jar tools/google-java-format-1.33.0-all-deps.jar --replace <file>` — 编辑 `.java` 文件后 PostToolUse hook 会自动执行,无需手动跑
- 全项目格式化:`mvn spotless:apply`
- 单个测试:`mvn test -Dtest=<TestClass>`
- 打包发布:`mvn -Pdist clean package -DskipTests`(macOS 出 DMG、Windows 出 MSI、Linux 出 DEB,产物在 `target/jpackage/`;`-Djpackage.type=IMAGE` 只出 .app/目录,便于快速验证)

## 架构约束

- **非模块化**:classpath 构建,无 `module-info.java`(commit `3b8d8c5` 移除,决策见 `docs/adr/0002`);引入新依赖直接加 pom,不需要 requires/opens。
- **格式**:Spotless 强制 google-java-format(GOOGLE 风格),版本钉在 1.33.0 — pom 配置与 `tools/` 下 hook 用的 jar 是同一个版本,改一边必须同步改另一边。
- **入口**:`Launcher.main` → `Application.launch(XunwenApp.class)`,与 JavaFX 解耦的启动类,供 jpackage classpath 启动;`javafx-maven-plugin` 的 mainClass 为 `cn.guoxy.xunwen.Launcher`。

## 打包发布

- `dist` profile 用 jpackage(classpath 模式 + `Launcher` 解耦主类)打原生安装包;运行时参数含 `--add-modules=jdk.incubator.vector`(Lucene KNN)与 `--enable-native-access=ALL-UNNAMED`(Lucene mmap)。
- 平台差异(类型/图标/win・linux 参数)由 `dist-mac` / `dist-win` / `dist-linux` 三个按 OS 激活的属性 profile 注入;akman 插件(0.1.5)不做按 OS 过滤,靠「非当前平台参数置空即跳过」实现。新增平台参数时两处(占位默认值 + OS profile)必须成对改。
- `appversion` 与 `project.version` 手工同步维护(jpackage 不接受 SNAPSHOT)。
- 对外分发需 Developer ID 签名 + 公证(`macsign` 现为 false,仅 ad-hoc;未签名包在别的 Mac 上需右键打开)。
- 应用图标源文件 `packaging/IconGen.java`(纯 JDK),重新生成:`java packaging/IconGen.java packaging`。

## 提交规范

提交信息遵循 Conventional Commits 中文适配:`<type>(<scope>): <中文描述>`。

- **type**(英文):feat / fix / docs / style / refactor / perf / test / build / ci / chore / revert
- **scope**(可选,英文):用本仓库包名,如 `classify`、`index`、`llm`、`pipeline`、`move`;跨模块改动省略 scope
- **描述**:中文动宾短语(「新增 xxx」「修复 xxx」「优化 xxx」),不超过 50 字符,不加句号结尾,中英文/数字之间加一个空格,如 `feat(index): 新增增量索引`
- **正文**(可选):说明变更原因、方案要点、影响范围;与标题之间空一行,每行不超过 72 字符
- **不兼容变更**:必须用 footer `BREAKING CHANGE: <说明及迁移方法>` 或在 type 后加 `!` 标注
- **原子性**:一个提交只做一件事(一个功能点或一个缺陷修复),接口定义与实现等关联文件放同一提交;提交前删除废弃函数和注释掉的代码块

## 代码约束
- 生成或修改代码时，优先使用 import 导入，除非遇到同名类冲突，否则不要写全类名。