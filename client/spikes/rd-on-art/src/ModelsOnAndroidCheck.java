import com.intellij.platform.split.protocol.DefaultSplitMarshallersProvider;
import com.jetbrains.rd.framework.IdKind;
import com.jetbrains.rd.framework.Identities;
import com.jetbrains.rd.framework.SerializationCtx;
import com.jetbrains.rd.framework.Serializers;
import com.jetbrains.rd.framework.UnsafeBuffer;
import com.jetbrains.rd.ide.model.LuxModel;
import com.jetbrains.rd.ide.model.LxPoint;
import java.util.Map;

/**
 * Checks that the generated split-mode model classes (JetBrains Client 263.7307.200) load and
 * serialize on Android ART:
 * 1. A model data class (LxPoint) round-trips through its generated marshaller.
 * 2. A polymorphic read resolves the type id through DefaultSplitMarshallersProvider, which looks the
 *    class up in the splitMarshallers.txt resource (so jar resources must ship with the dex).
 * 3. A root model class initializes (prints LuxModel.serializationHash).
 */
public final class ModelsOnAndroidCheck {
    public static void main(final String[] arguments) {
        final var point = new LxPoint(12.5, -3.0);

        final var writingSerializers = new Serializers(DefaultSplitMarshallersProvider.INSTANCE);
        writingSerializers.register(LxPoint.Companion);
        final var writingContext = new SerializationCtx(writingSerializers, new Identities(IdKind.Client), Map.of());

        try (final var buffer = new UnsafeBuffer(64)) {
            LxPoint.Companion.write(writingContext, buffer, point);
            buffer.setPosition(0);
            final var direct = LxPoint.Companion.read(writingContext, buffer);
            check(direct.getX() == 12.5 && direct.getY() == -3.0, "direct LxPoint round trip: " + direct);
            System.out.println("Generated marshaller round trip: OK (" + direct.getX() + ", " + direct.getY() + ")");

            buffer.setPosition(0);
            writingSerializers.writePolymorphic(writingContext, buffer, point);
            final int written = buffer.getPosition();
            buffer.setPosition(0);
            // Fresh serializers with nothing registered: the type must be found via the resource file.
            final var readingSerializers = new Serializers(DefaultSplitMarshallersProvider.INSTANCE);
            final var readingContext = new SerializationCtx(readingSerializers, new Identities(IdKind.Server), Map.of());
            final Object polymorphic = readingSerializers.readPolymorphic(readingContext, buffer, null);
            check(polymorphic instanceof LxPoint, "polymorphic read returned " + polymorphic);
            System.out.println("Polymorphic read via splitMarshallers.txt: OK (" + written + " bytes on the wire)");
        }

        System.out.println("LuxModel.serializationHash = " + LuxModel.serializationHash);
        System.out.println("ALL MODEL CHECKS PASSED");
    }

    private static void check(final boolean condition, final String what) {
        if (!condition) {
            throw new IllegalStateException("Check failed: " + what);
        }
    }
}
