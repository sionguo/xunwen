# 第三方组件许可

寻文基于以下开源组件构建，感谢原作者。本项目以 Apache-2.0 发布；下表为运行时依赖的
许可核查结论（2026-10-27 逐项对照上游 LICENSE / 仓库确认），全部允许随本项目再分发。

| 组件 | 版本 | 许可 | 来源 |
| --- | --- | --- | --- |
| JavaFX (OpenJFX) | 27 | GPLv2 + Classpath Exception | openjfx.io |
| ControlsFX | 11.2.1 | BSD-3-Clause | github.com/controlsfx/controlsfx |
| Ikonli | 12.3.1 | Apache-2.0 | github.com/kordamp/ikonli |
| AtlantaFX | 3.0.0 | MIT | github.com/mkpaz/atlantafx |
| SLF4J | 2.0.20 | MIT | slf4j.org |
| Logback | 1.3.14 | EPL-1.0 / LGPL-2.1（二选一，取 EPL） | logback.qos.ch |
| Lucene | 10.5.2 | Apache-2.0 | lucene.apache.org |
| Jackson 3 | 3.2.4 | Apache-2.0 | github.com/FasterXML |
| Apache Tika | 4.1.0 | Apache-2.0 | tika.apache.org |
| JSpecify | 1.0.1 | Apache-2.0 | jspecify.org |
| JiaJiaOCR | 2.2.0 | Apache-2.0 | github.com/jiangnanboy/JiaJiaOCR |
| ONNX Runtime | 1.19.0 | MIT | onnxruntime.ai |
| OpenCV（openpnp 打包） | 4.9.0-0 | BSD-3-Clause | github.com/openpnp/opencv |

说明：

- **JiaJiaOCR**：不在 Maven Central，由本项目从上游 Release 下载安装（见 CI）；jar 内
  捆绑的 PaddleOCR ONNX 模型随上游以 Apache-2.0 整体发布，再分发时保留其许可声明即可。
- **测试类依赖**（JUnit 5、TestFX）为 test scope，不进入安装包分发，不在上表之列。
- JavaFX 的 GPLv2 + Classpath Exception 允许作为普通依赖链接，不传染本项目许可。
