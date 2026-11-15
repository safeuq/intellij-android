import java.security.MessageDigest;
import java.security.Security;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import javax.net.ssl.SSLContext;

/** Prints whether this runtime provides what the split-mode connection needs: SHA3-256, TLSv1.3, raw DEFLATE with sync flush. */
public final class CryptoProbe {
    public static void main(final String[] arguments) throws Exception {
        try {
            final var digest = MessageDigest.getInstance("SHA3-256");
            System.out.println("SHA3-256: present (" + digest.getProvider().getName() + ")");
        } catch (final java.security.NoSuchAlgorithmException missingAlgorithm) {
            System.out.println("SHA3-256: MISSING");
        }
        final var tlsContext = SSLContext.getInstance("TLSv1.3");
        tlsContext.init(null, null, null);
        System.out.println("TLSv1.3: present (" + tlsContext.getProvider().getName() + "), default protocols "
            + String.join(",", tlsContext.getDefaultSSLParameters().getProtocols()));

        final byte[] input = "hello split mode".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final var deflater = new Deflater(1, true);
        deflater.setInput(input);
        final byte[] compressed = new byte[256];
        final int compressedLength = deflater.deflate(compressed, 0, compressed.length, Deflater.SYNC_FLUSH);
        final var inflater = new Inflater(true);
        inflater.setInput(compressed, 0, compressedLength);
        final byte[] restored = new byte[256];
        final int restoredLength = inflater.inflate(restored);
        System.out.println("raw DEFLATE + SYNC_FLUSH: " + (new String(restored, 0, restoredLength, java.nio.charset.StandardCharsets.UTF_8).equals("hello split mode") ? "OK" : "MISMATCH")
            + " (" + compressedLength + " bytes, ends " + String.format("%02X %02X %02X %02X", compressed[compressedLength - 4], compressed[compressedLength - 3], compressed[compressedLength - 2], compressed[compressedLength - 1]) + ")");
        System.out.println("security providers: " + java.util.Arrays.stream(Security.getProviders()).map(provider -> provider.getName()).reduce((left, right) -> left + "," + right).orElse(""));
    }
}
