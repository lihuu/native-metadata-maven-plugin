package com.example.plain;

/** Record with an array component and two annotation class references (one runtime-visible). */
@VisibleBinding(Greeting.class)
@InvisibleBinding(Point.class)
public record Point(String name, int[] values) {

    public static Point of(String name) {
        return new Point(name, new int[0]);
    }

    public String describe(String... parts) {
        return name + "/" + String.join(",", parts);
    }
}
