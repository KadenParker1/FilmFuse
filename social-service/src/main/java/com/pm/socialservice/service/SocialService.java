package com.pm.socialservice.service;


import com.pm.socialservice.dto.SocialRequestDTO;
import com.pm.socialservice.dto.SocialResponseDTO;
import com.pm.socialservice.mapper.UserNodeMapper;
import com.pm.socialservice.model.UserNode;
import com.pm.socialservice.repository.SocialRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SocialService {
    private SocialRepository socialRepository;

    public SocialService(SocialRepository socialRepository) {this.socialRepository = socialRepository;}

    public List<SocialResponseDTO> getNodes() {
        List<UserNode> userNodes = socialRepository.findAll();
        return userNodes.stream().map(UserNodeMapper::toDTO).toList();
    }

    public SocialResponseDTO createNode(SocialRequestDTO socialRequestDTO){
        if(socialRepository.existsById(socialRequestDTO.getId())){
            throw new UserAlreadyExistsEsception("A user with this Id already exists");
        }
        UserNode newUserNode = SocialRepository.save(UserNodeMapper.toModel(socialRequestDTO));
        return UserNodeMapper.toDTO(newUserNode);
    }

    public null sendFriendRequest(){

    }

    public null handleFriendRequest(){

    }
}

