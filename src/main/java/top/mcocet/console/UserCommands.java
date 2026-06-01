package top.mcocet.console;

import top.mcocet.db.DBManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.List;

public class UserCommands {

    public static void registerAll(CommandRegistry registry) {
        registry.register("list-users", "查看所有已注册用户", UserCommands::listUsers);
        registry.register("register", "手动注册新用户", UserCommands::registerUser);
        registry.register("delete-user", "注销用户", UserCommands::deleteUser);
        registry.register("user-info", "查看用户详细信息", UserCommands::userInfo);
        registry.register("set-admin", "设置用户为管理员", UserCommands::setAdmin);
        registry.register("help", "显示命令帮助", args -> registry.printHelp());
    }

    private static void listUsers(List<String> args) {
        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            String sql = "SELECT id, username, phone, email, is_admin, created_at FROM users ORDER BY id";
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
                System.out.println("===== 已注册用户列表 =====");
                int count = 0;
                while (rs.next()) {
                    count++;
                    System.out.printf("ID:%d | 用户:%s | 手机:%s | 邮箱:%s | 管理员:%s | 注册:%s%n",
                        rs.getInt("id"), rs.getString("username"),
                        rs.getString("phone") != null ? rs.getString("phone") : "-",
                        rs.getString("email") != null ? rs.getString("email") : "-",
                        rs.getInt("is_admin") == 1 ? "是" : "否",
                        rs.getString("created_at"));
                }
                System.out.println("共 " + count + " 位注册用户");
            }
        } catch (SQLException e) {
            System.out.println("查询失败: " + e.getMessage());
        } catch (InterruptedException e) {
            System.out.println("系统繁忙");
        } finally {
            DBManager.releaseConnection(conn);
        }
    }

    private static void registerUser(List<String> args) {
        if (args.size() < 2) {
            System.out.println("用法: register <用户名> <密码> [手机号] [邮箱]");
            return;
        }
        String username = args.get(0);
        String password = args.get(1);
        String phone = args.size() > 2 ? args.get(2) : null;
        String email = args.size() > 3 ? args.get(3) : null;
        if (username.length() < 3 || username.length() > 20) {
            System.out.println("错误: 用户名长度需在3-20位之间"); return;
        }
        if (password.length() < 6) {
            System.out.println("错误: 密码长度至少6位"); return;
        }
        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            String sql = "INSERT INTO users (username, password_hash, phone, email) VALUES (?, ?, ?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                stmt.setString(1, username);
                stmt.setString(2, hashPassword(password));
                stmt.setString(3, phone);
                stmt.setString(4, email);
                stmt.executeUpdate();
                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    if (rs.next()) {
                        System.out.println("用户注册成功！ID=" + rs.getInt(1) + ", 用户名=" + username);
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
            System.out.println("系统繁忙");
        } finally {
            DBManager.releaseConnection(conn);
        }
    }

    private static void deleteUser(List<String> args) {
        if (args.isEmpty()) {
            System.out.println("用法: delete-user <用户ID或用户名>");
            return;
        }
        String target = args.get(0);
        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            String findSql = "SELECT id, username FROM users WHERE id = ? OR username = ?";
            int userId = -1;
            String username = null;
            try (PreparedStatement stmt = conn.prepareStatement(findSql)) {
                try { stmt.setInt(1, Integer.parseInt(target)); }
                catch (NumberFormatException e) { stmt.setInt(1, -1); }
                stmt.setString(2, target);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) { userId = rs.getInt("id"); username = rs.getString("username"); }
                }
            }
            if (userId == -1) { System.out.println("错误: 未找到用户 '" + target + "'"); return; }
            String deleteSql = "DELETE FROM users WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(deleteSql)) {
                stmt.setInt(1, userId);
                stmt.executeUpdate();
                System.out.println("用户注销成功！ID=" + userId + ", 用户名=" + username);
            }
        } catch (SQLException e) {
            System.out.println("注销失败: " + e.getMessage());
        } catch (InterruptedException e) {
            System.out.println("系统繁忙");
        } finally {
            DBManager.releaseConnection(conn);
        }
    }

    private static void userInfo(List<String> args) {
        if (args.isEmpty()) { System.out.println("用法: user-info <用户ID>"); return; }
        int userId;
        try { userId = Integer.parseInt(args.get(0)); }
        catch (NumberFormatException e) { System.out.println("错误: 用户ID必须是数字"); return; }
        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            String userSql = "SELECT * FROM users WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(userSql)) {
                stmt.setInt(1, userId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) { System.out.println("错误: 未找到用户ID=" + userId); return; }
                    System.out.println("===== 用户详细信息 =====");
                    System.out.println("用户ID: " + rs.getInt("id"));
                    System.out.println("用户名: " + rs.getString("username"));
                    System.out.println("手机号: " + (rs.getString("phone") != null ? rs.getString("phone") : "未设置"));
                    System.out.println("邮箱: " + (rs.getString("email") != null ? rs.getString("email") : "未设置"));
                    System.out.println("管理员: " + (rs.getInt("is_admin") == 1 ? "是" : "否"));
                    System.out.println("注册时间: " + rs.getString("created_at"));
                }
            }
            String addrSql = "SELECT * FROM addresses WHERE user_id = ? ORDER BY is_default DESC, id";
            try (PreparedStatement stmt = conn.prepareStatement(addrSql)) {
                stmt.setInt(1, userId);
                try (ResultSet rs = stmt.executeQuery()) {
                    System.out.println("\n收货地址:");
                    int count = 0;
                    while (rs.next()) {
                        count++;
                        System.out.println("[" + count + "] " + rs.getString("receiver_name") + " " + rs.getString("phone") +
                            (rs.getInt("is_default") == 1 ? " [默认]" : ""));
                        System.out.println("    " + rs.getString("province") + " " + rs.getString("city") + " " +
                            (rs.getString("district") != null ? rs.getString("district") + " " : "") + rs.getString("detail"));
                    }
                    if (count == 0) System.out.println("(无收货地址)");
                }
            }
        } catch (SQLException e) {
            System.out.println("查询失败: " + e.getMessage());
        } catch (InterruptedException e) {
            System.out.println("系统繁忙");
        } finally {
            DBManager.releaseConnection(conn);
        }
    }

    private static void setAdmin(List<String> args) {
        if (args.isEmpty()) {
            System.out.println("用法: set-admin <用户ID或用户名>");
            return;
        }
        String target = args.get(0);
        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            String findSql = "SELECT id, username, is_admin FROM users WHERE id = ? OR username = ?";
            int userId = -1;
            String username = null;
            int currentAdmin = 0;
            try (PreparedStatement stmt = conn.prepareStatement(findSql)) {
                try { stmt.setInt(1, Integer.parseInt(target)); }
                catch (NumberFormatException e) { stmt.setInt(1, -1); }
                stmt.setString(2, target);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        userId = rs.getInt("id");
                        username = rs.getString("username");
                        currentAdmin = rs.getInt("is_admin");
                    }
                }
            }
            if (userId == -1) { System.out.println("错误: 未找到用户 '" + target + "'"); return; }

            String updateSql = "UPDATE users SET is_admin = ? WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(updateSql)) {
                int newAdmin = currentAdmin == 1 ? 0 : 1;
                stmt.setInt(1, newAdmin);
                stmt.setInt(2, userId);
                stmt.executeUpdate();
                System.out.println("用户 '" + username + "' 的管理员状态已设置为: " + (newAdmin == 1 ? "是" : "否"));
            }
        } catch (SQLException e) {
            System.out.println("设置失败: " + e.getMessage());
        } catch (InterruptedException e) {
            System.out.println("系统繁忙");
        } finally {
            DBManager.releaseConnection(conn);
        }
    }

    private static String hashPassword(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) { throw new RuntimeException(e); }
    }
}
