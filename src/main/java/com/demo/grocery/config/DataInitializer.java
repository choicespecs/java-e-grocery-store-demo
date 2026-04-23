package com.demo.grocery.config;

import com.demo.grocery.domain.Category;
import com.demo.grocery.domain.Product;
import com.demo.grocery.external.inventory.stub.StubInventoryService;
import com.demo.grocery.repository.CategoryRepository;
import com.demo.grocery.repository.ProductRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Component
public class DataInitializer implements ApplicationRunner {

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final StubInventoryService inventoryService;

    public DataInitializer(CategoryRepository categoryRepository,
                           ProductRepository productRepository,
                           StubInventoryService inventoryService) {
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
        this.inventoryService = inventoryService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Category produce   = categoryRepository.save(new Category("Produce"));
        Category dairy     = categoryRepository.save(new Category("Dairy"));
        Category bakery    = categoryRepository.save(new Category("Bakery"));
        Category beverages = categoryRepository.save(new Category("Beverages"));

        // Produce
        seed("Organic Apples (1 lb)",        produce,   "2.99", 50);
        seed("Bananas (bunch)",               produce,   "1.29", 75);
        seed("Baby Spinach (5 oz)",           produce,   "3.49", 30);
        seed("Roma Tomatoes (1 lb)",          produce,   "1.99",  3); // low stock — triggers badge

        // Dairy
        seed("Whole Milk (1 gal)",            dairy,     "3.79", 40);
        seed("Greek Yogurt (32 oz)",          dairy,     "5.99", 25);
        seed("Cheddar Cheese (8 oz)",         dairy,     "4.49", 35);
        seed("Free Range Eggs (dozen)",       dairy,     "4.99",  0); // out of stock — for demo

        // Bakery
        seed("Sourdough Bread",               bakery,    "4.99", 20);
        seed("Blueberry Muffins (4-pack)",    bakery,    "5.49", 15);
        seed("Croissants (2-pack)",           bakery,    "3.99",  2); // low stock
        seed("Whole Wheat Bagels (6-pack)",   bakery,    "4.29", 25);

        // Beverages
        seed("Orange Juice (52 oz)",          beverages, "5.29", 30);
        seed("Sparkling Water (12-pack)",     beverages, "6.99", 40);
        seed("Cold Brew Coffee (32 oz)",      beverages, "7.99", 20);
        seed("Herbal Tea (20-pack)",          beverages, "4.49", 35);
    }

    private void seed(String name, Category category, String price, int stock) {
        Product product = productRepository.save(
            new Product(name, category, new BigDecimal(price), stock)
        );
        // Mirror the DB stock into the external inventory stub
        inventoryService.initializeStock(product.getId(), stock);
    }
}
