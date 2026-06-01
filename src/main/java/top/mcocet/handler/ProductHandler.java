package top.mcocet.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import top.mcocet.db.DBManager;
import top.mcocet.model.Product;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class ProductHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");

        if ("GET".equalsIgnoreCase(method) && "/api/products".equals(path)) {
            handleGetProducts(exchange);
        } else if ("POST".equalsIgnoreCase(method) && path.startsWith("/api/buy/")) {
            handleBuy(exchange, path);
        } else if ("POST".equalsIgnoreCase(method) && "/api/admin/products".equals(path)) {
            if (!AuthHandler.requireAdmin(exchange)) return;
            handleAddProduct(exchange);
        } else if ("PUT".equalsIgnoreCase(method) && path.matches("/api/admin/products/\\d+")) {
            if (!AuthHandler.requireAdmin(exchange)) return;
            handleUpdateProduct(exchange, path);
        } else if ("DELETE".equalsIgnoreCase(method) && path.matches("/api/admin/products/\\d+")) {
            if (!AuthHandler.requireAdmin(exchange)) return;
            handleDeleteProduct(exchange, path);
        } else if ("OPTIONS".equalsIgnoreCase(method)) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
            exchange.sendResponseHeaders(204, -1);
            return;
        } else {
            sendResponse(exchange, 404, "{\"success\":false,\"message\":\"接口不存在\"}");
        }
    }

    private void handleGetProducts(HttpExchange exchange) throws IOException {
        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            String sql = "SELECT p.*, c.name as category_name FROM products p LEFT JOIN categories c ON p.category_id = c.id ORDER BY p.category_id, p.id";
            List<Product> products = new ArrayList<>();
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    products.add(new Product(
                        rs.getInt("id"),
                        rs.getString("name"),
                        rs.getString("description"),
                        new BigDecimal(rs.getString("price")),
                        rs.getString("image_path"),
                        rs.getInt("stock"),
                        rs.getInt("category_id")
                    ));
                }
            }
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < products.size(); i++) {
                Product p = products.get(i);
                json.append(String.format(
                    "{\"id\":%d,\"name\":\"%s\",\"description\":\"%s\",\"price\":%.2f,\"imagePath\":\"%s\",\"stock\":%d,\"categoryId\":%d}",
                    p.getId(), escapeJson(p.getName()), escapeJson(p.getDescription()),
                    p.getPrice(), escapeJson(p.getImagePath()), p.getStock(), p.getCategoryId()
                ));
                if (i < products.size() - 1) json.append(",");
            }
            json.append("]");
            sendResponse(exchange, 200, json.toString());
        } catch (SQLException | InterruptedException e) {
            sendResponse(exchange, 500, "{\"success\":false,\"message\":\"查询失败\"}");
        } finally {
            DBManager.releaseConnection(conn);
        }
    }

    private void handleBuy(HttpExchange exchange, String path) throws IOException {
        AuthHandler.SessionInfo session = AuthHandler.getCurrentSession(exchange);
        if (session == null) {
            sendResponse(exchange, 401, "{\"success\":false,\"message\":\"未登录，请先登录\"}");
            return;
        }
        try {
            int productId = Integer.parseInt(path.substring("/api/buy/".length()));
            Connection conn = null;
            try {
                conn = DBManager.getConnection();
                conn.setAutoCommit(false);

                String selectSql = "SELECT stock, name, price FROM products WHERE id = ?";
                int stock = 0;
                String productName = "";
                BigDecimal price = BigDecimal.ZERO;
                try (PreparedStatement stmt = conn.prepareStatement(selectSql)) {
                    stmt.setInt(1, productId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (!rs.next()) {
                            sendResponse(exchange, 404, "{\"success\":false,\"message\":\"商品不存在！\"}");
                            return;
                        }
                        stock = rs.getInt("stock");
                        productName = rs.getString("name");
                        price = new BigDecimal(rs.getString("price"));
                    }
                }

                if (stock <= 0) {
                    sendResponse(exchange, 400, "{\"success\":false,\"message\":\"库存不足！\"}");
                    return;
                }

                String updateSql = "UPDATE products SET stock = stock - 1 WHERE id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(updateSql)) {
                    stmt.setInt(1, productId);
                    stmt.executeUpdate();
                }

                // 创建订单记录，使用当前登录用户ID
                String orderSql = "INSERT INTO orders (user_id, product_id, product_name, price, quantity, status) VALUES (?, ?, ?, ?, 1, 'pending')";
                try (PreparedStatement stmt = conn.prepareStatement(orderSql)) {
                    stmt.setInt(1, session.userId);
                    stmt.setInt(2, productId);
                    stmt.setString(3, productName);
                    stmt.setString(4, price.toString());
                    stmt.executeUpdate();
                }

                conn.commit();
                sendResponse(exchange, 200, "{\"success\":true,\"message\":\"购买成功！\"}");
            } catch (SQLException | InterruptedException e) {
                if (conn != null) {
                    try { conn.rollback(); } catch (SQLException ignored) {}
                }
                sendResponse(exchange, 500, "{\"success\":false,\"message\":\"购买失败\"}");
            } finally {
                if (conn != null) {
                    try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
                    DBManager.releaseConnection(conn);
                }
            }
        } catch (NumberFormatException e) {
            sendResponse(exchange, 400, "{\"success\":false,\"message\":\"无效的商品ID\"}");
        }
    }

    private void handleAddProduct(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        String name = getParam(body, "name");
        String description = getParam(body, "description");
        String priceStr = getParam(body, "price");
        String imagePath = getParam(body, "imagePath");
        String stockStr = getParam(body, "stock");
        String categoryIdStr = getParam(body, "categoryId");

        if (name == null || name.isBlank() || priceStr == null || priceStr.isBlank()) {
            sendResponse(exchange, 400, "{\"success\":false,\"message\":\"商品名称和价格不能为空\"}");
            return;
        }

        BigDecimal price;
        int stock = 0;
        int categoryId = 0;
        try {
            price = new BigDecimal(priceStr);
            if (stockStr != null) stock = Integer.parseInt(stockStr);
            if (categoryIdStr != null) categoryId = Integer.parseInt(categoryIdStr);
        } catch (NumberFormatException e) {
            sendResponse(exchange, 400, "{\"success\":false,\"message\":\"价格或库存格式错误\"}");
            return;
        }

        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            String sql = "INSERT INTO products (name, description, price, image_path, stock, category_id) VALUES (?, ?, ?, ?, ?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                stmt.setString(1, name);
                stmt.setString(2, description);
                stmt.setString(3, price.toString());
                stmt.setString(4, imagePath != null ? imagePath : "");
                stmt.setInt(5, stock);
                stmt.setInt(6, categoryId);
                stmt.executeUpdate();
                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    if (rs.next()) {
                        sendResponse(exchange, 200,
                            "{\"success\":true,\"message\":\"添加成功\",\"productId\":" + rs.getInt(1) + "}");
                    }
                }
            }
        } catch (SQLException | InterruptedException e) {
            sendResponse(exchange, 500, "{\"success\":false,\"message\":\"添加失败\"}");
        } finally {
            DBManager.releaseConnection(conn);
        }
    }

    private void handleUpdateProduct(HttpExchange exchange, String path) throws IOException {
        int productId = Integer.parseInt(path.substring("/api/admin/products/".length()));
        String body = readBody(exchange);
        String name = getParam(body, "name");
        String description = getParam(body, "description");
        String priceStr = getParam(body, "price");
        String imagePath = getParam(body, "imagePath");
        String stockStr = getParam(body, "stock");

        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            StringBuilder sql = new StringBuilder("UPDATE products SET ");
            List<Object> params = new ArrayList<>();
            boolean first = true;

            if (name != null) { sql.append(first ? "" : ", ").append("name = ?"); params.add(name); first = false; }
            if (description != null) { sql.append(first ? "" : ", ").append("description = ?"); params.add(description); first = false; }
            if (priceStr != null) { sql.append(first ? "" : ", ").append("price = ?"); params.add(priceStr); first = false; }
            if (imagePath != null) { sql.append(first ? "" : ", ").append("image_path = ?"); params.add(imagePath); first = false; }
            if (stockStr != null) { sql.append(first ? "" : ", ").append("stock = ?"); params.add(Integer.parseInt(stockStr)); first = false; }
            String categoryIdStr = getParam(body, "categoryId");
            if (categoryIdStr != null) { sql.append(first ? "" : ", ").append("category_id = ?"); params.add(Integer.parseInt(categoryIdStr)); first = false; }

            if (first) {
                sendResponse(exchange, 400, "{\"success\":false,\"message\":\"没有要更新的字段\"}");
                return;
            }

            sql.append(" WHERE id = ?");
            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    stmt.setObject(i + 1, params.get(i));
                }
                stmt.setInt(params.size() + 1, productId);
                int rows = stmt.executeUpdate();
                if (rows > 0) {
                    sendResponse(exchange, 200, "{\"success\":true,\"message\":\"更新成功\"}");
                } else {
                    sendResponse(exchange, 404, "{\"success\":false,\"message\":\"商品不存在\"}");
                }
            }
        } catch (SQLException | InterruptedException e) {
            sendResponse(exchange, 500, "{\"success\":false,\"message\":\"更新失败\"}");
        } finally {
            DBManager.releaseConnection(conn);
        }
    }

    private void handleDeleteProduct(HttpExchange exchange, String path) throws IOException {
        int productId = Integer.parseInt(path.substring("/api/admin/products/".length()));
        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            String sql = "DELETE FROM products WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, productId);
                int rows = stmt.executeUpdate();
                if (rows > 0) {
                    sendResponse(exchange, 200, "{\"success\":true,\"message\":\"删除成功\"}");
                } else {
                    sendResponse(exchange, 404, "{\"success\":false,\"message\":\"商品不存在\"}");
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
