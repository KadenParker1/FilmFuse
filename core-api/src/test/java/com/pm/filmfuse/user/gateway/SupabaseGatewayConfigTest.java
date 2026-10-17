package com.pm.filmfuse.user.gateway;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SupabaseGatewayConfigTest {

    private final SupabaseGatewayConfig config = new SupabaseGatewayConfig();

    @Test
    void blankKeySelectsMocks() {
        SupabaseProperties properties = new SupabaseProperties(null, " ");
        assertThat(config.storageGateway(properties)).isInstanceOf(MockStorageGateway.class);
        assertThat(config.authAdminGateway(properties)).isInstanceOf(MockAuthAdminGateway.class);
    }

    @Test
    void keySelectsSupabase() {
        SupabaseProperties properties = new SupabaseProperties("https://proj.supabase.test", "service-key");
        assertThat(config.storageGateway(properties)).isInstanceOf(SupabaseStorageGateway.class);
        assertThat(config.authAdminGateway(properties)).isInstanceOf(SupabaseAuthAdminGateway.class);
    }

    @Test
    void urlDefaultsToLocalSupabase() {
        assertThat(new SupabaseProperties(null, null).url()).isEqualTo("http://127.0.0.1:55321");
        assertThat(new SupabaseProperties("https://x.test//", null).url()).isEqualTo("https://x.test");
    }

    @Test
    void mockStorageIsDeterministicAndRecordsCalls() {
        MockStorageGateway storage = new MockStorageGateway(new SupabaseProperties("http://sb.test", null));

        assertThat(storage.createSignedUploadUrl("b", "u/p.jpg", 60))
                .isEqualTo("http://sb.test/storage/v1/object/upload/sign/b/u/p.jpg?token=mock-upload-token");
        assertThat(storage.publicUrl("b", "u/p.jpg")).isEqualTo("http://sb.test/storage/v1/object/public/b/u/p.jpg");
        storage.delete("b", "u/p.jpg");

        assertThat(storage.calls()).containsExactly(
                new MockStorageGateway.Call("sign-upload", "b", "u/p.jpg"),
                new MockStorageGateway.Call("delete", "b", "u/p.jpg"));
        assertThat(storage.deletedPaths()).containsExactly("u/p.jpg");
    }

    @Test
    void mockAuthAdminRecordsDeletions() {
        MockAuthAdminGateway admin = new MockAuthAdminGateway();
        UUID id = UUID.randomUUID();
        admin.deleteUser(id);
        assertThat(admin.deletedUsers()).containsExactly(id);
    }
}
