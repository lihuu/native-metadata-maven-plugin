package example;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Runtime-visible annotation holding a class reference, used to check annotation extraction. */
@Retention(RetentionPolicy.RUNTIME)
public @interface VisibleBinding {
    Class<?> value();
}
