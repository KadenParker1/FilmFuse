package com.pm.filmfuse.user.gateway;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Used when {@code filmfuse.supabase.service-role-key} is blank (local dev without Supabase keys,
 * and every test). Talks to nothing: URLs are deterministic fakes under the configured Supabase URL
 * (an upload to one fails, which is the honest outcome without a key), and every call is recorded
 * so tests can assert on it. {@link #exists} answers true unless a test marked the object missing
 * ({@link #markMissing}), so local dev can confirm a photo it could not really upload.
 */
public class MockStorageGateway implements StorageGateway {

    private static final Logger log = LoggerFactory.getLogger(MockStorageGateway.class);

    public record Call(String operation, String bucket, String path) {
    }

    private final String storageUrl;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final Set<String> missing = ConcurrentHashMap.newKeySet();

    public MockStorageGateway(SupabaseProperties properties) {
        this.storageUrl = properties.url() + "/storage/v1";
    }

    @Override
    public String createSignedUploadUrl(String bucket, String path, int expiresInSeconds) {
        calls.add(new Call("sign-upload", bucket, path));
        log.info("[mock storage] signed upload URL for {}/{}", bucket, path);
        return storageUrl + "/object/upload/sign/" + bucket + "/" + path + "?token=mock-upload-token";
    }

    @Override
    public String publicUrl(String bucket, String path) {
        return storageUrl + "/object/public/" + bucket + "/" + path;
    }

    @Override
    public boolean exists(String bucket, String path) {
        calls.add(new Call("exists", bucket, path));
        return !missing.contains(bucket + "/" + path);
    }

    /** From now on {@link #exists} answers false for this object (tests use fresh random paths). */
    public void markMissing(String bucket, String path) {
        missing.add(bucket + "/" + path);
    }

    @Override
    public void delete(String bucket, String path) {
        calls.add(new Call("delete", bucket, path));
        log.info("[mock storage] would delete {}/{}", bucket, path);
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public List<String> deletedPaths() {
        return calls.stream().filter(call -> call.operation().equals("delete")).map(Call::path).toList();
    }
}
