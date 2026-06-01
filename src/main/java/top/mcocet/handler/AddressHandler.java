package top.mcocet.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import top.mcocet.db.DBManager;
import top.mcocet.model.Address;

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

public class AddressHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");

        if ("GET".equalsIgnoreCase(method) && path.matches("/api/addresses/\\d+")) {
            handleGetAddresses(exchange, path);
        } else if ("POST".equalsIgnoreCase(method) && "/api/address".equals(path)) {
            handleAddAddress(exchange);
        } else if ("PUT".equalsIgnoreCase(method) && path.matches("/api/address/\\d+")) {
            handleUpdateAddress(exchange, path);
        } else if ("DELETE".equalsIgnoreCase(method) && path.matches("/api/address/\\d+")) {
            handleDeleteAddress(exchange, path);
        } else if ("POST".equalsIgnoreCase(method) && path.matches("/api/address/\\d+/default")) {
            handleSetDefault(exchange, path);
        } else {
            sendResponse(exchange, 404, "{\"success\":false,\"message\":\"接口不存在\"}");
        }
    }

    private void handleGetAddresses(HttpExchange exchange, String path) throws IOException {
        AuthHandler.SessionInfo session = AuthHandler.getCurrentSession(exchange);
        if (session == null) {
            sendResponse(exchange, 401, "{\"success\":false,\"message\":\"未登录，请先登录\"}");
            return;
        }
        int userId = Integer.parseInt(path.substring("/api/addresses/".length()));
        if (userId != session.userId) {
            sendResponse(exchange, 403, "{\"success\":false,\"message\":\"无权查看他人地址\"}");
            return;
        }
        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            String sql = "SELECT * FROM addresses WHERE user_id = ? ORDER BY is_default DESC, id DESC";
            List<Address> addresses = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, userId);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        addresses.add(new Address(
                            rs.getInt("id"),
                            rs.getInt("user_id"),
                            rs.getString("receiver_name"),
                            rs.getString("phone"),
                            rs.getString("province"),
                            rs.getString("city"),
                            rs.getString("district"),
                            rs.getString("detail"),
                            rs.getInt("is_default") == 1,
                            rs.getString("created_at")
                        ));
                    }
                }
            }
            sendResponse(exchange, 200, toJsonArray(addresses));
        } catch (SQLException | InterruptedException e) {
            sendResponse(exchange, 500, "{\"success\":false,\"message\":\"查询失败\"}");
        } finally {
            DBManager.releaseConnection(conn);
        }
    }

    private void handleAddAddress(HttpExchange exchange) throws IOException {
        AuthHandler.SessionInfo session = AuthHandler.getCurrentSession(exchange);
        if (session == null) {
            sendResponse(exchange, 401, "{\"success\":false,\"message\":\"未登录，请先登录\"}");
            return;
        }
        String body = readBody(exchange);
        int userId = parseInt(getParam(body, "userId"));
        if (userId != session.userId) {
            sendResponse(exchange, 403, "{\"success\":false,\"message\":\"无权为他人添加地址\"}");
            return;
        }
        String receiverName = getParam(body, "receiverName");
        String phone = getParam(body, "phone");
        String province = getParam(body, "province");
        String city = getParam(body, "city");
        String district = getParam(body, "district");
        String detail = getParam(body, "detail");
        boolean isDefault = "true".equals(getParam(body, "isDefault"));

        if (receiverName == null || phone == null || province == null || city == null || detail == null) {
            sendResponse(exchange, 400, "{\"success\":false,\"message\":\"请填写完整地址信息\"}");
            return;
        }

        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            conn.setAutoCommit(false);

            if (isDefault) {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE addresses SET is_default = 0 WHERE user_id = ?")) {
                    stmt.setInt(1, userId);
                    stmt.executeUpdate();
                }
            }

            String sql = "INSERT INTO addresses (user_id, receiver_name, phone, province, city, district, detail, is_default) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                stmt.setInt(1, userId);
                stmt.setString(2, receiverName);
                stmt.setString(3, phone);
                stmt.setString(4, province);
                stmt.setString(5, city);
                stmt.setString(6, district);
                stmt.setString(7, detail);
                stmt.setInt(8, isDefault ? 1 : 0);
                stmt.executeUpdate();

                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    if (rs.next()) {
                        conn.commit();
                        sendResponse(exchange, 200,
                            "{\"success\":true,\"message\":\"添加成功\",\"addressId\":" + rs.getInt(1) + "}");
                    }
                }
            }
        } catch (SQLException | InterruptedException e) {
            if (conn != null) {
                try { conn.rollback(); } catch (SQLException ignored) {}
            }
            sendResponse(exchange, 500, "{\"success\":false,\"message\":\"添加失败\"}");
        } finally {
            if (conn != null) {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
                DBManager.releaseConnection(conn);
            }
        }
    }

    private void handleUpdateAddress(HttpExchange exchange, String path) throws IOException {
        AuthHandler.SessionInfo session = AuthHandler.getCurrentSession(exchange);
        if (session == null) {
            sendResponse(exchange, 401, "{\"success\":false,\"message\":\"未登录，请先登录\"}");
            return;
        }
        int addressId = Integer.parseInt(path.substring("/api/address/".length()));
        String body = readBody(exchange);
        String receiverName = getParam(body, "receiverName");
        String phone = getParam(body, "phone");
        String province = getParam(body, "province");
        String city = getParam(body, "city");
        String district = getParam(body, "district");
        String detail = getParam(body, "detail");

        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            // 先校验地址是否属于当前用户
            String checkSql = "SELECT user_id FROM addresses WHERE id = ?";
            try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                checkStmt.setInt(1, addressId);
                try (ResultSet rs = checkStmt.executeQuery()) {
                    if (!rs.next()) {
                        sendResponse(exchange, 404, "{\"success\":false,\"message\":\"地址不存在\"}");
                        return;
                    }
                    if (rs.getInt("user_id") != session.userId) {
                        sendResponse(exchange, 403, "{\"success\":false,\"message\":\"无权修改他人地址\"}");
                        return;
                    }
                }
            }

            String sql = "UPDATE addresses SET receiver_name=?, phone=?, province=?, city=?, district=?, detail=? WHERE id=?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, receiverName);
                stmt.setString(2, phone);
                stmt.setString(3, province);
                stmt.setString(4, city);
                stmt.setString(5, district);
                stmt.setString(6, detail);
                stmt.setInt(7, addressId);
                int rows = stmt.executeUpdate();
                if (rows > 0) {
                    sendResponse(exchange, 200, "{\"success\":true,\"message\":\"更新成功\"}");
                } else {
                    sendResponse(exchange, 404, "{\"success\":false,\"message\":\"地址不存在\"}");
                }
            }
        } catch (SQLException | InterruptedException e) {
            sendResponse(exchange, 500, "{\"success\":false,\"message\":\"更新失败\"}");
        } finally {
            DBManager.releaseConnection(conn);
        }
    }

    private void handleDeleteAddress(HttpExchange exchange, String path) throws IOException {
        AuthHandler.SessionInfo session = AuthHandler.getCurrentSession(exchange);
        if (session == null) {
            sendResponse(exchange, 401, "{\"success\":false,\"message\":\"未登录，请先登录\"}");
            return;
        }
        int addressId = Integer.parseInt(path.substring("/api/address/".length()));
        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            // 先校验地址是否属于当前用户
            String checkSql = "SELECT user_id FROM addresses WHERE id = ?";
            try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                checkStmt.setInt(1, addressId);
                try (ResultSet rs = checkStmt.executeQuery()) {
                    if (!rs.next()) {
                        sendResponse(exchange, 404, "{\"success\":false,\"message\":\"地址不存在\"}");
                        return;
                    }
                    if (rs.getInt("user_id") != session.userId) {
                        sendResponse(exchange, 403, "{\"success\":false,\"message\":\"无权删除他人地址\"}");
                        return;
                    }
                }
            }

            String sql = "DELETE FROM addresses WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, addressId);
                int rows = stmt.executeUpdate();
                if (rows > 0) {
                    sendResponse(exchange, 200, "{\"success\":true,\"message\":\"删除成功\"}");
                } else {
                    sendResponse(exchange, 404, "{\"success\":false,\"message\":\"地址不存在\"}");
                }
            }
        } catch (SQLException | InterruptedException e) {
            sendResponse(exchange, 500, "{\"success\":false,\"message\":\"删除失败\"}");
        } finally {
            DBManager.releaseConnection(conn);
        }
    }

    private void handleSetDefault(HttpExchange exchange, String path) throws IOException {
        AuthHandler.SessionInfo session = AuthHandler.getCurrentSession(exchange);
        if (session == null) {
            sendResponse(exchange, 401, "{\"success\":false,\"message\":\"未登录，请先登录\"}");
            return;
        }
        int addressId = Integer.parseInt(path.substring("/api/address/".length(), path.length() - "/default".length()));
        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            conn.setAutoCommit(false);

            int userId;
            try (PreparedStatement stmt = conn.prepareStatement("SELECT user_id FROM addresses WHERE id = ?")) {
                stmt.setInt(1, addressId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        sendResponse(exchange, 404, "{\"success\":false,\"message\":\"地址不存在\"}");
                        return;
                    }
                    userId = rs.getInt("user_id");
                }
            }

            if (userId != session.userId) {
                sendResponse(exchange, 403, "{\"success\":false,\"message\":\"无权设置他人地址\"}");
                return;
            }

            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE addresses SET is_default = 0 WHERE user_id = ?")) {
                stmt.setInt(1, userId);
                stmt.executeUpdate();
            }

            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE addresses SET is_default = 1 WHERE id = ?")) {
                stmt.setInt(1, addressId);
                stmt.executeUpdate();
            }

            conn.commit();
            sendResponse(exchange, 200, "{\"success\":true,\"message\":\"设置成功\"}");
        } catch (SQLException | InterruptedException e) {
            if (conn != null) {
                try { conn.rollback(); } catch (SQLException ignored) {}
            }
            sendResponse(exchange, 500, "{\"success\":false,\"message\":\"设置失败\"}");
        } finally {
            if (conn != null) {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
                DBManager.releaseConnection(conn);
            }
        }
    }

    private String toJsonArray(List<Address> addresses) {
        StringBuilder sb = new StringBuilder("{\"success\":true,\"data\":[");
        for (int i = 0; i < addresses.size(); i++) {
            Address a = addresses.get(i);
            sb.append(String.format(
                "{\"id\":%d,\"userId\":%d,\"receiverName\":\"%s\",\"phone\":\"%s\",\"province\":\"%s\",\"city\":\"%s\",\"district\":\"%s\",\"detail\":\"%s\",\"isDefault\":%b}",
                a.getId(), a.getUserId(), a.getReceiverName(), a.getPhone(), a.getProvince(),
                a.getCity(), a.getDistrict(), a.getDetail(), a.isDefault()
            ));
            if (i < addresses.size() - 1) sb.append(",");
        }
        sb.append("]}");
        return sb.toString();
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

    private int parseInt(String value) {
        try {
            return value != null ? Integer.parseInt(value) : 0;
        } catch (NumberFormatException e) {
            return 0;
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
