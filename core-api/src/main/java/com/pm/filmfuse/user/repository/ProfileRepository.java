package com.pm.filmfuse.user.repository;

import com.pm.filmfuse.user.model.Profile;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfileRepository extends JpaRepository<Profile, UUID> {

    boolean existsByUsername(String username);

    boolean existsByUsernameAndIdNot(String username, UUID id);
}
