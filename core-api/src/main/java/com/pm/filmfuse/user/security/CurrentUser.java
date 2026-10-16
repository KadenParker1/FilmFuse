package com.pm.filmfuse.user.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Resolves the authenticated caller's {@link com.pm.filmfuse.user.model.Profile} for a controller
 * parameter, auto-provisioning the row on first sight of a Supabase user id (JWT {@code sub}).
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
