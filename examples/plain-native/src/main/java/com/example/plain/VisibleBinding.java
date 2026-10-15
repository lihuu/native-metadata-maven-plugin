package com.example.plain;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Runtime-visible annotation carrying a class reference; the scanner must report it. */
@Retention(RetentionPolicy.RUNTIME)
public @interface VisibleBinding {
    Class<?> value();
}
