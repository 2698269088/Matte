package top.mcocet.model;

import java.math.BigDecimal;

public class Product {
    private int id;
    private String name;
    private String description;
    private BigDecimal price;
    private String imagePath;
    private int stock;
    private int categoryId;

    public Product(int id, String name, String description, BigDecimal price, String imagePath, int stock) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.price = price;
        this.imagePath = imagePath;
        this.stock = stock;
        this.categoryId = 0;
    }

    public Product(int id, String name, String description, BigDecimal price, String imagePath, int stock, int categoryId) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.price = price;
        this.imagePath = imagePath;
        this.stock = stock;
        this.categoryId = categoryId;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public String getImagePath() { return imagePath; }
    public void setImagePath(String imagePath) { this.imagePath = imagePath; }

    public int getStock() { return stock; }
    public void setStock(int stock) { this.stock = stock; }

    public int getCategoryId() { return categoryId; }
    public void setCategoryId(int categoryId) { this.categoryId = categoryId; }
}
