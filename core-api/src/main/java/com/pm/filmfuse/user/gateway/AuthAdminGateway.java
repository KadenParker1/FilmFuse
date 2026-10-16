package com.pm.filmfuse.user.gateway;

import java.util.UUID;

/** The port to Supabase Auth's admin API. */
public interface AuthAdminGateway {

    /** Deletes the auth user (their sign-in identity). A user that is already gone is not an error. */
    void deleteUser(UUID userId);
}
