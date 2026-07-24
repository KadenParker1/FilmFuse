package com.pm.socialservice.service;


import com.pm.socialservice.dto.SocialRequestDTO;
import com.pm.socialservice.dto.SocialResponseDTO;
import com.pm.socialservice.mapper.UserNodeMapper;
import com.pm.socialservice.model.FriendRelationship;
import com.pm.socialservice.model.UserNode;
import com.pm.socialservice.repository.SocialRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

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

    public void sendFriendRequest(UUID userId, UUID targetUserId){
        UserNode sender = socialRepository.findById(userId).orElseThrow(() -> new RuntimeException("Sender not found"));

        UserNode target = socialRepository.findById(targetUserId).orElseThrow(() -> new RuntimeException("Target not found"));

        if (sender.getFriendRequests().contains(target) || sender.getBlocked().contains(target) || sender.getFriends().contains(target)){
            throw new RuntimeException("Friend request already exists, already friends, or user is blocked.");
        }

        sender.getFriendRequests().add(target);
        socialRepository.save(sender);

    }

    public void acceptFriendRequest(UUID userId, UUID targetUserId){
        UserNode sender = socialRepository.findById(userId).orElseThrow(() -> new RuntimeException("Sender not found"));
        UserNode target = socialRepository.findById(targetUserId).orElseThrow(() -> new RuntimeException("Target not found"));

        if (target.getFriendRequests().contains(target)) {
            Set<UserNode> friendRequests = target.getFriendRequests();
            friendRequests.remove(sender);
            Set<FriendRelationship> senderFriends = sender.getFriends();
            Set<FriendRelationship> targetFriends = target.getFriends();
            senderFriends.add(new FriendRelationship(target, 0));
            targetFriends.add(new FriendRelationship(target, 0));
            socialRepository.save(sender);
            socialRepository.save(target);
        }
        else {
            throw new RuntimeException("Friend request not found");
        }
    }

    public void rejectFriendRequest(UUID userId, UUID targetUserId){
        UserNode sender = socialRepository.findById(userId).orElseThrow(() -> new RuntimeException("Sender not found"));
        UserNode target = socialRepository.findById(targetUserId).orElseThrow(() -> new RuntimeException("Target not found"));

        if (target.getFriendRequests().contains(target)) {
            Set<UserNode> friendRequests = target.getFriendRequests();
            friendRequests.remove(sender);
            socialRepository.save(sender);
        }
        else {
            throw new RuntimeException();
        }
    }
    }

    public List<SocialResponseDTO> getUserFriends(UUID userId){
        List<UserNode> friends = socialRepository.findFriendsByUserId(userId);
        return friends.stream().map(UserNodeMapper::toDTO).toList();
    }
}

