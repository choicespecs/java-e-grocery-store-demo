package com.demo.grocery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for the Fresh Groceries demo application.
 *
 * <p>Starts an embedded Tomcat server on port 8080 (default). The application requires
 * no external infrastructure — H2 runs in-memory and all external services (inventory,
 * payment, promotions, coupons) are in-process stubs.
 *
 * @see com.demo.grocery.config.DataInitializer for the startup data seed
 * @see com.demo.grocery.service.CheckoutService for the saga orchestrator
 */
@SpringBootApplication
public class GroceryApplication {
    public static void main(String[] args) {
        SpringApplication.run(GroceryApplication.class, args);
    }
}
