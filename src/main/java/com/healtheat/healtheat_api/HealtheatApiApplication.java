package com.healtheat.healtheat_api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HealtheatApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(HealtheatApiApplication.class, args);
	}

}
