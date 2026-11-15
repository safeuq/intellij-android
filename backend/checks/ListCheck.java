import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ListCheck {
    public static void main(final String[] arguments) {
        for (final String directory : arguments) {
            final List<String> names = new ArrayList<>();
            try (final var directoryStream = Files.newDirectoryStream(Path.of(directory))) {
                for (final Path entry : directoryStream) {
                    names.add(entry.getFileName().toString());
                }
                System.out.println(directory + " -> " + (names.size() > 6 ? names.subList(0, 6) + " ... (" + names.size() + ")" : names));
            } catch (final IOException listingFailure) {
                System.out.println(directory + " -> " + listingFailure);
            }
        }
    }
}
