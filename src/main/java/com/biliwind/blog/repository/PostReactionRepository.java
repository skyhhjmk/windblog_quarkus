package com.biliwind.blog.repository;

import com.biliwind.blog.model.PostReaction;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

@ApplicationScoped
public class PostReactionRepository implements PanacheRepositoryBase<PostReaction, Long> {

    public Optional<PostReaction> findBySlug(String postSlug) {
        if (postSlug == null || postSlug.isBlank()) {
            return Optional.empty();
        }
        return find("postSlug", postSlug).firstResultOptional();
    }
}
