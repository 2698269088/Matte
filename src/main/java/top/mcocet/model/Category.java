package top.mcocet.model;

public class Category {
    private int id;
    private String name;
    private int sortOrder;
    private String createdAt;

    public Category() {}

    public Category(int id, String name, int sortOrder, String createdAt) {
        this.id = id;
        this.name = name;
        this.sortOrder = sortOrder;
        this.createdAt = createdAt;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
