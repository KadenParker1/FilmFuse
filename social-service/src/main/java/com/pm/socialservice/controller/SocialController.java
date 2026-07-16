package com.pm.socialservice.controller;


import com.pm.socialservice.dto.SocialRequestDTO;
import com.pm.socialservice.dto.SocialResponseDTO;
import com.pm.socialservice.service.SocialService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("v1/socialService") // If we completely change while in production we can just change v1 and keep old structure running in prod while updating

public class SocialController {
    private final SocialService socialService;

    public SocialController(SocialService socialService){this.socialService = socialService;}

    @GetMapping
    ResponseEntity<List<SocialResponseDTO>> getNodes() {
        List<SocialResponseDTO> nodes = socialService.getNodes();
        return ResponseEntity.ok().body(nodes);
    }


    @PostMapping
    public ResponseEntity<SocialResponseDTO> createNode(@Valid @RequestBody SocialRequestDTO socialRequestDTO){
        SocialResponseDTO socialResponseDTO = socialService.
    }
}
