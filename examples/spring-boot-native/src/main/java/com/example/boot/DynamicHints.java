package com.example.boot;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * The one dynamic operation the application cannot express statically: the controller looks up a
 * method by a name that arrives in the HTTP query string. It therefore needs an explicit hint, and
 * that hint is what {@code spring-boot:process-aot} turns into generated reflection metadata.
 *
 * <p>This is the registration the negative control removes: without it the dynamic request must
 * fail in the native image.</p>
 */
public class DynamicHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.reflection().registerType(DynamicTarget.class,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.INVOKE_DECLARED_METHODS);
    }
}
