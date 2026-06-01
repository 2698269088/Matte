package top.mcocet.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import top.mcocet.db.DBManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class AdminHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");

        if ("GET".equalsIgnoreCase(method) && "/api/admin/orders".equals(path)) {
            if (!AuthHandler.requireAdmin(exchange)) return;
            handleGetOrders(exchange);
        } else if ("POST".equalsIgnoreCase(method) && path.matches("/api/admin/orders/\\d+/ship")) {
            if (!AuthHandler.requireAdmin(exchange)) return;
            handleShipOrder(exchange, path);
        } else if ("POST".equalsIgnoreCase(method) && path.matches("/api/admin/orders/\\d+/deliver")) {
            if (!AuthHandler.requireAdmin(exchange)) return;
            handleDeliverOrder(exchange, path);
        } else if ("GET".equalsIgnoreCase(method) && "/api/admin/users".equals(path)) {
            if (!AuthHandler.requireAdmin(exchange)) return;
            handleGetUsers(exchange);
        } else if ("POST".equalsIgnoreCase(method) && path.matches("/api/admin/users/\\d+/unlock")) {
            if (!AuthHandler.requireAdmin(exchange)) return;
            handleUnlockUser(exchange, path);
        } else if ("POST".equalsIgnoreCase(method) && path.matches("/api/admin/users/\\d+/enable")) {
            if (!AuthHandler.requireAdmin(exchange)) return;
            handleEnableUser(exchange, path);
        } else if ("GET".equalsIgnoreCase(method) && path.matches("/api/merchants/\\d+/qr")) {
            handleGetMerchantQr(exchange, path);
        } else if ("GET".equalsIgnoreCase(method) && "/api/merchants".equals(path)) {
            handleGetMerchants(exchange);
        } else if ("POST".equalsIgnoreCase(method) && "/api/merchants".equals(path)) {
            if (!AuthHandler.requireAdmin(exchange)) return;
            handleAddMerchant(exchange);
        } else if ("PUT".equalsIgnoreCase(method) && path.matches("/api/merchants/\\d+")) {
            if (!AuthHandler.requireAdmin(exchange)) return;
            handleUpdateMerchant(exchange, path);
        } else if ("DELETE".equalsIgnoreCase(method) && path.matches("/api/merchants/\\d+")) {
            if (!AuthHandler.requireAdmin(exchange)) return;
            handleDeleteMerchant(exchange, path);
        } else if ("OPTIONS".equalsIgnoreCase(method)) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
            exchange.sendResponseHeaders(204, -1);
            return;
        } else {
            sendResponse(exchange, 404, "{\"success\":false,\"message\":\"接口不存在\"}");
        }
    }

    private void handleGetUsers(HttpExchange exchange) throws IOException {
        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            String sql = "SELECT id, username, phone, email, is_admin, failed_attempts, lockout_until, is_disabled, created_at FROM users ORDER BY created_at DESC";
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
                StringBuilder json = new StringBuilder("{\"success\":true,\"data\":[");
                boolean first = true;
                while (rs.next()) {
                    if (!first) json.append(",");
                    first = false;
                    String lockoutUntil = rs.getString("lockout_until");
                    json.append(String.format(
                            "{\"id\":%d,\"username\":\"%s\",\"phone\":\"%s\",\"email\":\"%s\",\"isAdmin\":%d,\"failedAttempts\":%d,\"lockoutUntil\":\"%s\",\"isDisabled\":%d,\"createdAt\":\"%s\"}",
                            rs.getInt("id"),
                            escapeJson(rs.getString("username")),
                            escapeJson(rs.getString("phone")),
                            escapeJson(rs.getString("email")),
                            rs.getInt("is_admin"),
                            rs.getInt("failed_attempts"),
                            lockoutUntil != null ? escapeJson(lockoutUntil) : "",
                            rs.getInt("is_disabled"),
                            escapeJson(rs.getString("created_at"))
                    ));
                }
                json.append("]}");
                sendResponse(exchange, 200, json.toString());
            }
        } catch (SQLException | InterruptedException e) {
            sendResponse(exchange, 500, "{\"success\":false,\"message\":\"查询失败\"}");
        } finally {
            DBManager.releaseConnection(conn);
        }
    }

    private void handleUnlockUser(HttpExchange exchange, String path) throws IOException {
        int userId = Integer.parseInt(path.substring("/api/admin/users/".length(), path.length() - "/unlock".length()));
        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            String sql = "UPDATE users SET failed_attempts = 0, lockout_until = NULL WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, userId);
                int rows = stmt.executeUpdate();
                if (rows > 0) {
                    sendResponse(exchange, 200, "{\"success\":true,\"message\":\"解锁成功\"}");
                } else {
                    sendResponse(exchange, 400, "{\"success\":false,\"message\":\"用户不存在\"}");
                }
            }
        } catch (SQLException | InterruptedException e) {
            sendResponse(exchange, 500, "{\"success\":false,\"message\":\"解锁失败\"}");
        } finally {
            DBManager.releaseConnection(conn);
        }
    }

    private void handleEnableUser(HttpExchange exchange, String path) throws IOException {
        int userId = Integer.parseInt(path.substring("/api/admin/users/".length(), path.length() - "/enable".length()));
        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            String sql = "UPDATE users SET is_disabled = 0, failed_attempts = 0, lockout_until = NULL WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, userId);
                int rows = stmt.executeUpdate();
                if (rows > 0) {
                    sendResponse(exchange, 200, "{\"success\":true,\"message\":\"账户已启用\"}");
                } else {
                    sendResponse(exchange, 400, "{\"success\":false,\"message\":\"用户不存在\"}");
                }
            }
        } catch (SQLException | InterruptedException e) {
            sendResponse(exchange, 500, "{\"success\":false,\"message\":\"启用失败\"}");
        } finally {
            DBManager.releaseConnection(conn);
        }
    }

    private void handleGetOrders(HttpExchange exchange) throws IOException {
        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            String sql = """
                SELECT o.*, u.username, a.receiver_name, a.phone, a.province, a.city, a.district, a.detail, m.name as merchant_name
                FROM orders o
                JOIN users u ON o.user_id = u.id
                LEFT JOIN addresses a ON o.address_id = a.id
                LEFT JOIN merchants m ON o.merchant_id = m.id
                ORDER BY o.created_at DESC
                """;
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
                StringBuilder json = new StringBuilder("{\"success\":true,\"data\":[");
                boolean first = true;
                while (rs.next()) {
                    if (!first) json.append(",");
                    first = false;
                    String status = rs.getString("status");
                    String shippedAt = rs.getString("shipped_at");
                    String deliveredAt = rs.getString("delivered_at");
                    String merchantName = rs.getString("merchant_name");
                    json.append(String.format(
                            "{\"id\":%d,\"userId\":%d,\"username\":\"%s\",\"productId\":%d,\"productName\":\"%s\",\"price\":%.2f,\"quantity\":%d,\"status\":\"%s\",\"createdAt\":\"%s\",\"shippedAt\":\"%s\",\"deliveredAt\":\"%s\",\"merchantName\":\"%s\",\"address\":{\"receiverName\":\"%s\",\"phone\":\"%s\",\"province\":\"%s\",\"city\":\"%s\",\"district\":\"%s\",\"detail\":\"%s\"}}",
                            rs.getInt("id"),
                            rs.getInt("user_id"),
                            escapeJson(rs.getString("username")),
                            rs.getInt("product_id"),
                            escapeJson(rs.getString("product_name")),
                            rs.getDouble("price"),
                            rs.getInt("quantity"),
                            escapeJson(status),
                            escapeJson(rs.getString("created_at")),
                            shippedAt != null ? escapeJson(shippedAt) : "",
                            deliveredAt != null ? escapeJson(deliveredAt) : "",
                            escapeJson(merchantName != null ? merchantName : ""),
                            escapeJson(rs.getString("receiver_name")),
                            escapeJson(rs.getString("phone")),
                            escapeJson(rs.getString("province")),
                            escapeJson(rs.getString("city")),
                            escapeJson(rs.getString("district")),
                            escapeJson(rs.getString("detail"))
                    ));
                }
                json.append("]}");
                sendResponse(exchange, 200, json.toString());
            }
        } catch (SQLException | InterruptedException e) {
            sendResponse(exchange, 500, "{\"success\":false,\"message\":\"查询失败\"}");
        } finally {
            DBManager.releaseConnection(conn);
        }
    }

    private void handleShipOrder(HttpExchange exchange, String path) throws IOException {
        int orderId = Integer.parseInt(path.substring("/api/admin/orders/".length(), path.length() - "/ship".length()));
        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            String sql = "UPDATE orders SET status = 'shipping', shipped_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'pending'";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, orderId);
                int rows = stmt.executeUpdate();
                if (rows > 0) {
                    sendResponse(exchange, 200, "{\"success\":true,\"message\":\"已标记为派送中\"}");
                } else {
                    sendResponse(exchange, 400, "{\"success\":false,\"message\":\"订单不存在或状态不正确\"}");
                }
            }
        } catch (SQLException | InterruptedException e) {
            sendResponse(exchange, 500, "{\"success\":false,\"message\":\"操作失败\"}");
        } finally {
            DBManager.releaseConnection(conn);
        }
    }

    private void handleDeliverOrder(HttpExchange exchange, String path) throws IOException {
        int orderId = Integer.parseInt(path.substring("/api/admin/orders/".length(), path.length() - "/deliver".length()));
        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            String sql = "UPDATE orders SET status = 'delivered', delivered_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'shipping'";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, orderId);
                int rows = stmt.executeUpdate();
                if (rows > 0) {
                    sendResponse(exchange, 200, "{\"success\":true,\"message\":\"已标记为已送达\"}");
                } else {
                    sendResponse(exchange, 400, "{\"success\":false,\"message\":\"订单不存在或状态不正确\"}");
                }
            }
        } catch (SQLException | InterruptedException e) {
            sendResponse(exchange, 500, "{\"success\":false,\"message\":\"操作失败\"}");
        } finally {
            DBManager.releaseConnection(conn);
        }
    }

    private void handleGetMerchantQr(HttpExchange exchange, String path) throws IOException {
        int merchantId = Integer.parseInt(path.substring("/api/merchants/".length(), path.length() - "/qr".length()));
        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            String sql = "SELECT qr_path FROM merchants WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, merchantId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        String qrPath = rs.getString("qr_path");
                        sendResponse(exchange, 200, "{\"success\":true,\"data\":{\"qrPath\":\"" + escapeJson(qrPath != null ? qrPath : "") + "\"}}");
                    } else {
                        sendResponse(exchange, 404, "{\"success\":false,\"message\":\"商家不存在\"}");
                    }
                }
            }
        } catch (SQLException | InterruptedException e) {
            sendResponse(exchange, 500, "{\"success\":false,\"message\":\"查询失败\"}");
        } finally {
            DBManager.releaseConnection(conn);
        }
    }

    private void handleGetMerchants(HttpExchange exchange) throws IOException {
        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            String sql = "SELECT id, name, phone, address, qr_path FROM merchants ORDER BY id";
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
                StringBuilder json = new StringBuilder("{\"success\":true,\"data\":[");
                boolean first = true;
                while (rs.next()) {
                    if (!first) json.append(",");
                    first = false;
                    json.append(String.format(
                            "{\"id\":%d,\"name\":\"%s\",\"phone\":\"%s\",\"address\":\"%s\",\"qrPath\":\"%s\"}",
                            rs.getInt("id"),
                            escapeJson(rs.getString("name")),
                            escapeJson(rs.getString("phone") != null ? rs.getString("phone") : ""),
                            escapeJson(rs.getString("address") != null ? rs.getString("address") : ""),
                            escapeJson(rs.getString("qr_path") != null ? rs.getString("qr_path") : "")
                    ));
                }
                json.append("]}");
                sendResponse(exchange, 200, json.toString());
            }
        } catch (SQLException | InterruptedException e) {
            sendResponse(exchange, 500, "{\"success\":false,\"message\":\"查询失败\"}");
        } finally {
            DBManager.releaseConnection(conn);
        }
    }

    private void handleAddMerchant(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        String name = getParam(body, "name");
        String phone = getParam(body, "phone");
        String address = getParam(body, "address");
        String qrPath = getParam(body, "qrPath");

        if (name == null || name.isBlank()) {
            sendResponse(exchange, 400, "{\"success\":false,\"message\":\"商家名称不能为空\"}");
            return;
        }

        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            String sql = "INSERT INTO merchants (name, phone, address, qr_path) VALUES (?, ?, ?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                stmt.setString(1, name);
                stmt.setString(2, phone);
                stmt.setString(3, address);
                stmt.setString(4, qrPath != null ? qrPath : "");
                stmt.executeUpdate();
                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    if (rs.next()) {
                        sendResponse(exchange, 200, "{\"success\":true,\"message\":\"添加成功\",\"merchantId\":" + rs.getInt(1) + "}");
                    }
                }
            }
        } catch (SQLException | InterruptedException e) {
            sendResponse(exchange, 500, "{\"success\":false,\"message\":\"添加失败\"}");
        } finally {
            DBManager.releaseConnection(conn);
        }
    }

    private void handleUpdateMerchant(HttpExchange exchange, String path) throws IOException {
        int merchantId = Integer.parseInt(path.substring("/api/merchants/".length()));
        String body = readBody(exchange);
        String name = getParam(body, "name");
        String phone = getParam(body, "phone");
        String address = getParam(body, "address");
        String qrPath = getParam(body, "qrPath");

        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            StringBuilder sql = new StringBuilder("UPDATE merchants SET ");
            List<Object> params = new ArrayList<>();
            boolean first = true;

            if (name != null) { sql.append(first ? "" : ", ").append("name = ?"); params.add(name); first = false; }
            if (phone != null) { sql.append(first ? "" : ", ").append("phone = ?"); params.add(phone); first = false; }
            if (address != null) { sql.append(first ? "" : ", ").append("address = ?"); params.add(address); first = false; }
            if (qrPath != null) { sql.append(first ? "" : ", ").append("qr_path = ?"); params.add(qrPath); first = false; }

            if (first) {
                sendResponse(exchange, 400, "{\"success\":false,\"message\":\"没有要更新的字段\"}");
                return;
            }

            sql.append(" WHERE id = ?");
            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    stmt.setObject(i + 1, params.get(i));
                }
                stmt.setInt(params.size() + 1, merchantId);
                int rows = stmt.executeUpdate();
                if (rows > 0) {
                    sendResponse(exchange, 200, "{\"success\":true,\"message\":\"更新成功\"}");
                } else {
                    sendResponse(exchange, 404, "{\"success\":false,\"message\":\"商家不存在\"}");
                }
            }
        } catch (SQLException | InterruptedException e) {
            sendResponse(exchange, 500, "{\"success\":false,\"message\":\"更新失败\"}");
        } finally {
            DBManager.releaseConnection(conn);
        }
    }

    private void handleDeleteMerchant(HttpExchange exchange, String path) throws IOException {
        int merchantId = Integer.parseInt(path.substring("/api/merchants/".length()));
        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            String sql = "DELETE FROM merchants WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, merchantId);
                int rows = stmt.executeUpdate();
                if (rows > 0) {
                    sendResponse(exchange, 200, "{\"success\":true,\"message\":\"删除成功\"}");
                } else {
                    sendResponse(exchange, 404, "{\"success\":false,\"message\":\"商家不存在\"}");
                }
            }
        } catch (SQLException | InterruptedException e) {
            sendResponse(exchange, 500, "{\"success\":false,\"message\":\"删除失败\"}");
        } finally {
            DBManager.releaseConnection(conn);
        }
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    private String getParam(String body, String name) {
        if (body == null || body.isEmpty()) return null;
        String[] pairs = body.split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}