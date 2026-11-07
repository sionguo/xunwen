# 寻文（xunwen）生产就绪度 + 产品特质 + 架构评审

日期：2026-11-07 · 基线：2026-10-27 评审决策（不翻案）· 方法：全量阅读主代码（约 5100 行）+ `mvn verify` 实跑

---

## 一、总评

寻文已经是一个**接近优秀产品水准**的项目：2026-10-27 敲定的七项裁决（删回滚、无 JPMS、kNN 降级、首启三步、降级可见性、手动建树、分层分类）全部落地且与 ADR/规格一致，上次评审点名的四项性能债（每文件 commit、每查询新开 reader、sync 逐文件查询、每次新建 HttpClient）经代码核实**全部已修**。数据安全的主干——SHA-256 去重、ATOMIC_MOVE 原子移动、失败跳过不中断、监视回环双保险——设计扎实且有测试覆盖。距「优秀」还差的是四件具体的事：首次搜索在 FX 线程同步等 embedding（200ms 验收线必破）、LLM 请求体默认 DEBUG 落盘（隐私）、配置文件损坏时应用直接起不动、错误弹窗仍抛英文异常串。这四件都是小改动，修完即可开源发布。

---

## 二、分维度评级

| 维度 | 评级 | 一句话理由 |
|---|---|---|
| a. 数据安全 | 良好 | 移动原子、冲突有据、失败不中断、回环双保险；缺一键重建索引入口 |
| b. 可靠性 | 良好 | 锁/线程/资源生命周期边界清晰；一处后台线程改 UI、watch 不监听删除 |
| c. 性能 | 合格 | 旧债四项全清；首搜阻塞 FX 线程 + 批量任务峰值内存两处新债 |
| d. 产品特质/UX | 良好 | 首启闭环、防呆到位；错误提示可读性与 sync 不可取消拖后腿 |
| e. 工程化/开源就绪 | 良好 | 116 测试全绿、CI 三平台、许可材料齐整；logback 默认 DEBUG 泄内容 |
| f. 架构 | 良好 | RepoSession 收敛后接缝干净、可 mock；UI 层直读 Lucene 属可接受妥协 |
| g. 安全/隐私 | 合格 | 内容外发有 README 声明；DEBUG 日志落内容 + PDFBox 主进程解析扣分 |

`mvn verify` 实测（本机 macOS，Java 25）：**Tests run: 116, Failures: 0, Errors: 0；Spotless 80 文件合规；BUILD SUCCESS，46.4s**。`RealLlmPipelineIT` 按设计不进 verify（类名不带 Test 后缀，手动 `mvn test -Dtest=RealLlmPipelineIT`，见其类注释）。

---

## 三、分维度详细发现（按严重度排序）

严重度：[高] 发布前必须修 / [中] 发布前应修 / [低] 记账可缓

### c. 性能 + d. UX（最高严重度的两条在此）

