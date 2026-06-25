package dev.koecraft.micbridge;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

public final class KoeCraftMicBridgeIconGenerator {
    private KoeCraftMicBridgeIconGenerator() {
    }

    public static void main(String[] args) throws IOException {
        Path iconset = args.length > 0 ? Path.of(args[0]) : Path.of("koecraft-mic-bridge.iconset");
        Files.createDirectories(iconset);
        write(iconset, "icon_16x16.png", 16);
        write(iconset, "icon_16x16@2x.png", 32);
        write(iconset, "icon_32x32.png", 32);
        write(iconset, "icon_32x32@2x.png", 64);
        write(iconset, "icon_128x128.png", 128);
        write(iconset, "icon_128x128@2x.png", 256);
        write(iconset, "icon_256x256.png", 256);
        write(iconset, "icon_256x256@2x.png", 512);
        write(iconset, "icon_512x512.png", 512);
        write(iconset, "icon_512x512@2x.png", 1024);
    }

    private static void write(Path iconset, String name, int size) throws IOException {
        ImageIO.write(draw(size), "png", iconset.resolve(name).toFile());
    }

    private static BufferedImage draw(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double scale = size / 512.0D;
        g.scale(scale, scale);
        g.setColor(new Color(0x111827));
        g.fillRoundRect(40, 40, 432, 432, 112, 112);
        g.setColor(new Color(0x22C55E));
        g.fillRoundRect(176, 112, 160, 196, 80, 80);
        g.setColor(new Color(0x064E3B));
        g.setStroke(new BasicStroke(24.0F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(214, 172, 298, 172);
        g.drawLine(214, 220, 298, 220);
        g.setColor(new Color(0xF8FAFC));
        g.setStroke(new BasicStroke(34.0F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(256, 342, 256, 400);
        g.drawLine(198, 400, 314, 400);
        g.setStroke(new BasicStroke(24.0F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(148, 196, 106, 154);
        g.drawLine(364, 196, 406, 154);
        g.drawLine(142, 260, 86, 260);
        g.drawLine(370, 260, 426, 260);
        g.dispose();
        return image;
    }
}
