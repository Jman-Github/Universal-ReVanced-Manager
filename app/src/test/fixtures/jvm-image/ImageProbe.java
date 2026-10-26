import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

/** Run on the Android JRE, rather than the developer's full desktop JDK. */
public final class ImageProbe {
    public static void main(String[] args) throws Exception {
        if (!java.awt.GraphicsEnvironment.isHeadless()) throw new AssertionError("Worker must be headless");
        Class.forName("java.awt.image.Raster");
        Class.forName("brut.androlib.res.decoder.Res9patchStreamDecoder").getConstructor().newInstance();
        BufferedImage original = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        original.setRGB(1, 1, 0xff123456);
        java.awt.Graphics2D graphics = original.createGraphics();
        graphics.dispose();
        // Triggers the fontmanager -> freetype native dependency even in headless mode.
        Class.forName("sun.font.FontManagerNativeLibrary");
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        if (!ImageIO.write(original, "png", png)) throw new AssertionError("PNG writer missing");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(png.toByteArray()));
        if (decoded == null || decoded.getRGB(1, 1) != 0xff123456) throw new AssertionError("PNG roundtrip failed");
        System.out.println("PASS: Android JVM loads Apktool nine-patch decoder and roundtrips PNG pixels");
    }
}
