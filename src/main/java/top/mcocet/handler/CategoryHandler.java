package top.mcocet.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import top.mcocet.db.DBManager;
import top.mcocet.model.Category;

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

public class CategoryHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");

        if ("GET".equalsIgnoreCase(method) && "/api/categories".equals(path)) {
            handleGetCategories(exchange);
        } else if ("POST".equalsIgnoreCase(method) && "/api/admin/categories".equals(path)) {
            if (!AuthHandler.requireAdmin(exchange)) return;
            handleAddCategory(exchange);
        } else if ("PUT".equalsIgnoreCase(method) && path.matches("/api/admin/categories/\\d+")) {
            if (!AuthHandler.requireAdmin(exchange)) return;
            handleUpdateCategory(exchange, path);
        } else if ("DELETE".equalsIgnoreCase(method) && path.matches("/api/admin/categories/\\d+")) {
            if (!AuthHandler.requireAdmin(exchange)) return;
            handleDeleteCategory(exchange, path);
        } else if ("OPTIONS".equalsIgnoreCase(method)) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
            exchange.sendResponseHeaders(204, -1);
        } else {
            sendResponse(exchange, 404, "{\"success\":false,\"message\":\"接口不存在\"}");
        }
    }

    private void handleGetCategories(HttpExchange exchange) throws IOException {
        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            String sql = "SELECT * FROM categories ORDER BY sort_order, id";
            List<Category> categories = new ArrayList<>();
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    categories.add(new Category(
                        rs.getInt("id"),
                        rs.getString("name"),
                        rs.getInt("sort_order"),
                        rs.getString("created_at")
                    ));
                }
            }
            StringBuilder json = new StringBuilder("{\"success\":true,\"data\":[");
            for (int i = 0; i < categories.size(); i++) {
                Category c = categories.get(i);
                json.append(String.format(
                    "{\"id\":%d,\"name\":\"%s\",\"sortOrder\":%d}",
                    c.getId(), escapeJson(c.getName()), c.getSortOrder()
                ));
                if (i < categories.size() - 1) json.append(",");
            }
            json.append("]}");
            sendResponse(exchange, 200, json.toString());
        } catch (SQLException | InterruptedException e) {
            sendResponse(exchange, 500, "{\"success\":false,\"message\":\"查询失败\"}");
        } finally {
            DBManager.releaseConnection(conn);
        }
    }

    private void handleAddCategory(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        String name = getParam(body, "name");
        String sortOrderStr = getParam(body, "sortOrder");

        if (name == null || name.isBlank()) {
            sendResponse(exchange, 400, "{\"success\":false,\"message\":\"分区名称不能为空\"}");
            return;
        }

        int sortOrder = 0;
        if (sortOrderStr != null) {
            try { sortOrder = Integer.parseInt(sortOrderStr); } catch (NumberFormatException ignored) {}
        }

        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            String sql = "INSERT INTO categories (name, sort_order) VALUES (?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                stmt.setString(1, name);
                stmt.setInt(2, sortOrder);
                stmt.executeUpdate();
                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    if (rs.next()) {
                        sendResponse(exchange, 200,
                            "{\"success\":true,\"message\":\"添加成功\",\"categoryId\":" + rs.getInt(1) + "}");
                    }
                }
            }
        } catch (SQLException | InterruptedException e) {
            sendResponse(exchange, 500, "{\"success\":false,\"message\":\"添加失败\"}");
        } finally {
            DBManager.releaseConnection(conn);
        }
    }

    private void handleUpdateCategory(HttpExchange exchange, String path) throws IOException {
        int categoryId = Integer.parseInt(path.substring("/api/admin/categories/".length()));
        String body = readBody(exchange);
        String name = getParam(body, "name");
        String sortOrderStr = getParam(body, "sortOrder");

        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            StringBuilder sql = new StringBuilder("UPDATE categories SET ");
            List<Object> params = new ArrayList<>();
            boolean first = true;

            if (name != null) { sql.append(first ? "" : ", ").append("name = ?"); params.add(name); first = false; }
            if (sortOrderStr != null) { sql.append(first ? "" : ", ").append("sort_order = ?"); params.add(Integer.parseInt(sortOrderStr)); first = false; }

            if (first) {
                sendResponse(exchange, 400, "{\"success\":false,\"message\":\"没有要更新的字段\"}");
                return;
            }

            sql.append(" WHERE id = ?");
            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    stmt.setObject(i + 1, params.get(i));
                }
                stmt.setInt(params.size() + 1, categoryId);
                int rows = stmt.executeUpdate();
                if (rows > 0) {
                    sendResponse(exchange, 200, "{\"success\":true,\"message\":\"更新成功\"}");
                } else {
                    sendResponse(exchange, 404, "{\"success\":false,\"message\":\"分区不存在\"}");
                }
            }
        } catch (SQLException | InterruptedException e) {
            sendResponse(exchange, 500, "{\"success\":false,\"message\":\"更新失败\"}");
        } finally {
            DBManager.releaseConnection(conn);
        }
    }

    private void handleDeleteCategory(HttpExchange exchange, String path) throws IOException {
        int categoryId = Integer.parseInt(path.substring("/api/admin/categories/".length()));
        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            // 将属于该分区的商品移到默认分区(0)
            try (PreparedStatement stmt = conn.prepareStatement("UPDATE products SET category_id = 0 WHERE category_id = ?")) {
                stmt.setInt(1, categoryId);
                stmt.executeUpdate();
            }
            String sql = "DELETE FROM categories WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, categoryId);
                int rows = stmt.executeUpdate();
                if (rows > 0) {
                    sendResponse(exchange, 200, "{\"success\":true,\"message\":\"删除成功\"}");
                } else {
                    sendResponse(exchange, 404, "{\"success\":false,\"message\":\"分区不存在\"}");
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
