package com.pm.userservice.controller;


import com.pm.userservice.dto.UserRequestDTO;
import com.pm.userservice.dto.UserResponseDTO;
import com.pm.userservice.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/users")
public class UserController {
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    ResponseEntity<List<UserResponseDTO>> getUsers() {
        List<UserResponseDTO> users = userService.getUsers();
        return ResponseEntity.ok().body(users);
    }

    @PostMapping
    public ResponseEntity<UserResponseDTO> createUser(@Valid @RequestBody UserRequestDTO userRequestDTO){
        UserResponseDTO userResponseDTO = userService.createUser(userRequestDTO);
        return ResponseEntity.ok().body(userResponseDTO);
    }
}
