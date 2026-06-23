-- liquibase formatted sql
-- changeset antigravity:083-create-comment-quotes

CREATE TABLE comment_quotes
(
    id          BIGSERIAL PRIMARY KEY,
    comment_id  BIGINT                   NOT NULL,
    post_id     BIGINT                   NOT NULL,
    quote_type  VARCHAR(20)              NOT NULL,
    quote_text  TEXT                     NOT NULL,
    anchor_data JSONB                    NOT NULL,
    status      SMALLINT                 NOT NULL DEFAULT 0,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE comment_quotes
    ADD CONSTRAINT fk_comment_quotes_comment FOREIGN KEY (comment_id) REFERENCES comments (id) ON DELETE CASCADE;
ALTER TABLE comment_quotes
    ADD CONSTRAINT fk_comment_quotes_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE;
CREATE INDEX idx_comment_quotes_post_id ON comment_quotes (post_id);
