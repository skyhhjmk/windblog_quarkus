package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "email_channels")
public class EmailChannel extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false)
    public String name;
    @Column(nullable = false)
    public String provider;
    @Column(nullable = false)
    public String host;
    @Column(nullable = false)
    public int port;
    @Column(name = "security_mode", nullable = false)
    public String securityMode;
    public String username;
    @Column(name = "password_encrypted")
    public String passwordEncrypted;
    @Column(name = "from_name", nullable = false)
    public String fromName;
    @Column(name = "from_address", nullable = false)
    public String fromAddress;
    @Column(name = "reply_to_address")
    public String replyToAddress;
    @Column(nullable = false)
    public boolean enabled;
}
