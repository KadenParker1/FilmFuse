package com.pm.filmfuse.user.dto;

/**
 * @param username  the normalised (trimmed, lowercased) username that was checked
 * @param valid     whether it meets the format rules at all
 * @param available valid and not taken by anyone else (your own current username counts as available)
 */
public record UsernameAvailabilityDTO(String username, boolean valid, boolean available) {
}
