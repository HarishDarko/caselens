package com.harishdarko.caselens;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CaseLensApplication {
    public static void main(String[] args) {
        SpringApplication.run(CaseLensApplication.class, args);
    }
}
