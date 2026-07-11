package com.pm.socialservice.repository;
import com.pm.socialservice.model.UserNode;
import org.springframework.data.neo4j.repository.ReactiveNeo4jRepository;

import java.util.UUID;


public interface SocialRepository extends ReactiveNeo4jRepository<UserNode, UUID> {
}
