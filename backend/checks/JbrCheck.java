import com.jetbrains.JBR;

public class JbrCheck {
    public static void main(final String[] arguments) {
        System.out.println("JBR.isAvailable = " + JBR.isAvailable());
        System.out.println("JBR.isGraphicsUtilsSupported = " + JBR.isGraphicsUtilsSupported());
    }
}