- **[高] 首次搜索在 FX 线程同步调 embedding 端点，首屏 200ms 线必破** — `ui/SearchController.java:172` 在 `onSearch()`（FX 线程）里调 `state.queryVector(q)` 预检降级；`ui/AppState.java:105-124` 的 `queryVector` 缓存未命中时直接 `llm().embed(query)`（HTTP，请求超时 5 分钟、连接超时 30s，`llm/OpenAiCompatClient.java:23,103`）。本地 Ollama 冷启动一个查询几百 ms，端点挂起时 UI 冻结至连接超时 30s。`:393` 的 `doSearch` 里还会再调一次（缓存命中则免费）。缓存（`AppState.java:138-144`，LRU 128）已解决重复查询，但首次查询的阻塞没有。**建议**：删掉 `:172` 的 FX 线程预检，把降级提示移进 `doSearch` 的后台任务里（返回值带 `embedDown` 标记）。
- **[中] 批量任务峰值内存 GB 级：futures 持有全部 Prepared** — `pipeline/BatchRunner.java:73-84` 一次性 submit 全部文件并保留 `Future<FileProcessor.Prepared>`；`Prepared`（`pipeline/FileProcessor.java:37`）携带 rawText（Tika 上限 100k 字符，`extract/TikaExtractor.java:29`）+ 1024 维向量。1 万份全量重排时，即使串行 commit，已完成的 prepare 结果全部驻留内存，中文文档按 2 字节/char 估算峰值可到 ~2GB。静态推算，**未实测**。**建议**：prepare 完成即压缩内容（commit 只需移动+入库，rawText 可在 upsert 后即弃），或把 prepared 落临时文件/软引用；最简单的做法是 `Prepared` 里只留 embedText + 分类结果，入库 content 用截断版。
- **[低] 混合检索为 200 个候选读全量 content 存储字段** — `index/HybridSearcher.java:58-61` 两路各取 CANDIDATES=100，`storedOf` 读含 content（≤100k 字符）的完整文档，单次搜索搬运潜在 ~20MB 存储；高亮只用 top 50。万级索引尚可，十万级会成为首屏第二项。**建议**：knn/kw 两路只取 path，再按合并后的 topK 读 content。
- **[低] 监视忽略前缀无上界且逐事件线性扫描** — `watch/RepoWatcher.java:44-46` 每次 ingest 把移动落点加进 `ignoredPrefixes`（只增不减），`:115` 每个文件事件线性扫全表。一个会话内处理 1 万文件后，每个新事件做 1 万次 `startsWith`。**建议**：改存落点父目录集合，或入 HashSet。
- 1 小时/万份验收线：分层分类已落地（`classify/Classifier.java:55-61` 强票直通跳过 LLM，`classify/KnnVote.java:14-17` 门槛为邻居≥3、一致占比≥0.75、平均相似度≥0.7），LLM 调用降为「难例才调」，静态评估方向正确；实际吞吐依赖本地 embed 端点延迟，**未实测**。

### e. 工程化/开源就绪 + g. 安全隐私

- **[高] LLM 请求/响应体默认落盘日志** — `src/main/resources/logback.xml:21` 将 `cn.guoxy.xunwen.llm` 固定为 `level="DEBUG"`，而 `llm/OpenAiCompatClient.java:120,130-131` 在 DEBUG 下记录完整请求体（含文件内容预览、类别列表）与响应体到 `~/.xunwen/logs/xunwen.log`（100MB 上限、14 天）。注释写着「调试开关」，实际默认全开。**建议**：默认改 INFO，需要排查时手动开。
- **[中] 配置文件损坏 = 应用无法启动且无提示** — `XunwenApp.init()`（`ui/XunwenApp.java:20-24`）调 `GlobalConfigStore.load`/`RepoRegistry.list`（`config/GlobalConfigStore.java:19-24`、`config/RepoRegistry.java:20-26`），JSON 损坏时 Jackson 3 抛 unchecked `JacksonException`，直接冲出 `init()`，应用启动失败且用户只看到启动器堆栈。**建议**：load 捕获解析异常 → 备份坏文件 → 回退默认配置并提示（或首次进入设置页强制确认）。
- **[中] README 文档漂移两处** — `README.md:32`「索引、类别树、操作日志全部落在本机」中的「操作日志」已在 ADR 0001 裁决下删除（代码中 0 处 `OperationLog`）；`README.md:110` Roadmap 列「重排语义拆分」为 TBD，但 `ReshuffleTask.Mode.INCREMENTAL/FULL`（`pipeline/ReshuffleTask.java:12-16`）已实现。开源首因效应，README 不该有假话。**建议**：删「操作日志」字样、勾掉该 Roadmap 项。
- **[低~~误报~~] api key 输入框** — 更正：`settings-view.fxml:26` 已经是 `PasswordField`（commit 609583b 界面翻新时落地）；`SettingsController.java:15` 声明的是父类型 `TextField`，不影响遮蔽效果。无需改动。config.json 明文存储属规格预期（FR-10）。
- **[低] BatchRunner 无直接单测** — cancel 生效性、prepare 异常按 FAILED 计数（`pipeline/BatchRunner.java:94-99`）两条路径目前只被 `ReshuffleTaskTest`/`IngestTaskTest` 间接覆盖。`WatchServiceBinder`、`RepoOpener`、`Fx.progressBox`、`OcrFallbackExtractor` 无测试（后两者需 UI/OCR 环境，可接受）。
- 开源材料核查通过：LICENSE=Apache-2.0；`THIRD-PARTY-NOTICES.md` 逐项核对过运行时依赖许可（含 JiaJiaOCR 非 Central 的处理说明）；README 双语；`.github/workflows/ci.yml` 三平台矩阵 + JiaJiaOCR 缓存安装；`macsign=false` 为已知缺口且有注释指明开启条件（`pom.xml:328-329`）。

