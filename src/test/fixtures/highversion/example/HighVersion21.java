package example;

import java.util.List;

/**
 * Uses an API introduced in Java 21, so a successful {@code --release 21} build is real evidence
 * that the 21/25 artifacts are not accidentally Java 17 class files with a patched version.
 */
public class HighVersion21 {

    public String first(List<String> values) {
        return values.getFirst();
    }
}
