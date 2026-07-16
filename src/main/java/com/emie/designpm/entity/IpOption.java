package com.emie.designpm.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "ip_options")
public class IpOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    @Column(nullable = false)
    private Boolean active = true;

    /** 二级 IP 选项，JSON 数组；为空时新建项目页不展示二级选择区域。 */
    @Column(columnDefinition = "TEXT")
    private String subOptionsJson;

    /** single / multiple */
    @Column(length = 16)
    private String subOptionSelectionMode = "multiple";

    public IpOption() {}

    public IpOption(String name, Integer sortOrder) {
        this.name = name;
        this.sortOrder = sortOrder;
        this.active = true;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }

    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }

    public String getSubOptionsJson() { return subOptionsJson; }
    public void setSubOptionsJson(String subOptionsJson) { this.subOptionsJson = subOptionsJson; }

    public String getSubOptionSelectionMode() { return subOptionSelectionMode; }
    public void setSubOptionSelectionMode(String subOptionSelectionMode) { this.subOptionSelectionMode = subOptionSelectionMode; }
}