### a. 数据安全

- **[中] 索引损坏无用户侧恢复入口** — 「索引可重建」硬约束成立：sync 以文件系统为准全量 diff（`pipeline/IngestTask.java:61-92`），删掉 `.xunwen/index` 重开即重建；全新索引读侧全部按空结果兜底（`index/RepoIndex.java:66-68,101-103`）。但 Lucene `CorruptIndexException` 会沿 `Fx.error` 弹英文异常串（`ui/Fx.java:68`），没有任何 UI/文档告诉用户「关应用 → 删 `.xunwen/index` → 重新打开即自愈」。**建议**：README 加一段故障恢复说明；进一步可在 open 失败时弹「索引损坏，是否重建」。
- **[低] 批量审批中途失败无明细** — `pipeline/ApprovalService.java:37-48` `approveBatch` 中单项抛异常即整批中断，`:40` try-with-resources 的 close 仍会 commit 已完成项；中断项可能「文件已移动、relocate 未执行」（`:50-66` move 先于 relocate），下轮 sync 自愈（旧路径条目被清理、新路径重新入库），但用户拿到的是异常弹窗而非「成功 N / 失败 M」。**建议**：单失败计数进 BatchStats 式汇报。
- **[低] 入库 hash 字段大部分为空串** — `move/FileMover.java:31` 无同名冲突时不算 SHA-256，`pipeline/FileProcessor.java:127` 沿用空串入库；FR-12 指纹实际只用 mtime+size（`pipeline/IngestTask.java:74-78`），功能不受影响，但字段名与规格承诺不符。
- **[低] 添加仓库无规模/位置防护** — `ui/DashboardController.java:161-174` 选中即入库，选到家目录/系统盘会触发全仓库重排。有「移除仓库」确认且只解除管理不删文件（`:176-193`），风险可控，可在选目录后对文件数超阈值给一次性确认。
- 做得对的（有测试背书）：同名冲突先比 SHA-256、相同跳过不同加 `(n)` 序号（`move/FileMover.java:33-53`，`FileMoverTest` 三用例）；`ATOMIC_MOVE` 跨卷直接抛异常快速失败、绝不静默 copy+delete（`move/FileMover.java:56-58`）；移动/解析失败按 FAILED 跳过不中断整批（`pipeline/FileProcessor.java:162-166`）；移动后清空孤儿目录且保护 `.xunwen`/`待分类`（`move/FileMover.java:63-75`）；审批防呆——未选类别不能归位、批量只处理已选类别、候选类别预选（`ui/PendingController.java:206-210,231-235,183-193`）；watch 回环双保险——MoveRecord 设置忽略前缀 + `findByPath` 幂等跳过（`ui/WatchServiceBinder.java:56-59`、`pipeline/FileProcessor.java:91-94`）。

### b. 可靠性

