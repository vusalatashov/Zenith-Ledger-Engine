package com.mswallet.common.idempotency;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as idempotent.
 * The aspect will intercept the call, check the header, and ensure
 * the operation is not executed multiple times.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
    String headerName() default "X-Idempotency-Key";
    int expireHours() default 24;
}