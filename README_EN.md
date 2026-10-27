<h1 align="center">Xunwen 寻文</h1>

<p align="center">
  <img src="packaging/Xunwen.png" width="128" alt="Xunwen icon">
</p>

<p align="center">
  <strong>A desktop tool that auto-organizes local documents and searches them with hybrid retrieval</strong><br>
  It watches your document folders, classifies files into a category tree with an LLM, and builds a "keyword + semantic" hybrid index with Lucene.
</p>

<p align="center">
  English | <a href="README.md">简体中文</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-25-orange" alt="Java 25">
  <img src="https://img.shields.io/badge/JavaFX-27-blue" alt="JavaFX 27">
  <img src="https://img.shields.io/badge/Lucene-10.5-green" alt="Lucene 10.5">
  <img src="https://img.shields.io/badge/License-Apache_2.0-blue.svg" alt="Apache 2.0">
</p>

## Features

- **Auto classification** — An LLM picks candidate categories for each file and reports its own confidence: high scores are moved automatically, low scores go to a physical staging folder (待分类, "Unclassified"), and the middle band enters a review queue for you to approve or override with one click. Vector-consistency clamping: when the file vector disagrees with the category vectors (cosine), confidence is pushed back into the review queue — files only move automatically when both evidence channels agree.
- **Cold start & category evolution** — After adding a folder, the LLM samples files and proposes a category tree; approving brings it to life. When later files don't fit existing categories, the LLM proposes new ones, similar proposals are aggregated automatically, and approving one relocates the affected files.
- **Full-library reshuffle** — Rearrange the whole library into a pure category tree (`category/subcategory/file`) with one click; name conflicts are deduplicated by SHA-256 or resolved with a numeric suffix.
- **Hybrid search** — BM25 (smartcn Chinese analyzer) + KNN vectors (HNSW) fused with RRF ranking, results with highlighted snippets; searches the current library by default, or all libraries jointly.
- **Document parsing** — Apache Tika handles pdf / docx / xlsx / pptx / txt / md; scanned PDFs automatically fall back to PaddleOCR (ONNX, pure Java) for text extraction.
- **Incremental watching** — While the GUI is open, a WatchService recursively watches the library with write-stability detection (repeated identical sampling) so half-written files are never processed; events caused by the tool's own moves are filtered out to prevent loops.
- **Multiple libraries** — Libraries are fully isolated: each has its own category tree and index; the index and metadata live inside the library's `.xunwen/` folder, travel with it, and deleting the folder deletes the index.
- **Local-first** — The index, category tree, and operation data all stay on your machine; the only network traffic is calls to the LLM endpoint you configure.

## Getting Started

### Requirements

- JDK 25+ ([BellSoft Liberica](https://bell-sw.com/) recommended and verified)
- An OpenAI-compatible LLM endpoint (default `http://localhost:11434/v1`, i.e. local Ollama; vLLM / DeepSeek / GLM etc. all work)

With Ollama, prepare one chat model + one embedding model:

```bash
ollama pull qwen3:4b
ollama pull bge-m3
```

### Run from source

```bash
git clone https://github.com/sionguo/xunwen.git
cd xunwen
./mvnw javafx:run
```

On first launch you'll be guided to add a library: pick a folder → the LLM proposes a category tree → approve → reshuffle right away; day-to-day increments are handled automatically afterwards.

### Installers

```bash
./mvnw -Pdist clean package -DskipTests
```

Artifacts land in `target/jpackage/`: DMG on macOS, MSI on Windows, DEB on Linux (bundled JRE, no Java installation needed). Add `-Djpackage.type=IMAGE` to produce just the `.app`/portable directory for quick verification.

> The macOS build is currently ad-hoc signed: when copied to another Mac, right-click → Open to bypass Gatekeeper on first launch.

## Configuration

Global settings live in `~/.xunwen/config.json` (editable in the GUI settings page): LLM base URL, API key, chat model, embedding model and dimension, confidence thresholds, classification concurrency. The library list is in `~/.xunwen/repos.json`.

Data layout inside each library:

```
<library>/
  .xunwen/
    index/             # Lucene index
    categories.yaml    # category tree (hand-editable)
  待分类/               # "Unclassified": physical staging area for low-confidence files
  <category>/
    <subcategory>/
      files
```

## How It Works

```
watch/scan → content extraction (Tika/OCR) → LLM classification (multiple choice + confidence)
  ├─ confidence ≥ 0.85        → auto-move into the category folder
  ├─ 0.5 ≤ confidence < 0.85  → review queue, human approval
  └─ confidence < 0.5 / none  → "Unclassified" staging area
Every step keeps the Lucene index (BM25 + KNN vectors) in sync; retrieval fuses both channels with RRF
```

## Development

```bash
./mvnw verify          # full verification: compile + tests + spotless format check
./mvnw test -Dtest=X   # single test class
./mvnw spotless:apply  # auto-format (google-java-format)
java packaging/IconGen.java packaging   # regenerate the app icon
```

Tech stack: Java 25 · JavaFX 27 (classpath build with a decoupled Launcher) · Lucene 10.5 · Apache Tika 4 · Jackson 3 · JDK HttpClient. Design and implementation details (Chinese): [docs/xunwen-v1-spec.md](docs/xunwen-v1-spec.md).

## Roadmap

The following features are planned (TBD), in priority order: RAG Q&A → system tray → audio/video transcription.

- [ ] Split reshuffle semantics: incremental re-classification vs full reshuffle
- [ ] RAG Q&A (currently retrieval only)
- [ ] Audio/video transcription, tray background mode

## License

[Apache-2.0](LICENSE). See [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) for third-party component licenses.
