package com.pm.userservice.mapper;

import com.pm.userservice.dto.UserResponseDTO;
import com.pm.userservice.model.User;

public class UserMapper {
    public static UserResponseDTO toDTO(User user){
        UserResponseDTO userDTO = new UserResponseDTO();
        userDTO.setId(user.getId().toString());
        userDTO.setName(user.getName());
        userDTO.setUsername(user.getUsername());
        userDTO.setProfileImageUrl(user.getProfileImageUrl());
        userDTO.setEmail(user.getEmail());
        return userDTO;
    }
}
