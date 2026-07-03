package com.emie.designpm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class DesignPmApplication {
    public static void main(String[] args) {
        SpringApplication.run(DesignPmApplication.class, args);
    }
}
