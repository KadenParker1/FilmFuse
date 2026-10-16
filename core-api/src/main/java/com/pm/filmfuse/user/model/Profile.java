package com.pm.filmfuse.user.model;

import com.pm.filmfuse.common.model.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

/**
 * A FilmFuse user. {@code id} is the Supabase {@code auth.users.id} (JWT {@code sub}) - assigned,
 * never generated here. Supabase owns identity (email, password, OAuth); we only mirror the id.
 *
 * <p>Implements {@link Persistable} because the id is always non-null before save: without it
 * Spring Data would {@code merge()} (select + insert) a brand-new row instead of {@code persist()}.
 */
@Entity
@Table(name = "profiles", schema = "app")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(of = "id", callSuper = false)
public class Profile extends AuditableEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    /** Lowercase {@code [a-z0-9_]{3,30}}; null until the user picks one during onboarding. */
    @Column(unique = true)
    private String username;

    @Column(name = "display_name")
    private String displayName;

    /** A Supabase Storage path in the {@code profile-photos} bucket (or an absolute URL). */
    @Column(name = "profile_image_url")
    private String profileImageUrl;

    private String bio;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status = AccountStatus.ACTIVE;

    @Column(name = "onboarding_step", nullable = false)
    private int onboardingStep = 0;

    @Column(name = "onboarding_completed_at")
    private Instant onboardingCompletedAt;

    public Profile(UUID id) {
        this.id = id;
    }

    @Override
    @Transient
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
