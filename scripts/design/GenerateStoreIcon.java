import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Reuses GalliPick's checked-in cat foreground for the opaque 512px Play icon.
 * No text, shadows, extra assets, or external dependencies.
 *
 * Run with: java scripts/design/GenerateStoreIcon.java SOURCE_PNG OUTPUT_PNG
 */
class GenerateStoreIcon {
    private static final int SIZE = 512;
    private static final int FOREGROUND_SIZE = 470;
    private static final int OFFSET = (SIZE - FOREGROUND_SIZE) / 2;

    public static void main(String[] arguments) throws IOException {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("Expected the cat source PNG and output icon PNG");
        }
        final Path source = Path.of(arguments[0]);
        final Path destination = Path.of(arguments[1]);
        final BufferedImage cat = ImageIO.read(source.toFile());
        if (cat == null || cat.getWidth() != 640 || cat.getHeight() != 640 || !cat.getColorModel().hasAlpha()) {
            throw new IllegalArgumentException("Expected the existing transparent 640x640 cat illustration");
        }
        final BufferedImage icon = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D canvas = icon.createGraphics();
        try {
            canvas.setColor(new Color(0xCDE6D8));
            canvas.fillRect(0, 0, SIZE, SIZE);
            canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            canvas.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            canvas.drawImage(cat, OFFSET, OFFSET, FOREGROUND_SIZE, FOREGROUND_SIZE, null);
        } finally {
            canvas.dispose();
        }
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                if ((icon.getRGB(x, y) >>> 24) != 255) {
                    throw new IllegalStateException("The final Play icon must be fully opaque");
                }
            }
        }
        if (destination.getParent() != null) {
            Files.createDirectories(destination.getParent());
        }
        if (!ImageIO.write(icon, "png", destination.toFile())) {
            throw new IOException("PNG writer unavailable");
        }
        if (Files.size(destination) > 1_048_576L) {
            throw new IOException("Google Play icon exceeds 1 MiB");
        }
        System.out.println("Generated GalliPick 512x512 sRGB RGBA Play icon: " + destination);
    }
}
