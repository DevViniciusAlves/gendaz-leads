package com.gendaz.leads;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class GendazLeadsApplication {

    public static void main(String[] args) {
        SpringApplication.run(GendazLeadsApplication.class, args);
    }
}
