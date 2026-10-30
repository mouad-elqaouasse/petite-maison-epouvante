// Expérience 4 — photo de test (JPEG 640x480), générée sur le poste : jshell 00-generer-photo.jsh
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
var img = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
var g = img.createGraphics();
g.setPaint(new GradientPaint(0, 0, new Color(40, 0, 60), 640, 480, new Color(200, 80, 0)));
g.fillRect(0, 0, 640, 480);
g.setColor(Color.WHITE);
g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 48));
g.drawString("epouvante-test", 140, 250);
g.dispose();
ImageIO.write(img, "jpg", new java.io.File("photo-test.jpg"));
/exit
