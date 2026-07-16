package com.pm.socialservice.model;

import org.springframework.data.neo4j.core.schema.Relationship;
import org.springframework.data.neo4j.core.schema.RelationshipId;
import org.springframework.data.neo4j.core.schema.RelationshipProperties;
import org.springframework.data.neo4j.core.schema.TargetNode;

@RelationshipProperties
public class FriendRelationship {

    @RelationshipId
    private Long id;

    private int closenessScore;

    @TargetNode
    private UserNode friend;

    public FriendRelationship() {

    }

    public FriendRelationship(UserNode friend, int closenessScore)
    {
        this.friend = friend;
        this.closenessScore = closenessScore;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public int getClosenessScore() {
        return closenessScore;
    }

    public void setClosenessScore(int closenessScore) {
        this.closenessScore = closenessScore;
    }

    public UserNode getFriend() {
        return friend;
    }

    public void setFriend(UserNode friend) {
        this.friend = friend;
    }
}
