package cn.guoxy.xunwen.extract;

import com.jiajia.core.JiaJiaOCR;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.nio.file.Path;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OCR 兜底提取器：包装 Tika，仅当 Tika 判定无文本层且文件为 PDF 时，用 JiaJiaOCR （PaddleOCR ONNX 纯 Java 封装）识别前几页。文本 PDF
 * 的毫秒级快速路径不受影响； 扫描件 OCR 一次后结果进索引，不会重复识别。
 */
public final class OcrFallbackExtractor implements ContentExtractor {
  private static final Logger LOG = LoggerFactory.getLogger(OcrFallbackExtractor.class);

  private static final int MIN_TEXT_LAYER = 20; // 与 TikaExtractor 一致
  private static final int MAX_PAGES = 3; // 分类只需标题与前言，不必整本
  private static final float RENDER_DPI = 150f;
  private static volatile JiaJiaOCR ocr; // 模型首次调用时加载；官方文档：单实例可并发共享

  static {
    nu.pattern.OpenCV.loadShared(); // openpnp opencv：装载平台 native
  }

  private final TikaExtractor tika = new TikaExtractor();

  @Override
  public Extracted extract(Path file) throws IOException {
    Extracted ex = tika.extract(file);
    if (ex.hasTextLayer() || !"pdf".equalsIgnoreCase(ext(file))) {
      return ex;
    }
    String text = ocrFirstPages(file);
    if (text.isBlank()) {
      return ex; // OCR 也无果：维持 Tika 的无文本层结论，走文件名兜底
    }
    LOG.info("OCR 兜底：{}：提取 {} 字符", file.getFileName(), text.length());
    return new Extracted(text, text.length() >= MIN_TEXT_LAYER);
  }

  private static String ocrFirstPages(Path file) {
    try (PDDocument doc = Loader.loadPDF(file.toFile())) {
      PDFRenderer renderer = new PDFRenderer(doc);
      StringBuilder sb = new StringBuilder();
      int pages = Math.min(MAX_PAGES, doc.getNumberOfPages());
      for (int i = 0; i < pages; i++) {
        BufferedImage image = renderer.renderImageWithDPI(i, RENDER_DPI);
        try {
          for (var item : ocr().recognizeGeneralText(toMat(image))) {
            sb.append(item.getLeft().getText()).append('\n');
          }
        } catch (Exception e) {
          // JiaJiaOCR 对无文本框的页面(空白页/纯图)会抛 NPE:跳过该页,不影响其余页
          LOG.warn("OCR 第 {} 页失败,跳过:{}", i + 1, file.getFileName());
        }
      }
      return sb.toString();
    } catch (Exception e) {
      LOG.warn("OCR 失败：{}", file, e);
      return "";
    }
  }

  private static JiaJiaOCR ocr() {
    if (ocr == null) {
      synchronized (OcrFallbackExtractor.class) {
        if (ocr == null) {
          ocr = JiaJiaOCR.builder();
        }
      }
    }
    return ocr;
  }

  /** BufferedImage → BGR Mat（OpenCV 识别入参）。 */
  private static Mat toMat(BufferedImage image) {
    BufferedImage bgr =
        new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
    Graphics2D g = bgr.createGraphics();
    g.drawImage(image, 0, 0, null);
    g.dispose();
    Mat mat = new Mat(bgr.getHeight(), bgr.getWidth(), CvType.CV_8UC3);
    mat.put(0, 0, ((DataBufferByte) bgr.getRaster().getDataBuffer()).getData());
    return mat;
  }

  private static String ext(Path file) {
    String name = file.getFileName().toString();
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "" : name.substring(dot + 1);
  }
}
