package top.mcocet.handler;

import com.sun.net.httpserver.HttpExchange;
import top.mcocet.db.DBManager;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

public class AuthHandler {

    // 内存中存储的会话: token -> SessionInfo
    private static final ConcurrentHashMap<String, SessionInfo> sessions = new ConcurrentHashMap<>();

    public static class SessionInfo {
        public final int userId;
        public final String username;
        public final boolean isAdmin;
        public final long createdAt;

        public SessionInfo(int userId, String username, boolean isAdmin) {
            this.userId = userId;
            this.username = username;
            this.isAdmin = isAdmin;
            this.createdAt = System.currentTimeMillis();
        }
    }

    /**
     * 生成随机 Token
     */
    public static String generateToken() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 创建会话
     */
    public static String createSession(int userId, String username, boolean isAdmin) {
        String token = generateToken();
        sessions.put(token, new SessionInfo(userId, username, isAdmin));
        return token;
    }

    /**
     * 根据 Token 获取会话信息
     */
    public static SessionInfo getSession(String token) {
        if (token == null || token.isEmpty()) return null;
        return sessions.get(token);
    }

    /**
     * 移除会话（登出）
     */
    public static void removeSession(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    /**
     * 从请求头中获取 Token
     */
    public static String extractToken(HttpExchange exchange) {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return null;
    }

    /**
     * 验证请求是否为管理员，如果不是则发送 401 响应并返回 false
     */
    public static boolean requireAdmin(HttpExchange exchange) throws IOException {
        String token = extractToken(exchange);
        SessionInfo session = getSession(token);

        if (session == null) {
            sendAuthResponse(exchange, 401, "未登录或登录已过期，请重新登录");
            return false;
        }

        if (!session.isAdmin) {
            sendAuthResponse(exchange, 403, "权限不足，需要管理员身份");
            return false;
        }

        return true;
    }

    /**
     * 验证请求是否已登录（普通用户或管理员），如果不是则发送 401 响应并返回 false
     */
    public static boolean requireLogin(HttpExchange exchange) throws IOException {
        String token = extractToken(exchange);
        SessionInfo session = getSession(token);

        if (session == null) {
            sendAuthResponse(exchange, 401, "未登录或登录已过期，请重新登录");
            return false;
        }

        return true;
    }

    /**
     * 获取当前登录用户的会话信息，未登录返回 null
     */
    public static SessionInfo getCurrentSession(HttpExchange exchange) {
        String token = extractToken(exchange);
        return getSession(token);
    }

    private static void sendAuthResponse(HttpExchange exchange, int statusCode, String message) throws IOException {
        String response = "{\"success\":false,\"message\":\"" + escapeJson(message) + "\"}";
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
