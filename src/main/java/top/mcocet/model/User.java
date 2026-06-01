package top.mcocet.model;

public class User {
    private int id;
    private String username;
    private String passwordHash;
    private String phone;
    private String email;
    private String createdAt;
    private boolean admin;

    public User() {}

    public User(int id, String username, String passwordHash, String phone, String email, String createdAt) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.phone = phone;
        this.email = email;
        this.createdAt = createdAt;
        this.admin = false;
    }

    public User(int id, String username, String passwordHash, String phone, String email, String createdAt, boolean admin) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.phone = phone;
        this.email = email;
        this.createdAt = createdAt;
        this.admin = admin;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public boolean isAdmin() { return admin; }
    public void setAdmin(boolean admin) { this.admin = admin; }
}
