package example;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Class-retention annotation holding a class reference; must be kept alongside the visible one. */
@Retention(RetentionPolicy.CLASS)
public @interface InvisibleBinding {
    Class<?> value();
}
