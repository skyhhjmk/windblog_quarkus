package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;

@Entity
@Table(name = "email_channel_group_members")
@IdClass(EmailChannelGroupMember.EmailChannelGroupMemberId.class)
public class EmailChannelGroupMember extends PanacheEntityBase {
    @Id
    @Column(name = "group_id")
    public Long groupId;
    @Id
    @Column(name = "channel_id")
    public Long channelId;
    @Column(nullable = false)
    public int priority;

    public static class EmailChannelGroupMemberId implements Serializable {
        public Long groupId;
        public Long channelId;
    }
}
