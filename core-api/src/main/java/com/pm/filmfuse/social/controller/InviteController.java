package com.pm.filmfuse.social.controller;

import com.pm.filmfuse.social.dto.InviteDTO;
import com.pm.filmfuse.social.dto.InviteRedeemResultDTO;
import com.pm.filmfuse.social.service.InviteService;
import com.pm.filmfuse.user.model.Profile;
import com.pm.filmfuse.user.security.CurrentUser;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Shareable invite links. */
@RestController
@RequestMapping("/api/v1/invites")
public class InviteController {

    private final InviteService inviteService;

    public InviteController(InviteService inviteService) {
        this.inviteService = inviteService;
    }

    /** A new link, valid for 14 days and for any number of people. */
    @PostMapping
    public InviteDTO create(@CurrentUser Profile caller) {
        return inviteService.create(caller.getId());
    }

    /** Makes the caller and the inviter friends. 400 own invite, 404 unknown/blocked, 410 expired. */
    @PostMapping("/{token}/redeem")
    public InviteRedeemResultDTO redeem(@CurrentUser Profile caller, @PathVariable String token) {
        return inviteService.redeem(caller.getId(), token);
    }
}
