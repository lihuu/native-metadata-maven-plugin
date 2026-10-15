package com.example.plain;

/**
 * Reflection target of the sample.
 *
 * <p>It carries the shapes the scanner has to keep straight: a zero-arg and an overloaded
 * constructor, a same-name no-arg method and a varargs (array) method, a private field read and
 * written at run time, and one method ({@link #secret()}) that is never called and therefore should
 * not be needed - but which pruning must not remove on its own.</p>
 */
public class Greeting {

    private String message;
    private int[] weights = new int[0];

    public Greeting() {
        this.message = "default";
    }

    public Greeting(String message) {
        this.message = message;
    }

    public Greeting(String message, int[] weights) {
        this.message = message;
        this.weights = weights;
    }

    public String label() {
        return message;
    }

    public String describe(String... parts) {
        return message + ":" + String.join(",", parts);
    }

    public int[] weights() {
        return weights;
    }

    /** Registered by the broad configuration, never invoked. Pruning must leave it alone. */
    public String secret() {
        return "secret";
    }
}
