package com.pm.filmfuse.user.repository;

import com.pm.filmfuse.user.model.Profile;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ProfileRepository extends JpaRepository<Profile, UUID> {

    boolean existsByUsername(String username);

    boolean existsByUsernameAndIdNot(String username, UUID id);

    /**
     * Touches only {@code email}: a full-entity save here could overwrite a concurrent PATCH /me
     * with the stale values this request loaded.
     */
    @Transactional
    @Modifying
    @Query("update Profile p set p.email = :email where p.id = :id")
    int updateEmail(@Param("id") UUID id, @Param("email") String email);
}
