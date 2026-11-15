import com.jetbrains.rd.framework.FrameworkMarshallers;
import com.jetbrains.rd.framework.IdKind;
import com.jetbrains.rd.framework.Identities;
import com.jetbrains.rd.framework.Protocol;
import com.jetbrains.rd.framework.RdContext;
import com.jetbrains.rd.framework.Serializers;
import com.jetbrains.rd.framework.SocketWire;
import com.jetbrains.rd.framework.UnsafeBuffer;
import com.jetbrains.rd.framework.base.IRdBindableKt;
import com.jetbrains.rd.framework.base.RdBindableBase;
import com.jetbrains.rd.framework.base.RdBindableBaseKt;
import com.jetbrains.rd.framework.impl.RdSignal;
import com.jetbrains.rd.util.lifetime.LifetimeDefinition;
import com.jetbrains.rd.util.threading.SynchronousScheduler;
import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Checks that JetBrains' rd library (as shipped in JetBrains Client 263.7307.200) runs on Android ART:
 * 1. UnsafeBuffer (sun.misc.Unsafe based) round-trips primitives and strings.
 * 2. Two rd Protocols connected by SocketWire over loopback deliver an RdSignal value.
 */
public final class RdOnAndroidCheck {
    public static void main(final String[] arguments) throws Exception {
        checkUnsafeBuffer();
        checkSocketWireLoopback();
        System.out.println("ALL CHECKS PASSED");
        System.exit(0);
    }

    private static void checkUnsafeBuffer() {
        try (final var buffer = new UnsafeBuffer(16)) {
            buffer.writeInt(0x12345678);
            buffer.writeLong(-42L);
            buffer.writeDouble(3.25);
            buffer.writeString("héllo rd");
            buffer.writeIntArray(new int[] {1, 2, 3});
            buffer.setPosition(0);
            check(buffer.readInt() == 0x12345678, "readInt");
            check(buffer.readLong() == -42L, "readLong");
            check(buffer.readDouble() == 3.25, "readDouble");
            check("héllo rd".equals(buffer.readString()), "readString");
            check(buffer.readIntArray().length == 3, "readIntArray");
        }
        System.out.println("UnsafeBuffer round trip: OK");
    }

    private static void checkSocketWireLoopback() throws Exception {
        final var lifetimeDefinition = new LifetimeDefinition();
        final var lifetime = lifetimeDefinition.getLifetime();
        final var scheduler = SynchronousScheduler.INSTANCE;

        final var serverWire = new SocketWire.Server(lifetime, scheduler, (Integer) null, "server", false);
        // Explicit IPv4: on Android InetAddress.getLoopbackAddress() is ::1, while SocketWire.Server listens on 127.0.0.1.
        final var clientWire = new SocketWire.Client(lifetime, scheduler, serverWire.getPort(), "client", InetAddress.getByName("127.0.0.1"));

        final var serverProtocol = new Protocol("server", new Serializers(), new Identities(IdKind.Server), scheduler,
            serverWire, lifetime, null, null, new RdContext[0]);
        final var clientProtocol = new Protocol("client", new Serializers(), new Identities(IdKind.Client), scheduler,
            clientWire, lifetime, null, null, new RdContext[0]);

        final var serverSignal = new RdSignal<String>(FrameworkMarshallers.INSTANCE.getString());
        final var clientSignal = new RdSignal<String>(FrameworkMarshallers.INSTANCE.getString());
        // Same static id on both sides so the two signals match on the wire; rd requires binding on the protocol's scheduler.
        // `static` is a Java keyword, so the Kotlin extension is called reflectively.
        final var staticId = RdBindableBaseKt.class.getMethod("static", RdBindableBase.class, int.class);
        staticId.invoke(null, serverSignal, 1);
        staticId.invoke(null, clientSignal, 1);
        scheduler.queue(() -> {
            IRdBindableKt.bindTopLevel(serverSignal, lifetime, serverProtocol, "check");
            IRdBindableKt.bindTopLevel(clientSignal, lifetime, clientProtocol, "check");
            return kotlin.Unit.INSTANCE;
        });

        final var received = new AtomicReference<String>();
        final var latch = new CountDownLatch(1);
        scheduler.queue(() -> {
            serverSignal.advise(lifetime, value -> {
                received.set(value);
                latch.countDown();
                return kotlin.Unit.INSTANCE;
            });
            return kotlin.Unit.INSTANCE;
        });

        scheduler.queue(() -> {
            clientSignal.fire("hello from the client");
            return kotlin.Unit.INSTANCE;
        });
        check(latch.await(15, TimeUnit.SECONDS), "signal not received within 15 s");
        check("hello from the client".equals(received.get()), "unexpected value: " + received.get());
        System.out.println("SocketWire loopback on port " + serverWire.getPort() + ": OK (" + received.get() + ")");
        lifetimeDefinition.terminate(false);
    }

    private static void check(final boolean condition, final String what) {
        if (!condition) {
            throw new IllegalStateException("Check failed: " + what);
        }
    }
}
