package com.pm.filmfuse.user.gateway;

/**
 * The port to object storage (Supabase Storage). Paths are bucket-relative, e.g.
 * {@code <userId>/<uuid>.jpg}. Failures are {@code UpstreamUnavailableException}.
 */
public interface StorageGateway {

    /**
     * An absolute URL the client can {@code PUT} the object to without credentials of its own.
     *
     * @param expiresInSeconds how long the caller will tell the client the URL is valid for
     */
    String createSignedUploadUrl(String bucket, String path, int expiresInSeconds);

    /** The object's public URL (the bucket must be public). Pure string building, no request. */
    String publicUrl(String bucket, String path);

    /**
     * Whether the object exists. False for a missing object (or bucket); an outage is an
     * {@code UpstreamUnavailableException}, never a silent false.
     */
    boolean exists(String bucket, String path);

    /** Deleting an object that does not exist is not an error. */
    void delete(String bucket, String path);
}
