package com.gendaz.leads;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class GendazLeadsApplication {

    public static void main(String[] args) {
        SpringApplication.run(GendazLeadsApplication.class, args);
    }
}
