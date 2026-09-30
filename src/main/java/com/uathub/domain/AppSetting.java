package com.uathub.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class AppSetting {

    @Id
    @Column(name = "setting_key")
    private String name;

    @Column(name = "setting_value")
    private String value;

    public AppSetting() {}

    public AppSetting(String name, String value) {
        this.name = name;
        this.value = value;
    }

    public String getName() { return name; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}
