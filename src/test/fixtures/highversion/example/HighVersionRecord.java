package example;

/** Record with components, a nested annotation class reference and an explicit member list. */
@VisibleBinding(HighVersionProbe.class)
@InvisibleBinding(HighVersionProbe.class)
public record HighVersionRecord(String name, int[] values) {

    public static HighVersionRecord of(String name) {
        return new HighVersionRecord(name, new int[0]);
    }

    public String describe(String... parts) {
        return name + "/" + String.join(",", parts);
    }
}
