package com.pm.userservice.service;

import com.pm.userservice.dto.UserResponseDTO;
import com.pm.userservice.model.User;
import com.pm.userservice.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserService {
    private UserRepository userRepository;
    public UserService(UserRepository userRepository){
        this.userRepository = userRepository;
    }

    public List<UserResponseDTO> getUsers(){
        List<User> users = userRepository.findAll();
        return users;
    }
}
