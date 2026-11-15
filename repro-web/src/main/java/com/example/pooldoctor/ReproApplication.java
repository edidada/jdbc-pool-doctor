package com.example.pooldoctor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 根包 com.example.pooldoctor，扫描 pool-common(.common) 与 repro-web(.web)。
 */
@SpringBootApplication
public class ReproApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReproApplication.class, args);
    }
}
