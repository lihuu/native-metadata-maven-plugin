package example;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Input for the "low JVM reads high class file" case.
 *
 * <p>Compiled at several {@code --release} levels by {@code scripts/build-high-version-fixtures.sh}.
 * Two properties matter:</p>
 *
 * <ul>
 *   <li>The class file major version must be newer than the JVM running the scanner, so that the
 *       scanner demonstrably reads bytes it could not load.</li>
 *   <li>The static initializer writes a marker file. If the scanner ever loaded or initialized the
 *       fixture, the marker would appear. This only proves "not initialized"; combined with
 *       {@code UnsupportedClassVersionError} on load and the absence of any class-loading call in
 *       the scanner, it supports "reads bytes, does not load classes".</li>
 * </ul>
 */
public class HighVersionProbe {

    public static final String CONSTANT = "constant";

    static {
        String marker = System.getProperty("highversion.marker");
        if (marker != null) {
            try {
                java.nio.file.Files.writeString(java.nio.file.Path.of(marker), "initialized");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private String label;
    private int[] weights;

    public HighVersionProbe() {
        this.label = "default";
    }

    public HighVersionProbe(String label) {
        this.label = label;
    }

    public HighVersionProbe(String label, int[] weights) {
        this.label = label;
        this.weights = weights;
    }

    public String label() {
        return label;
    }

    public String describe(String... parts) {
        return label + ":" + String.join(",", parts);
    }

    public int[] weights() {
        return weights;
    }

    public static class Nested {
        public String value() {
            return "nested";
        }
    }
}
