import java.awt.*; import java.awt.image.BufferedImage; import java.io.File; import javax.imageio.ImageIO;
public class Icon { public static void main(String[] a) throws Exception {
  BufferedImage i = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB); Graphics2D g = i.createGraphics();
  g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
  g.setPaint(new GradientPaint(0, 0, new Color(0x1b2f7a), 0, 512, new Color(0x0c1230))); g.fillRect(0, 0, 512, 512);
  g.setColor(new Color(0x10182c)); g.fillRoundRect(156, 56, 200, 400, 36, 36);
  g.setColor(new Color(0x9fb4ff)); g.setStroke(new BasicStroke(6)); g.drawRoundRect(156, 56, 200, 400, 36, 36);
  g.setColor(new Color(0x2c6be0)); g.fillRect(176, 86, 160, 180);
  g.setColor(new Color(0xffd000)); g.fillOval(236, 150, 44, 44);
  g.setColor(new Color(0xc8d4ff)); for (int r = 0; r < 4; r++) for (int c = 0; c < 3; c++) g.fillRoundRect(184 + c * 52, 290 + r * 38, 40, 26, 10, 10);
  g.setColor(Color.WHITE); g.setFont(new Font("SansSerif", Font.BOLD, 44)); g.drawString("J2ME", 20, 496);
  ImageIO.write(i, "png", new File(a[0])); } }
