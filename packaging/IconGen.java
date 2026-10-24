import java.awt.geom.Arc2D;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.geom.Line2D;
import java.awt.RenderingHints;
import java.awt.Shape;import java.awt.font.TextLayout;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 生成「寻文」应用图标三件套:Xunwen.icns(macOS)、Xunwen.ico(Windows)、Xunwen.png(Linux)。
 *
 * <p>设计:蓝渐变圆角底上的白色放大镜,深色镜片中一枚白色「文」——放大镜里找「文」,即「寻文」。
 * 设计坐标基于 512 画布,按输出尺寸线性缩放。
 *
 * <p>纯 JDK 实现,无第三方依赖。用法: {@code java packaging/IconGen.java <输出目录>},输出目录默认为当前目录。
 */
public class IconGen {

  /** 设计基准画布边长 */
  private static final int BASE = 512;
  private static final String GLYPH = "文";
  private static final String[] FONT_CANDIDATES = {"PingFang SC", "Hiragino Sans GB", "STHeiti"};

  // 设计常量(512 画布坐标)
  private static final float SQUARE = 50; // 圆角底外边距
  private static final float CORNER = 92; // 圆角底半径
  private static final float LENS_CX = 244;
  private static final float LENS_CY = 232;
  private static final float LENS_R = 148;
  private static final float RING = 34;
  private static final float HANDLE = 124; // 镜柄长度(沿 45° 方向)
  private static final float HANDLE_W = 44;
  private static final float GLYPH_SIZE = 152;

  public static void main(String[] args) throws IOException {
    Path out = Path.of(args.length > 0 ? args[0] : ".");
    Files.createDirectories(out);
    writeIcns(out.resolve("Xunwen.icns"));
    writeIco(out.resolve("Xunwen.ico"));
    Files.write(out.resolve("Xunwen.png"), png(draw(512)));
    System.out.println("已生成 Xunwen.icns / Xunwen.ico / Xunwen.png → " + out.toAbsolutePath());
  }

  private static BufferedImage draw(int size) {
    float s = size / (float) BASE;
    BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    // 圆角渐变底
    float pad = SQUARE * s;
    float box = size - 2 * pad;
    float corner = CORNER * s;
    g.setPaint(new GradientPaint(0, 0, new Color(0x4E8BFF), 0, size, new Color(0x2440C8)));
    g.fill(new RoundRectangle2D.Float(pad, pad, box, box, corner, corner));
    // 放大镜(投影 → 深色镜片 → 白色「文」→ 玻璃高光 → 白圈 → 镜柄)
    float cx = LENS_CX * s;
    float cy = LENS_CY * s;
    float radius = LENS_R * s;
    Ellipse2D lens = new Ellipse2D.Float(cx - radius, cy - radius, radius * 2, radius * 2);
    shadow(g, new BasicStroke(RING * s).createStrokedShape(lens), 10 * s);
    g.setColor(new Color(16, 28, 96, 200));
    g.fill(lens);
    Font font = pickFont().deriveFont(Font.BOLD, GLYPH_SIZE * s);
    TextLayout layout = new TextLayout(GLYPH, font, g.getFontRenderContext());
    g.setColor(Color.WHITE);
    layout.draw(g, (float) (cx - layout.getBounds().getCenterX()), (float) (cy - layout.getBounds().getCenterY()));
    g.setStroke(new BasicStroke(13 * s, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    g.setColor(new Color(255, 255, 255, 80));
    float inset = 30 * s;
    g.draw(
        new Arc2D.Float(
            cx - radius + inset,
            cy - radius + inset,
            radius * 2 - inset * 2,
            radius * 2 - inset * 2,
            130,
            60,
            Arc2D.OPEN));
    float ring = RING * s;
    g.setColor(Color.WHITE);
    g.setStroke(new BasicStroke(ring, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
    g.draw(lens);
    float a = 0.7071f;
    float x1 = cx + radius * a;
    float y1 = cy + radius * a;
    float x2 = x1 + HANDLE * s * a;
    float y2 = y1 + HANDLE * s * a;
    g.setStroke(new BasicStroke(HANDLE_W * s, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    g.draw(new Line2D.Float(x1, y1, x2, y2));
    g.dispose();
    return img;
  }

  private static void shadow(Graphics2D g, Shape shape, float dy) {
    g.setColor(new Color(0, 0, 0, 55));
    g.translate(0, dy);
    g.fill(shape);
    g.translate(0, -dy);
  }

  private static Font pickFont() {
    for (String name : FONT_CANDIDATES) {
      Font font = new Font(name, Font.BOLD, 12);
      if (font.canDisplayUpTo(GLYPH) == -1) {
        return font;
      }
    }
    return new Font(Font.SANS_SERIF, Font.BOLD, 12);
  }

  private static void writeIcns(Path path) throws IOException {
    Object[][] entries = {
      {"icp4", 16}, {"icp5", 32}, {"ic07", 128}, {"ic08", 256}, {"ic09", 512}, {"ic10", 1024},
    };
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    for (Object[] entry : entries) {
      byte[] png = png(draw((Integer) entry[1]));
      body.write(entry[0].toString().getBytes(StandardCharsets.US_ASCII));
      body.write(u32be(png.length + 8));
      body.write(png);
    }
    try (var out = Files.newOutputStream(path)) {
      out.write("icns".getBytes(StandardCharsets.US_ASCII));
      out.write(u32be(body.size() + 8));
      out.write(body.toByteArray());
    }
  }

  private static void writeIco(Path path) throws IOException {
    int[] sizes = {16, 24, 32, 48, 64, 128, 256};
    List<byte[]> pngs = new ArrayList<>();
    for (int size : sizes) {
      pngs.add(png(draw(size)));
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(u16le(0));
    out.write(u16le(1));
    out.write(u16le(sizes.length));
    int offset = 6 + 16 * sizes.length;
    for (int i = 0; i < sizes.length; i++) {
      int size = sizes[i];
      byte[] png = pngs.get(i);
      out.write(size == 256 ? 0 : size);
      out.write(size == 256 ? 0 : size);
      out.write(0);
      out.write(0);
      out.write(u16le(1));
      out.write(u16le(32));
      out.write(u32le(png.length));
      out.write(u32le(offset));
      offset += png.length;
    }
    for (byte[] png : pngs) {
      out.write(png);
    }
    Files.write(path, out.toByteArray());
  }

  private static byte[] png(BufferedImage img) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    ImageIO.write(img, "png", bytes);
    return bytes.toByteArray();
  }

  private static byte[] u16le(int v) {
    return new byte[] {(byte) v, (byte) (v >>> 8)};
  }

  private static byte[] u32le(int v) {
    return new byte[] {(byte) v, (byte) (v >>> 8), (byte) (v >>> 16), (byte) (v >>> 24)};
  }

  private static byte[] u32be(int v) {
    return ByteBuffer.allocate(4).putInt(v).array();
  }
}
