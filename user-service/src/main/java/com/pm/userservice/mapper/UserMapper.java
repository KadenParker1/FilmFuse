package com.pm.userservice.mapper;

import com.pm.userservice.dto.UserRequestDTO;
import com.pm.userservice.dto.UserResponseDTO;
import com.pm.userservice.model.User;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class UserMapper {
    public static UserResponseDTO toDTO(User user){
        UserResponseDTO userDTO = new UserResponseDTO();
        userDTO.setId(user.getId().toString());
        userDTO.setName(user.getName());
        userDTO.setUserName(user.getUserName());
        userDTO.setProfileImageUrl(user.getProfileImageUrl());
        userDTO.setEmail(user.getEmail());
        return userDTO;
    }

    public static User toModel(UserRequestDTO userRequestDTO){
        User user = new User();
        user.setName(userRequestDTO.getName());
        user.setUserName(userRequestDTO.getUserName());
        user.setProfileImageUrl(userRequestDTO.getProfileImageUrl());
        user.setEmail(userRequestDTO.getEmail());
        user.setRegisteredDate(LocalDateTime.parse(userRequestDTO.getRegisteredDate()));
        return user;

    }
}
