package com.pm.filmfuse.social.controller;

import com.pm.filmfuse.common.dto.PageDTO;
import com.pm.filmfuse.social.dto.PublicProfileDTO;
import com.pm.filmfuse.social.dto.UserSummaryDTO;
import com.pm.filmfuse.social.service.UserSearchService;
import com.pm.filmfuse.user.model.Profile;
import com.pm.filmfuse.user.security.CurrentUser;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Finding and viewing other people. */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserSearchService userSearchService;

    public UserController(UserSearchService userSearchService) {
        this.userSearchService = userSearchService;
    }

    /** Prefix search on username or display name, 20 per page. 400 when {@code q} is not 2-50 characters. */
    @GetMapping("/search")
    public PageDTO<UserSummaryDTO> search(
            @CurrentUser Profile caller,
            @RequestParam @NotBlank(message = "Enter something to search for")
            @Size(min = 2, max = 50, message = "Search text must be 2-50 characters") String q,
            @RequestParam(required = false) String cursor) {
        return userSearchService.search(caller.getId(), q, cursor);
    }

    /** 404 for an unknown user or one who has blocked the caller. */
    @GetMapping("/{id}")
    public PublicProfileDTO profile(@CurrentUser Profile caller, @PathVariable UUID id) {
        return userSearchService.profile(caller.getId(), id);
    }
}
