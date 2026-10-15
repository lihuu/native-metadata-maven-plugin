package com.example.plain;

/**
 * Registered in the configuration but never touched by the application.
 *
 * <p>Pruning must keep the registration: "no ordinary reference in the scanned bytecode" is not
 * evidence that a type is unused, so there is deliberately no rule that removes it.</p>
 */
public class Unused {

    public String unreachable() {
        return "never called";
    }
}
