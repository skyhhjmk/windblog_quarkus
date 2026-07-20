package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "email_channel_groups")
public class EmailChannelGroup extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false)
    public String name;
    @Column(name = "dispatch_mode", nullable = false)
    public String dispatchMode;
    @Column(name = "next_channel_index", nullable = false)
    public int nextChannelIndex;
    @Column(nullable = false)
    public boolean enabled;
}
