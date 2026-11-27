package com.peanut;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.web.config.EnableSpringDataWebSupport;

@SpringBootApplication
@EnableSpringDataWebSupport
public class PeanutApplication {
    public static void main(String[] args) {
        SpringApplication.run(PeanutApplication.class, args);
    }
}
