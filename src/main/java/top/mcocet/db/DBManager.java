package top.mcocet.db;

import java.sql.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class DBManager {
    private static final String DB_URL = "jdbc:sqlite:matte_shop.db";
    private static final int POOL_SIZE = 5;
    private static final BlockingQueue<Connection> pool = new ArrayBlockingQueue<>(POOL_SIZE);
    private static volatile boolean initialized = false;

    static {
        try {
            Class.forName("org.sqlite.JDBC");
            initPool();
            initTables();
        } catch (Exception e) {
            throw new RuntimeException("数据库初始化失败", e);
        }
    }

    private static void initPool() throws SQLException {
        for (int i = 0; i < POOL_SIZE; i++) {
            pool.offer(DriverManager.getConnection(DB_URL));
        }
    }

    private static void initTables() throws SQLException {
        String createUsers = """
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT UNIQUE NOT NULL,
                password_hash TEXT NOT NULL,
                phone TEXT,
                email TEXT,
                is_admin INTEGER DEFAULT 0,
                failed_attempts INTEGER DEFAULT 0,
                lockout_until TIMESTAMP,
                is_disabled INTEGER DEFAULT 0,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """;

        String createAddresses = """
            CREATE TABLE IF NOT EXISTS addresses (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL,
                receiver_name TEXT NOT NULL,
                phone TEXT NOT NULL,
                province TEXT NOT NULL,
                city TEXT NOT NULL,
                district TEXT,
                detail TEXT NOT NULL,
                is_default INTEGER DEFAULT 0,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
            )
            """;

        String createProducts = """
            CREATE TABLE IF NOT EXISTS products (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                description TEXT,
                price REAL NOT NULL,
                image_path TEXT,
                stock INTEGER DEFAULT 0,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """;

        String createOrders = """
            CREATE TABLE IF NOT EXISTS orders (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL,
                product_id INTEGER NOT NULL,
                product_name TEXT NOT NULL,
                price REAL NOT NULL,
                quantity INTEGER DEFAULT 1,
                address_id INTEGER,
                merchant_id INTEGER,
                status TEXT DEFAULT 'pending',
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                shipped_at TIMESTAMP,
                delivered_at TIMESTAMP,
                FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
                FOREIGN KEY (address_id) REFERENCES addresses(id) ON DELETE SET NULL
            )
            """;

        String createMerchants = """
            CREATE TABLE IF NOT EXISTS merchants (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                phone TEXT,
                address TEXT,
                qr_path TEXT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """;

        String createSettings = """
            CREATE TABLE IF NOT EXISTS settings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                key TEXT UNIQUE NOT NULL,
                value TEXT,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """;

        String createCategories = """
            CREATE TABLE IF NOT EXISTS categories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                sort_order INTEGER DEFAULT 0,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """;

        String createCarts = """
            CREATE TABLE IF NOT EXISTS carts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL,
                product_id INTEGER NOT NULL,
                product_name TEXT NOT NULL,
                price REAL NOT NULL,
                custom_price REAL,
                quantity INTEGER DEFAULT 1,
                image_path TEXT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
                FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE
            )
            """;

        Connection conn = null;
        try {
            conn = getConnection();
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(createUsers);
                stmt.execute(createAddresses);
                stmt.execute(createProducts);
                stmt.execute(createOrders);
                stmt.execute(createMerchants);
                stmt.execute(createSettings);
                stmt.execute(createCategories);
                stmt.execute(createCarts);
            }
            // 为products表添加category_id列（如果不存在）
            try (Statement stmt = conn.createStatement()) {
                try {
                    stmt.execute("ALTER TABLE products ADD COLUMN category_id INTEGER DEFAULT 0");
                } catch (SQLException e) {
                    // 列已存在，忽略错误
                }
            }
            
            // 为orders表添加merchant_id列（如果不存在）
            try (Statement stmt = conn.createStatement()) {
                try {
                    stmt.execute("ALTER TABLE orders ADD COLUMN merchant_id INTEGER");
                    System.out.println("Added merchant_id column to orders table");
                } catch (SQLException e) {
                    System.out.println("merchant_id column already exists");
                }
                try {
                    stmt.execute("ALTER TABLE orders ADD COLUMN delivered_at TIMESTAMP");
                    System.out.println("Added delivered_at column to orders table");
                } catch (SQLException e) {
                    System.out.println("delivered_at column already exists");
                }
            }

            // 为users表添加登录失败相关字段（如果不存在）
            try (Statement stmt = conn.createStatement()) {
                try {
                    stmt.execute("ALTER TABLE users ADD COLUMN failed_attempts INTEGER DEFAULT 0");
                    System.out.println("Added failed_attempts column to users table");
                } catch (SQLException e) {
                    System.out.println("failed_attempts column already exists");
                }
                try {
                    stmt.execute("ALTER TABLE users ADD COLUMN lockout_until TEXT");
                    System.out.println("Added lockout_until column to users table");
                } catch (SQLException e) {
                    System.out.println("lockout_until column already exists");
                }
                try {
                    stmt.execute("ALTER TABLE users ADD COLUMN is_disabled INTEGER DEFAULT 0");
                    System.out.println("Added is_disabled column to users table");
                } catch (SQLException e) {
                    System.out.println("is_disabled column already exists");
                }
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("获取数据库连接被中断", e);
        } finally {
            releaseConnection(conn);
        }
    }

    public static Connection getConnection() throws InterruptedException {
        return pool.take();
    }

    public static void releaseConnection(Connection conn) {
        if (conn != null) {
            pool.offer(conn);
        }
    }

    public static void closeAll() {
        for (Connection conn : pool) {
            try {
                conn.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }
}