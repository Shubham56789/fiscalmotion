package com.fiscalmotion.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FiscalmotionApplication {

	public static void main(String[] args) {
		SpringApplication.run(FiscalmotionApplication.class, args);
	}

}
