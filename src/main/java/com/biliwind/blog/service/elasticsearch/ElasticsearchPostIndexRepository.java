package com.biliwind.blog.service.elasticsearch;

import com.biliwind.blog.model.PostStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@ApplicationScoped
public class ElasticsearchPostIndexRepository {

    @PersistenceContext
    EntityManager entityManager;

    private static final String PUBLIC_PUBLISHED_PREDICATE = """
            from Post post
            where post.status = :status
              and post.deletedAt is null
              and post.visibility = 0
              and post.publishedRevision is not null
            """;

    @Transactional
    public List<Long> listPublicPublishedPostIds() {
        return entityManager.createQuery("select post.id " + PUBLIC_PUBLISHED_PREDICATE + " order by post.id",
                        Long.class)
                .setParameter("status", PostStatus.PUBLISHED)
                .getResultList();
    }

    @Transactional
    public long countPublicPublishedPosts() {
        return entityManager.createQuery("select count(post.id) " + PUBLIC_PUBLISHED_PREDICATE, Long.class)
                .setParameter("status", PostStatus.PUBLISHED)
                .getSingleResult();
    }

    @Transactional
    public OffsetDateTime maxPublicPublishedUpdatedAt() {
        return entityManager.createQuery("select max(post.updatedAt) "
                        + PUBLIC_PUBLISHED_PREDICATE, OffsetDateTime.class)
                .setParameter("status", PostStatus.PUBLISHED)
                .getSingleResult();
    }

    @Transactional
    public List<Long> listPublicPublishedIdsUpdatedAfter(OffsetDateTime threshold) {
        return entityManager.createQuery("select post.id " + PUBLIC_PUBLISHED_PREDICATE
                        + " and post.updatedAt > :threshold"
                        + " order by post.id", Long.class)
                .setParameter("status", PostStatus.PUBLISHED)
                .setParameter("threshold", threshold)
                .getResultList();
    }
}
