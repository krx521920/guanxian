package com.guanxian.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.guanxian.platform")
public class GuanxianApplication {
    public static void main(String[] args) {
        SpringApplication.run(GuanxianApplication.class, args);
    }
}
