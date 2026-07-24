package com.pm.socialservice.controller;


import com.pm.socialservice.dto.SocialRequestDTO;
import com.pm.socialservice.dto.SocialResponseDTO;
import com.pm.socialservice.service.SocialService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("v1/social/users") // If we completely change while in production we can just change v1 and keep old structure running in prod while updating

public class SocialController {
    private final SocialService socialService;

    public SocialController(SocialService socialService){this.socialService = socialService;}

    @GetMapping
    public ResponseEntity<List<SocialResponseDTO>> getNodes() {
        List<SocialResponseDTO> nodes = socialService.getNodes();
        return ResponseEntity.ok().body(nodes);
    }

    @GetMapping("/{userId}/friends") // very important for groupservice, gets all friends of a user
    public ResponseEntity<List<SocialResponseDTO>> getUserFriends(@PathVariable UUID userId){
        List<SocialResponseDTO> friends = socialService.getUserFriends(userId);
        return ResponseEntity.ok(friends);
    }

    @PostMapping
    public ResponseEntity<SocialResponseDTO> createNode(@Valid @RequestBody SocialRequestDTO socialRequestDTO){
        SocialResponseDTO socialResponseDTO = socialService.createNode(socialRequestDTO);
        return ResponseEntity.ok(socialResponseDTO);
    }

    @PostMapping("/v1/social/{userId}/friend-requests/{targetUserId}")
    public null sendFriendRequest(@PathVariable UUID userId, @PathVariable UUID targetUserId){
        socialService.sendFriendRequest(userId, targetUserId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/v1/social/users/{userId}/friend-requests/{requesterId}/accept")
    public null acceptFriendRequest(@PathVariable UUID userId, @PathVariable UUID targetUserId){
        socialService.acceptFriendRequest(userId, targetUserId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/v1/social/users/{userId}/friend-requests/{requesterId}/reject")
    public null rejectFriendRequest(@PathVariable UUID userId, @PathVariable UUID targetUserId){
        socialService.rejectFriendRequest(userId, targetUserId);
        return ResponseEntity.ok().build();
    }

}
