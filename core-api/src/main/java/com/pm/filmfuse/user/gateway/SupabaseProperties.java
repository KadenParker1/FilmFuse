package com.pm.filmfuse.user.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param url            the Supabase project URL ({@code SUPABASE_URL}); {@code http://127.0.0.1:54321}
 *                       for {@code supabase start}
 * @param serviceRoleKey the service-role key ({@code SUPABASE_SERVICE_ROLE_KEY}); bypasses RLS and
 *                       can delete users, so it never leaves core-api. Blank selects the mock
 *                       storage / auth-admin gateways.
 */
@ConfigurationProperties(prefix = "filmfuse.supabase")
public record SupabaseProperties(String url, String serviceRoleKey) {

    public static final String DEFAULT_URL = "http://127.0.0.1:54321";

    public SupabaseProperties {
        url = url == null || url.isBlank() ? DEFAULT_URL : url;
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
    }

    public boolean hasServiceRoleKey() {
        return serviceRoleKey != null && !serviceRoleKey.isBlank();
    }
}