- **[中] embedDegraded 从后台线程 set，UI 监听器在后台线程改控件** — `ui/AppState.java:116,119` 的 `embedDegraded.set(...)` 会被后台线程调用（`ui/SearchController.java:393` 的 `doSearch` 经 `Fx.runAsync`），而 `ui/DashboardController.java:57` 的监听器直接 `statVector.setText(...)`——JavaFX 监听器在触发线程回调，改场景图属未定义行为。**建议**：AppState 内用 `Platform.runLater` 包裹 set，或监听器里切线程。
- **[中] watch 不监听 ENTRY_DELETE** — `watch/RepoWatcher.java:36-39` 只注册 CREATE/MODIFY；应用打开期间删除的文件其索引条目残留，直到下次打开/手动 sync 才被清理（`pipeline/IngestTask.java:73-77`）。搜索结果会指向已删文件，双击报「无法打开文件」。**建议**：注册 DELETE 直接删索引条目。
- **[低] OVERFLOW 事件静默丢弃** — `watch/RepoWatcher.java:53-55` `continue`；事件风暴时丢文件，靠打开时 sync 兜底，但顶栏无任何「事件溢出」提示。
- **[低] AppState.llm() 的 check-then-act 竞态与旧实例不关闭** — `ui/AppState.java:90-99` 并发首调可能重复建 client（良性，引用比较后一方被弃）；换配置后旧 `HttpClient` 不 close，selector 线程滞留至空闲超时。量级无害，记账。
- **[低] PendingController.reload 无去抖** — `ui/PendingController.java:119-120` 双监听器（currentRepo + dataChanged）可触发重叠的 runAsync，后完成者覆盖先完成者；数据自愈，仅可能闪旧数据。
- 做得对的：单写者引擎锁 + 只读快照并发（`ui/Fx.java:38-52`、`index/RepoIndex.java:61-68`）；NRT 共享读视图，批次边界 close 统一 commit（`index/RepoIndex.java:91-114,352-367`）；Tika 解析在 fork JVM 隔离 + 60s 超时 + 崩溃自动按无文本层兜底 + 关闭钩子（`extract/TikaExtractor.java:13-24,37-59`）；crash 恢复路径成立——移动先于索引提交，崩溃后 sync 以文件系统为准补账（`pipeline/IngestTask.java:61-92`）。

### d. 产品特质/UX

- **[中] 错误弹窗直接给 `String.valueOf(t)`** — `ui/Fx.java:68`：用户看到的是 `java.net.ConnectException: ...` 这类英文异常串；`SettingsController.rootMessage`（`ui/SettingsController.java:85-92`）已经实现了「取根因消息」的人话化，却只用于连接测试，不用于错误弹窗。**建议**：`Fx.error` 复用 rootMessage，标题保留中文上下文即可。
- **[中] 打开仓库的 sync 不可取消** — `ui/Fx.java:79-81` 进度框取消键禁用（注释「v1 任务不可中断，按钮仅占位」），`RepoOpener.open` 的 sync 未传取消标志；重排已有完整取消链路（`ui/TreeController.java:355-357,380-385` + `pipeline/BatchRunner.java:87-91`）。规格 FR 第 3 条承诺「进度可暂停」（`docs/xunwen-v1-spec.md:13`），重排达标、打开仓库的积压 sync 未达。**建议**：把 TreeController 的 cancelledFlag 模式搬到 RepoOpener。
- **[低] 空仓库冷启动不拦截** — `classify/ColdStartSampler.java:20-57` 对 0 文件仓库返回空样本，`classify/TreeProposer.java:26-66` 不检查直接调 LLM 生成无据之树。建议样本为空时直接弹「仓库没有可读取的文件」。
- 做得对的：首启三步真闭环——选目录 → 无树则弹「采样生成（LLM）/手动建树（无 LLM 可用）」二选一（`ui/RepoOpener.java:24-46`），空提案走右键编辑 + 批准回读（`ui/RepoOpener.java:74-80`、`ui/TreeController.java:228-255`），重启后提案持久化恢复（`ui/TreeController.java:110-124`、`RepoPaths.proposalYaml`）；批准入树强制补「其他」兜底（`ui/TreeController.java:239-243`）且不可删除（`:437-439`）；降级可见性单一状态源——顶栏状态卡 + 搜索提示「向量化服务不可用，当前为纯关键词检索」（`ui/DashboardController.java:56-57`、`ui/SearchController.java:184-186`）；活动摘要顶栏常驻 + 5 条历史（`ui/MainController.java:79-92`）；审批页打开不调 LLM（`ui/PendingController.java:28`，提议读索引聚合）。

### f. 架构

