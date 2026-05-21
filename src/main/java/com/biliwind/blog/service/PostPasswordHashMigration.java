package com.biliwind.blog.service;

import com.biliwind.blog.common.security.PasswordHasher;
import com.biliwind.blog.model.Post;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;

@ApplicationScoped
public class PostPasswordHashMigration {

    private static final Logger LOG = Logger.getLogger(PostPasswordHashMigration.class);

    @Inject
    PasswordHasher passwordHasher;

    @Transactional
    void onStart(@Observes StartupEvent ignored) {
        List<Post> posts = Post.list("visibility = ?1 and password is not null and password <> ''", (short) 2);
        int migratedCount = 0;
        for (Post post : posts) {
            if (!passwordHasher.isHashedFormat(post.password)) {
                post.password = passwordHasher.hash(post.password);
                migratedCount = migratedCount + 1;
            }
        }
        if (migratedCount > 0) {
            LOG.warnf("已将 %d 篇密码文章的明文访问密码迁移为 PBKDF2 哈希", migratedCount);
        }
    }
}
