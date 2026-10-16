package com.pm.filmfuse.social.controller;

import com.pm.filmfuse.social.dto.UserSummaryDTO;
import com.pm.filmfuse.social.service.BlockService;
import com.pm.filmfuse.user.model.Profile;
import com.pm.filmfuse.user.security.CurrentUser;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** People the caller has blocked. */
@RestController
@RequestMapping("/api/v1/blocks")
public class BlockController {

    private final BlockService blockService;

    public BlockController(BlockService blockService) {
        this.blockService = blockService;
    }

    @GetMapping
    public List<UserSummaryDTO> list(@CurrentUser Profile caller) {
        return blockService.list(caller.getId());
    }

    /** Removes any friendship and pending requests. 204, idempotent; 400 for oneself. */
    @PostMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void block(@CurrentUser Profile caller, @PathVariable UUID userId) {
        blockService.block(caller.getId(), userId);
    }

    /** 204, idempotent. */
    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unblock(@CurrentUser Profile caller, @PathVariable UUID userId) {
        blockService.unblock(caller.getId(), userId);
    }
}
