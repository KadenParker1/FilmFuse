package com.pm.filmfuse.user.gateway;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Used when {@code filmfuse.supabase.service-role-key} is blank. Deletes nothing; records the ids. */
public class MockAuthAdminGateway implements AuthAdminGateway {

    private static final Logger log = LoggerFactory.getLogger(MockAuthAdminGateway.class);

    private final List<UUID> deletedUsers = new CopyOnWriteArrayList<>();

    @Override
    public void deleteUser(UUID userId) {
        deletedUsers.add(userId);
        log.info("[mock auth admin] would delete auth user {}", userId);
    }

    public List<UUID> deletedUsers() {
        return List.copyOf(deletedUsers);
    }
}
