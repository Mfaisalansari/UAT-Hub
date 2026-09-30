package com.uathub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class UatHubApplication {
    public static void main(String[] args) {
        SpringApplication.run(UatHubApplication.class, args);
    }
}
