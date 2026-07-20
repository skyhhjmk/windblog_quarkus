package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "email_scenario_routes")
public class EmailScenarioRoute extends PanacheEntityBase {
    @Id
    public String scenario;
    @Column(name = "channel_group_id")
    public Long channelGroupId;
    @Column(name = "channel_id")
    public Long channelId;
    @Column(name = "template_key", nullable = false)
    public String templateKey;
}
