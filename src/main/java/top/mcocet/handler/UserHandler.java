package top.mcocet.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import top.mcocet.db.DBManager;
import top.mcocet.model.User;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

public class UserHandler implements HttpHandler {

    private static final int MAX_ATTEMPTS_BEFORE_LOCKOUT = 10;
    private static final int MAX_ATTEMPTS_BEFORE_DISABLE = 50;
    private static final int LOCKOUT_DURATION_HOURS = 1;

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");

        if ("POST".equalsIgnoreCase(method) && "/api/register".equals(path)) {
            handleRegister(exchange);
        } else if ("POST".equalsIgnoreCase(method) && "/api/login".equals(path)) {
            handleLogin(exchange);
        } else if ("GET".equalsIgnoreCase(method) && "/api/user".equals(path)) {
            handleGetUser(exchange);
        } else if ("POST".equalsIgnoreCase(method) && "/api/logout".equals(path)) {
            handleLogout(exchange);
        } else {
            sendResponse(exchange, 404, "{\"success\":false,\"message\":\"接口不存在\"}");
        }
    }

    private void handleRegister(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        String username = getParam(body, "username");
        String password = getParam(body, "password");
        String phone = getParam(body, "phone");
        String email = getParam(body, "email");

        if (username == null || password == null || username.isBlank() || password.isBlank()) {
            sendResponse(exchange, 400, "{\"success\":false,\"message\":\"用户名和密码不能为空\"}");
            return;
        }

        if (username.length() < 3 || username.length() > 20) {
            sendResponse(exchange, 400, "{\"success\":false,\"message\":\"用户名长度需在3-20位之间\"}");
            return;
        }

        if (password.length() < 6) {
            sendResponse(exchange, 400, "{\"success\":false,\"message\":\"密码长度至少6位\"}");
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
                stmt.setString(3, phone);
                stmt.setString(4, email);
                stmt.executeUpdate();

                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    if (rs.next()) {
                        int userId = rs.getInt(1);
                        sendResponse(exchange, 200,
                                "{\"success\":true,\"message\":\"注册成功\",\"userId\":" + userId + ",\"username\":\"" + username + "\"}");
                    }
                }
            }
        } catch (SQLException e) {
            if (e.getMessage().contains("UNIQUE constraint failed")) {
                sendResponse(exchange, 400, "{\"success\":false,\"message\":\"用户名已存在\"}");
            } else {
                sendResponse(exchange, 500, "{\"success\":false,\"message\":\"注册失败：" + e.getMessage() + "\"}");
            }
        } catch (InterruptedException e) {
            sendResponse(exchange, 500, "{\"success\":false,\"message\":\"系统繁忙\"}");
        } finally {
            DBManager.releaseConnection(conn);
        }
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        String username = getParam(body, "username");
        String password = getParam(body, "password");

        if (username == null || password == null) {
            sendResponse(exchange, 400, "{\"success\":false,\"message\":\"用户名和密码不能为空\"}");
            return;
        }

        Connection conn = null;
        try {
            conn = DBManager.getConnection();

            // 先检查用户是否被禁用或锁定
            String checkSql = "SELECT id, username, password_hash, phone, email, is_admin, failed_attempts, lockout_until, is_disabled FROM users WHERE username = ?";
            try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                checkStmt.setString(1, username);
                try (ResultSet rs = checkStmt.executeQuery()) {
                    if (rs.next()) {
                        // 检查是否被禁用
                        if (rs.getInt("is_disabled") == 1) {
                            sendResponse(exchange, 401, "{\"success\":false,\"message\":\"账户已被禁用，请联系管理员\"}");
                            return;
                        }

                        // 检查是否还在锁定时间内
                        String lockoutUntil = rs.getString("lockout_until");
                        if (lockoutUntil != null) {
                            LocalDateTime lockoutEnd = LocalDateTime.parse(lockoutUntil, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                            if (LocalDateTime.now().isBefore(lockoutEnd)) {
                                long minutesRemaining = java.time.Duration.between(LocalDateTime.now(), lockoutEnd).toMinutes();
                                sendResponse(exchange, 401, "{\"success\":false,\"message\":\"账户已被锁定，请" + minutesRemaining + "分钟后再试\"}");
                                return;
                            }
                        }

                        int failedAttempts = rs.getInt("failed_attempts");
                        String storedHash = rs.getString("password_hash");

                        if (storedHash.equals(hashPassword(password))) {
                            // 登录成功，重置失败次数
                            resetFailedAttempts(conn, rs.getInt("id"));

                            int userId = rs.getInt("id");
                            String phone = rs.getString("phone");
                            String email = rs.getString("email");
                            boolean isAdmin = rs.getInt("is_admin") == 1;

                            // 创建服务端会话，生成 Token
                            String token = AuthHandler.createSession(userId, username, isAdmin);

                            sendResponse(exchange, 200,
                                    "{\"success\":true,\"message\":\"登录成功\",\"userId\":" + userId +
                                            ",\"username\":\"" + username + "\",\"phone\":\"" + (phone != null ? phone : "") +
                                            "\",\"email\":\"" + (email != null ? email : "") + "\",\"admin\":" + isAdmin +
                                            ",\"token\":\"" + token + "\"}");
                        } else {
                            // 密码错误，更新失败次数
                            handleFailedLogin(conn, rs.getInt("id"), failedAttempts);
                            int remaining = MAX_ATTEMPTS_BEFORE_LOCKOUT - (failedAttempts + 1);
                            if (remaining > 0) {
                                sendResponse(exchange, 401, "{\"success\":false,\"message\":\"密码错误，还剩" + remaining + "次机会\"}");
                            } else {
                                sendResponse(exchange, 401, "{\"success\":false,\"message\":\"登录失败次数过多，账户已被锁定1小时\"}");
                            }
                        }
                    } else {
                        sendResponse(exchange, 401, "{\"success\":false,\"message\":\"用户不存在\"}");
                    }
                }
            }
        } catch (SQLException e) {
            sendResponse(exchange, 500, "{\"success\":false,\"message\":\"登录失败\"}");
        } catch (InterruptedException e) {
            sendResponse(exchange, 500, "{\"success\":false,\"message\":\"系统繁忙\"}");
        } finally {
            DBManager.releaseConnection(conn);
        }
    }

    private void resetFailedAttempts(Connection conn, int userId) throws SQLException {
        String sql = "UPDATE users SET failed_attempts = 0, lockout_until = NULL WHERE id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, userId);
            stmt.executeUpdate();
        }
    }

    private void handleFailedLogin(Connection conn, int userId, int currentAttempts) throws SQLException {
        int newAttempts = currentAttempts + 1;

        if (newAttempts >= MAX_ATTEMPTS_BEFORE_DISABLE) {
            // 超过50次，禁用账户
            String sql = "UPDATE users SET failed_attempts = ?, is_disabled = 1, lockout_until = NULL WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, newAttempts);
                stmt.setInt(2, userId);
                stmt.executeUpdate();
            }
        } else if (newAttempts >= MAX_ATTEMPTS_BEFORE_LOCKOUT) {
            // 超过10次，锁定1小时
            String sql = "UPDATE users SET failed_attempts = ?, lockout_until = ? WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, newAttempts);
                stmt.setString(2, LocalDateTime.now().plusHours(LOCKOUT_DURATION_HOURS).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                stmt.setInt(3, userId);
                stmt.executeUpdate();
            }
        } else {
            // 普通失败，增加次数
            String sql = "UPDATE users SET failed_attempts = ? WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, newAttempts);
                stmt.setInt(2, userId);
                stmt.executeUpdate();
            }
        }
    }

    private void handleGetUser(HttpExchange exchange) throws IOException {
        sendResponse(exchange, 200, "{\"success\":false,\"message\":\"请使用登录接口\"}");
    }

    private void handleLogout(HttpExchange exchange) throws IOException {
        String token = AuthHandler.extractToken(exchange);
        if (token != null) {
            AuthHandler.removeSession(token);
        }
        sendResponse(exchange, 200, "{\"success\":true,\"message\":\"退出成功\"}");
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    private String getParam(String body, String name) {
        String[] pairs = body.split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private String hashPassword(String password) {
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

    private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}