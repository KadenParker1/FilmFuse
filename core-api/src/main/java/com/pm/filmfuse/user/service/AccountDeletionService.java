package com.pm.filmfuse.user.service;

import com.pm.filmfuse.user.gateway.AuthAdminGateway;
import com.pm.filmfuse.user.model.Profile;
import com.pm.filmfuse.user.repository.DeletedAccountRepository;
import com.pm.filmfuse.user.repository.ProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code DELETE /me}: the photo (best-effort), the profile row, then the Supabase Auth user.
 *
 * <p>Order matters. Our data goes first, because that is the part we can guarantee. The auth user
 * goes last: if Supabase fails at that point the row is already gone, so the request still
 * succeeds and the failure is logged at ERROR with the id for a manual clean-up (Supabase dashboard
 * or admin API). Failing the request instead would leave the user unable to retry - their next call
 * would simply provision a fresh, empty profile.
 *
 * <p>{@link AccountDeletionEvent} is published in that transaction, before the row goes, for clean-up
 * a cascade cannot express (handing over group ownership).
 *
 * <p>The tombstone ({@code deleted_accounts}) is written in the same transaction as the row delete,
 * before the Auth call, so the caller's still-valid access token is refused from then on whatever
 * Supabase does.
 */
@Service
public class AccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);

    private final ProfileRepository profileRepository;
    private final ProfilePhotoService profilePhotoService;
    private final AuthAdminGateway authAdminGateway;
    private final DeletedAccountRepository deletedAccountRepository;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher events;

    public AccountDeletionService(ProfileRepository profileRepository, ProfilePhotoService profilePhotoService,
                                  AuthAdminGateway authAdminGateway, DeletedAccountRepository deletedAccountRepository,
                                  TransactionTemplate transactionTemplate, ApplicationEventPublisher events) {
        this.profileRepository = profileRepository;
        this.profilePhotoService = profilePhotoService;
        this.authAdminGateway = authAdminGateway;
        this.deletedAccountRepository = deletedAccountRepository;
        this.transactionTemplate = transactionTemplate;
        this.events = events;
    }

    public void delete(Profile profile) {
        String photoPath = profilePhotoService.storagePathOf(profile.getProfileImageUrl());
        if (photoPath != null) {
            profilePhotoService.deleteQuietly(photoPath);
        }

        transactionTemplate.executeWithoutResult(status -> {
            deletedAccountRepository.markDeleted(profile.getId());
            // Other modules tidy up first (e.g. group ownership hand-over); cascades remove the rest.
            events.publishEvent(new AccountDeletionEvent(profile.getId()));
            profileRepository.deleteById(profile.getId());
            profileRepository.flush();
        });
        log.info("Deleted profile {}", profile.getId());

        try {
            authAdminGateway.deleteUser(profile.getId());
        } catch (RuntimeException ex) {
            log.error("Profile {} was deleted but its Supabase Auth user was not; delete it by hand", profile.getId(), ex);
        }
    }
}
