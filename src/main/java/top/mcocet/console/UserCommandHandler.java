package top.mcocet.console;

import top.mcocet.db.DBManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.List;

public class UserCommandHandler {

    public static void listUsers() {
        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            String sql = "SELECT id, username, phone, email, created_at FROM users ORDER BY id";
            try (PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {

                System.out.println("┌────────┬────────────────┬──────────────┬─────────────────────┬─────────────────────┐");
                System.out.println("│  ID    │ 用户名         │ 手机号       │ 邮箱                │ 注册时间            │");
                System.out.println("├────────┼────────────────┼──────────────┼─────────────────────┼─────────────────────┤");

                int count = 0;
                while (rs.next()) {
                    count++;
                    System.out.printf("│ %-6d │ %-14s │ %-12s │ %-19s │ %-19s │%n",
                        rs.getInt("id"),
                        truncate(rs.getString("username"), 14),
                        truncate(rs.getString("phone") != null ? rs.getString("phone") : "-", 12),
                        truncate(rs.getString("email") != null ? rs.getString("email") : "-", 19),
                        rs.getString("created_at")
                    );
                }

                System.out.println("└────────┴────────────────┴──────────────┴─────────────────────┴─────────────────────┘");
                System.out.println("共 " + count + " 位注册用户");
            }
        } catch (SQLException e) {
            System.out.println("查询用户失败: " + e.getMessage());
        } catch (InterruptedException e) {
            System.out.println("系统繁忙，请稍后重试");
        } finally {
            DBManager.releaseConnection(conn);
        }
    }

    public static void registerUser(String username, String password, String phone, String email) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            System.out.println("错误: 用户名和密码不能为空");
            return;
        }

        if (username.length() < 3 || username.length() > 20) {
            System.out.println("错误: 用户名长度需在3-20位之间");
            return;
        }

        if (password.length() < 6) {
            System.out.println("错误: 密码长度至少6位");
            return;
        }

        String passwordHash = hashPassword(password);
        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            String sql = "INSERT INTO users (username, password_hash, phone, email) VALUES (?, ?, ?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                stmt.setString(1, username);
                stmt.setString(2, passwordHash);
                stmt.setString(3, phone != null && !phone.isBlank() ? phone : null);
                stmt.setString(4, email != null && !email.isBlank() ? email : null);
                stmt.executeUpdate();

                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    if (rs.next()) {
                        System.out.println("✓ 用户注册成功！");
                        System.out.println("  用户ID: " + rs.getInt(1));
                        System.out.println("  用户名: " + username);
                    }
                }
            }
        } catch (SQLException e) {
            if (e.getMessage().contains("UNIQUE constraint failed")) {
                System.out.println("错误: 用户名 '" + username + "' 已存在");
            } else {
                System.out.println("注册失败: " + e.getMessage());
            }
        } catch (InterruptedException e) {
            System.out.println("系统繁忙，请稍后重试");
        } finally {
            DBManager.releaseConnection(conn);
        }
    }

    public static void deleteUser(String usernameOrId) {
        if (usernameOrId == null || usernameOrId.isBlank()) {
            System.out.println("错误: 请提供用户名或用户ID");
            return;
        }

        Connection conn = null;
        try {
            conn = DBManager.getConnection();

            String querySql = "SELECT id, username FROM users WHERE id = ? OR username = ?";
            int userId = -1;
            String username = null;

            try (PreparedStatement stmt = conn.prepareStatement(querySql)) {
                stmt.setString(1, usernameOrId);
                stmt.setString(2, usernameOrId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        userId = rs.getInt("id");
                        username = rs.getString("username");
                    } else {
                        System.out.println("错误: 未找到用户 '" + usernameOrId + "'");
                        return;
                    }
                }
            }

            String deleteSql = "DELETE FROM users WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(deleteSql)) {
                stmt.setInt(1, userId);
                int rows = stmt.executeUpdate();
                if (rows > 0) {
                    System.out.println("✓ 用户注销成功！");
                    System.out.println("  用户ID: " + userId);
                    System.out.println("  用户名: " + username);
                }
            }
        } catch (SQLException e) {
            System.out.println("注销用户失败: " + e.getMessage());
        } catch (InterruptedException e) {
            System.out.println("系统繁忙，请稍后重试");
        } finally {
            DBManager.releaseConnection(conn);
        }
    }

    private static String hashPassword(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static String truncate(String str, int maxLen) {
        if (str == null) return "";
        if (str.length() <= maxLen) return str;
        return str.substring(0, maxLen - 1) + "…";
    }
}
