<h1 align="center">寻文 Xunwen</h1>

<p align="center">
  <img src="packaging/Xunwen.png" width="128" alt="寻文图标">
</p>

<p align="center">
  <strong>本地文档自动归类 + 混合检索的桌面工具</strong><br>
  监视你的资料仓库,用 LLM 把文件自动归入类别树,用 Lucene 建立「关键词 + 语义」混合索引
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-25-orange" alt="Java 25">
  <img src="https://img.shields.io/badge/JavaFX-27-blue" alt="JavaFX 27">
  <img src="https://img.shields.io/badge/Lucene-10.5-green" alt="Lucene 10.5">
  <img src="https://img.shields.io/badge/License-Apache_2.0-blue.svg" alt="Apache 2.0">
</p>

## 特性

- **自动归类** — LLM 为每个文件选择候选类别并自报置信度;高分自动移动、低分进入「待分类」暂存区、中间地带进入待审队列,由你一键批准或改判。向量一致性钳制:文件向量与类别向量余弦不一致时压回待审,两路证据一致才自动移动。
- **冷启动与类别演化** — 添加仓库后 LLM 抽样归纳两级类别树提案,批准即生效;后续文件进不了现有类别时,LLM 提议新类别,相似提议自动聚合,批准后相关文件归位。
- **全仓库重排** — 一键把仓库重排为纯类别树(`一级类别/二级类别/文件`);重名冲突按 SHA-256 去重或序号避让。
- **混合检索** — BM25(smartcn 中文分词)+ KNN 向量(HNSW)RRF 融合排序,结果带高亮片段;默认搜当前仓库,也可全仓库联合检索。
- **文档解析** — Apache Tika 通吃 pdf / docx / xlsx / pptx / txt / md;扫描件 PDF 自动走 PaddleOCR(ONNX,纯 Java)兜底提取。
- **增量监视** — GUI 打开期间 WatchService 递归监视,写入稳定检测(连续采样不变)避免处理半截文件;工具自身移动产生的事件自动回环过滤。
- **多仓库** — 仓库互不干扰:每仓库独立类别树、独立索引;索引与元数据存于仓库内 `.xunwen/` 目录,随仓库走,删仓库即删索引。
- **本地优先** — 索引、类别树、操作日志全部落在本机;唯一的网络请求是调用你自己配置的 LLM 端点。

## 快速开始

### 环境要求

- JDK 25+(推荐 [BellSoft Liberica](https://bell-sw.com/),已验证)
- 一个 OpenAI 兼容的 LLM 端点(默认 `http://localhost:11434/v1`,即本机 Ollama;vLLM / DeepSeek / GLM 等均可)

用 Ollama 的话,准备一个对话模型 + 一个向量模型即可:

```bash
ollama pull qwen3:4b
ollama pull bge-m3
```

### 从源码运行

```bash
git clone https://github.com/sionguo/xunwen.git
cd xunwen
./mvnw javafx:run
```

首次启动会引导你添加仓库:选择文件夹 → LLM 生成类别树提案 → 批准 → 立即重排,之后日常增量自动处理。

### 安装包

```bash
./mvnw -Pdist clean package -DskipTests
```

产物在 `target/jpackage/`:macOS 出 DMG、Windows 出 MSI、Linux 出 DEB(内置 JRE,无需安装 Java)。加 `-Djpackage.type=IMAGE` 只出 `.app`/绿色目录,便于快速验证。

> macOS 包当前为 ad-hoc 签名:拷贝到其他 Mac 首次打开需右键 → 打开绕过 Gatekeeper。

## 配置

全局配置在 `~/.xunwen/config.json`(GUI 设置页可视化修改):LLM base-url、api-key、对话模型、向量模型与维度、置信度阈值、分类并发。仓库列表在 `~/.xunwen/repos.json`。

每个仓库内的数据布局:

```
<仓库>/
  .xunwen/
    index/             # Lucene 索引
    categories.yaml    # 类别树(可手工编辑)
  待分类/               # 低置信文件的物理暂存区
  <一级类别>/
    <二级类别>/
      文件
```

## 工作原理

```
监视/扫描 → 内容提取(Tika/OCR) → LLM 分类(选择题 + 置信度)
  ├─ 置信度 ≥ 0.85        → 自动移动到类别目录
  ├─ 0.5 ≤ 置信度 < 0.85   → 待审队列,人工批准
  └─ 置信度 < 0.5 / 无类别  → 待分类暂存区
每一步同步修正 Lucene 索引(BM25 + KNN 向量),检索时 RRF 融合
```

## 开发

```bash
./mvnw verify          # 全量验证:编译 + 测试 + spotless 格式检查
./mvnw test -Dtest=X   # 单个测试
./mvnw spotless:apply  # 自动格式化(google-java-format)
java packaging/IconGen.java packaging   # 重新生成应用图标
```

技术栈:Java 25 · JavaFX 27(classpath + Launcher 解耦启动)· Lucene 10.5 · Apache Tika 4 · Jackson 3 · JDK HttpClient。设计与实现细节见 [docs/xunwen-v1-spec.md](docs/xunwen-v1-spec.md)。

## Roadmap

- [ ] 重排语义拆分:增量重分类 vs 全量重排
- [ ] RAG 问答(当前为纯检索)
- [ ] 音视频转写、常驻托盘后台

## License

[Apache-2.0](LICENSE)