- **[低] UI 层直读 Lucene** — 5 处控制器直接 `RepoIndex.openReadOnly`（`ui/PendingController.java:149`、`ui/DashboardController.java:123`、`ui/SearchController.java:220,404`、`ui/TreeController.java:135`），Lucene 类型渗入 UI。只读、模式统一（try-with-resources + 不进引擎锁），在当前规模下是可接受妥协；若后续加第二索引实现（或做服务化）再收口为查询服务。不算债，记为边界决策。
- **[低] 两个控制器接近上帝类上限** — `ui/TreeController.java`（474 行：提案编辑 + 重排 + 取消 + 右键菜单四职责）、`ui/SearchController.java`（420 行：搜索 + 浏览双模式 + 分面）。内聚尚可、私有状态互不纠缠；下一刀是抽 `FacetPanel` 与「提案编辑器」。
- 做得对的：`RepoSession`（`pipeline/RepoSession.java:12-13`）收敛后装配点唯一（`ui/AppState.java:154-156`），后台任务单构造参数，配置变更引用比较自动失效重建；依赖方向单向（ui → pipeline → classify/index/move/llm，无反向）；`FileStatus` 是三包共享持久化协议的唯一 owner（`index/FileStatus.java:6-9`）；可测试性接缝齐全——`LlmClient`/`EmbeddingClient`/`ContentExtractor` 三个接口，测试用 FakeLlm（`pipeline/FileProcessorTest.java:26`）+ `@TempDir`，无需起 Lucene 外部依赖；`BatchRunner` 消灭了两份编排（`pipeline/BatchRunner.java:15-18`）；`CategoryTree.find` 是类别路径的唯一校验口（`repo/CategoryTree.java:27-41`，深度>2 直接拒绝）。

### g. 安全/隐私（产品事实，须让用户知情）

- **文件内容会发送到用户配置的 LLM 端点**（含默认 `http://localhost:11434/v1`）：分类发送文件名 + 内容前 3000 字符（`classify/Classifier.java:41,84-88`），向量化发送前 4000 字符（`pipeline/FileProcessor.java:34,97`），冷启动采样每个样本 500 字符 ×20 文件（`classify/TreeProposer.java:33-35`、`ui/TreeController.java:205`）。README:32 有「唯一的网络请求是调用你自己配置的 LLM 端点」声明，但未直说「文件内容」会出去；建议把措辞改明确。
- **路径遍历：无风险** — 分类结果必须命中类别树白名单才生效（`classify/Classifier.java:121-122` → `repo/CategoryTree.java:31-33`），`FileMover` 落点恒为 `repoRoot.resolve(白名单路径)`；索引路径全部 relativize 后按 POSIX 存储（`pipeline/FileProcessor.java:201-203`）。
- **不可信文件解析**：Tika 在 fork 进程隔离 + 60 秒超时 + 崩溃兜底（`extract/TikaExtractor.java:13-24,37-59`），是同类桌面工具里的好实践；残余面是扫描 PDF 的 PDFBox 光栅化 + OpenCV 在主进程内解析不可信 PDF（`extract/OcrFallbackExtractor.java:51-56`），记账即可。

---

## 四、与 2026-10-27 评审决策的符合度核对表

| 决策 | 现状（证据） | 结论 |
|---|---|---|
| 删批次回滚 + OperationLog（ADR 0001） | 代码 0 处 RollbackService/OperationLog；`MoveRecord` 仅用于监视回环过滤（`pipeline/MoveRecord.java:5`），非审计日志 | 符合（README:32 残留一处「操作日志」字样，文档漂移） |
| classpath 无 JPMS（ADR 0002） | 无 module-info.java，pom 无 requires/opens 配置 | 符合 |
| kNN 降级 + 强票门槛（ADR 0003：邻居≥3、一致占比≥0.75、平均相似度≥0.7） | `classify/KnnVote.java:14-17` 三常量逐一精确匹配；LLM 不可达时弱票兜底（`classify/Classifier.java:62-70`） | 符合 |
| 分层分类解 1h 冲突（强票直通/弱票 LLM/兜底「其他」） | 强票直通跳 LLM（`Classifier.java:55-61`），LLM 不可达弱票兜底，无票异常上抛计 FAILED | 符合（吞吐未实测） |
| 首启三步（选目录→建索引→能搜）无向导 | `RepoOpener.open` 引导 + `IngestTask.sync` 后台建索引，搜索页即搜 | 符合（重排按钮在树页，属「三步后」动作） |
| 降级可见性 = AppState.embedDegraded 单一状态源 | `ui/AppState.java:127-135`；Dashboard 状态卡与搜索提示共同消费 | 符合（set 的线程边界有 bug，见可靠性 [中]） |
| 手动建树（RepoOpener 空提案 + TreeController） | `RepoOpener.java:74-80` 落空提案 + TreeController 右键编辑/批准回读（`TreeController.java:228-274`） | 符合 |
| 性能债四项（每文件 commit / 每查询新开 reader / sync 逐文件 findByPath / llm() 每次 new HttpClient） | ① dirty 标记 + close 单次 commit（`RepoIndex.java:138-139,355-357`）② 共享读视图 + NRT 刷新（`:91-114`）③ sync 指纹表 + indexed 集合一次建表（`IngestTask.java:61-92`）④ llmClient 复用 + 引用比较失效重建（`AppState.java:86-99`） | 全部已修 |

