package com.example.plain;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Class-retention annotation carrying a class reference; must not be dropped by the scanner. */
@Retention(RetentionPolicy.CLASS)
public @interface InvisibleBinding {
    Class<?> value();
}
