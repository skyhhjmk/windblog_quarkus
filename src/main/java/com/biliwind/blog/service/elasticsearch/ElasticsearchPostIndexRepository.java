package com.biliwind.blog.service.elasticsearch;

import com.biliwind.blog.model.PostStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

import java.util.List;

@ApplicationScoped
public class ElasticsearchPostIndexRepository {

    @PersistenceContext
    EntityManager entityManager;

    @Transactional
    public List<Long> listPublicPublishedPostIds() {
        return entityManager.createQuery("""
                select post.id
                from Post post
                where post.status = :status
                  and post.deletedAt is null
                  and post.visibility = 0
                  and post.publishedRevision is not null
                order by post.id
                """, Long.class)
                .setParameter("status", PostStatus.PUBLISHED)
                .getResultList();
    }
}