---

## 五、开源发布前必须修完（按性价比排序，≤8 条）

1. **首次搜索移出 FX 线程**：删 `ui/SearchController.java:172` 预检，降级提示改由后台搜索结果携带——一举修掉 UI 冻结与 200ms 线破。
2. **logback 默认关掉 llm DEBUG**：`logback.xml:21` 改 INFO——文件内容默认落盘属隐私事故级默认值。
3. **配置损坏自愈**：`GlobalConfigStore.load`/`RepoRegistry.list` 捕获解析异常，备份坏文件 + 回退默认并提示——否则用户手滑编辑坏 JSON 后应用永远起不动。
4. **embedDegraded 线程安全**：`ui/AppState.java:116-119` 的 set 包 `Platform.runLater`。
5. **Fx.error 人话化**：`ui/Fx.java:68` 复用 `SettingsController.rootMessage`，弹根因消息不弹异常 toString。
6. **sync 可取消**：把 TreeController 的 cancelledFlag 模式接入 RepoOpener（顺带兑现规格「进度可暂停」）。
7. **README 修正漂移 + 明确隐私**：删「操作日志」（README:32）、Roadmap 勾掉已实现的「重排语义拆分」（:110）、把「文件内容会发送到所配端点」写明。
8. ~~**api key 改 PasswordField**~~：核实为误报——FXML 已是 `PasswordField`（见上文更正）。

---

*未验证项：1 万份文档全量索引实际吞吐（需真实 LLM 端点与语料，静态评估见性能节）；TestFX UI 测试在 Windows/Linux CI 上的实际表现（本机仅跑了 macOS 全量）。*

---

## 六、修复记录（2026-11-07 当日）

- #1 首搜移出 FX 线程：删 `SearchController.onSearch` 的 FX 线程预检，`doSearch` 返回 `SearchResult(hits, embedDown)` 由后台结果携带降级标记。
- #2 logback `cn.guoxy.xunwen.llm` 默认改 INFO（请求/响应体不再默认落盘）。
- #3 配置损坏自愈：`GlobalConfigStore.load`/`RepoRegistry.list` 捕获 `JacksonException`，坏文件改名 `.corrupt` 留存 + WARN 日志 + 回退默认（ConfigTest 两个新用例覆盖，TDD）。
- #4 `embedDegraded` 线程边界：与报告建议的「AppState 包 runLater」不同，改为 DashboardController 监听器内 `Platform.runLater`——保持 `AppStateTest` 可无 FX 平台 headless 运行（属性分发线程本就是触发线程，缺陷在消费方改场景图）。
- #5 `Fx.rootMessage` 从 SettingsController 上提，`Fx.error` 弹根因消息（FxTest 覆盖，TDD）。
- #6 打开仓库 sync 可取消：`Fx.progressBox` 增加取消回调启用按钮，`RepoOpener` 以 cancelledFlag 复用 TreeController 模式接入 `BatchRunner.Progress.cancelled`。
- #7 README/README_EN 删「操作日志」残留、删已实现的 Roadmap「重排语义拆分」、隐私措辞写明「分类/向量化/建树采样会把文件名与内容片段发送到所配端点」。
- #8 误报更正，无改动（FXML 已是 PasswordField）。
