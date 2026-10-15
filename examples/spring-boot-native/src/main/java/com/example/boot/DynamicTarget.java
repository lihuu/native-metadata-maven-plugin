package com.example.boot;

/** Reflection target reached only through {@link DynamicOperation}; registered by RuntimeHints. */
public class DynamicTarget {

    private String label = "dynamic";

    public DynamicTarget() {
    }

    public String describe(String suffix) {
        return label + ":" + suffix;
    }

    public String secret() {
        return "not part of any hint";
    }
}
