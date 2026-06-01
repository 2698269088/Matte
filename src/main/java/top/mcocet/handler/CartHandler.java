package top.mcocet.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import top.mcocet.db.DBManager;

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

public class CartHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");

        if ("GET".equalsIgnoreCase(method) && path.matches("/api/cart/\\d+")) {
            handleGetCart(exchange, path);
        } else if ("POST".equalsIgnoreCase(method) && "/api/cart/add".equals(path)) {
            handleAddToCart(exchange);
        } else if ("PUT".equalsIgnoreCase(method) && path.matches("/api/cart/\\d+")) {
            handleUpdateCartItem(exchange, path);
        } else if ("DELETE".equalsIgnoreCase(method) && path.matches("/api/cart/\\d+")) {
            handleDeleteCartItem(exchange, path);
        } else if ("POST".equalsIgnoreCase(method) && "/api/cart/checkout".equals(path)) {
            handleCheckout(exchange);
        } else if ("OPTIONS".equalsIgnoreCase(method)) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            exchange.sendResponseHeaders(204, -1);
        } else {
            sendResponse(exchange, 404, "{\"success\":false,\"message\":\"接口不存在\"}");
        }
    }

    private void handleGetCart(HttpExchange exchange, String path) throws IOException {
        AuthHandler.SessionInfo session = AuthHandler.getCurrentSession(exchange);
        if (session == null) {
            sendResponse(exchange, 401, "{\"success\":false,\"message\":\"未登录，请先登录\"}");
            return;
        }
        try {
            int userId = Integer.parseInt(path.substring("/api/cart/".length()));
            if (userId != session.userId) {
                sendResponse(exchange, 403, "{\"success\":false,\"message\":\"无权查看他人购物车\"}");
                return;
            }
            Connection conn = null;
            try {
                conn = DBManager.getConnection();
                String sql = "SELECT c.*, p.stock FROM carts c JOIN products p ON c.product_id = p.id WHERE c.user_id = ? ORDER BY c.created_at DESC";
                List<String> items = new ArrayList<>();
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setInt(1, userId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            BigDecimal price = new BigDecimal(rs.getString("price"));
                            BigDecimal customPrice = rs.getObject("custom_price") != null
                                ? new BigDecimal(rs.getString("custom_price")) : null;
                            int quantity = rs.getInt("quantity");
                            BigDecimal finalPrice = customPrice != null ? customPrice : price;
                            BigDecimal subtotal = finalPrice.multiply(new BigDecimal(quantity));
                            items.add(String.format(
                                "{\"id\":%d,\"productId\":%d,\"productName\":\"%s\",\"price\":%.2f,\"customPrice\":%s,\"quantity\":%d,\"imagePath\":\"%s\",\"stock\":%d,\"subtotal\":%.2f}",
                                rs.getInt("id"),
                                rs.getInt("product_id"),
                                escapeJson(rs.getString("product_name")),
                                price,
                                customPrice != null ? String.format("%.2f", customPrice) : "null",
                                quantity,
                                escapeJson(rs.getString("image_path") != null ? rs.getString("image_path") : ""),
                                rs.getInt("stock"),
                                subtotal
                            ));
                        }
                    }
                }
                String json = "{\"success\":true,\"data\":[" + String.join(",", items) + "]}";
                sendResponse(exchange, 200, json);
            } catch (SQLException | InterruptedException e) {
                sendResponse(exchange, 500, "{\"success\":false,\"message\":\"查询失败\"}");
            } finally {
                DBManager.releaseConnection(conn);
            }
        } catch (NumberFormatException e) {
            sendResponse(exchange, 400, "{\"success\":false,\"message\":\"无效的用户ID\"}");
        }
    }

    private void handleAddToCart(HttpExchange exchange) throws IOException {
        AuthHandler.SessionInfo session = AuthHandler.getCurrentSession(exchange);
        if (session == null) {
            sendResponse(exchange, 401, "{\"success\":false,\"message\":\"未登录，请先登录\"}");
            return;
        }
        String body = readBody(exchange);
        String userIdStr = getParam(body, "userId");
        String productIdStr = getParam(body, "productId");
        String quantityStr = getParam(body, "quantity");

        if (userIdStr == null || productIdStr == null) {
            sendResponse(exchange, 400, "{\"success\":false,\"message\":\"用户ID和商品ID不能为空\"}");
            return;
        }

        int userId, productId, quantity = 1;
        try {
            userId = Integer.parseInt(userIdStr);
            productId = Integer.parseInt(productIdStr);
            if (quantityStr != null) quantity = Integer.parseInt(quantityStr);
            if (quantity < 1) quantity = 1;
        } catch (NumberFormatException e) {
            sendResponse(exchange, 400, "{\"success\":false,\"message\":\"参数格式错误\"}");
            return;
        }

        if (userId != session.userId) {
            sendResponse(exchange, 403, "{\"success\":false,\"message\":\"无权操作他人购物车\"}");
            return;
        }

        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            conn.setAutoCommit(false);

            // 查询商品信息
            String productSql = "SELECT name, price, stock, image_path FROM products WHERE id = ?";
            String productName = "";
            BigDecimal price = BigDecimal.ZERO;
            String imagePath = "";
            int stock = 0;
            try (PreparedStatement stmt = conn.prepareStatement(productSql)) {
                stmt.setInt(1, productId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        sendResponse(exchange, 404, "{\"success\":false,\"message\":\"商品不存在\"}");
                        return;
                    }
                    productName = rs.getString("name");
                    price = new BigDecimal(rs.getString("price"));
                    stock = rs.getInt("stock");
                    imagePath = rs.getString("image_path") != null ? rs.getString("image_path") : "";
                }
            }

            if (stock < quantity) {
                sendResponse(exchange, 400, "{\"success\":false,\"message\":\"库存不足，当前库存\"" + stock + "}");
                return;
            }

            // 检查购物车是否已有该商品
            String checkSql = "SELECT id, quantity FROM carts WHERE user_id = ? AND product_id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(checkSql)) {
                stmt.setInt(1, userId);
                stmt.setInt(2, productId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        int cartId = rs.getInt("id");
                        int newQty = rs.getInt("quantity") + quantity;
                        if (newQty > stock) {
                            sendResponse(exchange, 400, "{\"success\":false,\"message\":\"购物车数量超过库存\"}");
                            return;
                        }
                        String updateSql = "UPDATE carts SET quantity = ? WHERE id = ?";
                        try (PreparedStatement upd = conn.prepareStatement(updateSql)) {
                            upd.setInt(1, newQty);
                            upd.setInt(2, cartId);
                            upd.executeUpdate();
                        }
                        conn.commit();
                        sendResponse(exchange, 200, "{\"success\":true,\"message\":\"已更新购物车数量\"}");
                        return;
                    }
                }
            }

            // 新增购物车记录
            String insertSql = "INSERT INTO carts (user_id, product_id, product_name, price, quantity, image_path) VALUES (?, ?, ?, ?, ?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                stmt.setInt(1, userId);
                stmt.setInt(2, productId);
                stmt.setString(3, productName);
                stmt.setString(4, price.toString());
                stmt.setInt(5, quantity);
                stmt.setString(6, imagePath);
                stmt.executeUpdate();
                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    if (rs.next()) {
                        conn.commit();
                        sendResponse(exchange, 200, "{\"success\":true,\"message\":\"加入购物车成功\"}");
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

    private void handleUpdateCartItem(HttpExchange exchange, String path) throws IOException {
        AuthHandler.SessionInfo session = AuthHandler.getCurrentSession(exchange);
        if (session == null) {
            sendResponse(exchange, 401, "{\"success\":false,\"message\":\"未登录，请先登录\"}");
            return;
        }
        int cartId = Integer.parseInt(path.substring("/api/cart/".length()));
        String body = readBody(exchange);
        String quantityStr = getParam(body, "quantity");
        String customPriceStr = getParam(body, "customPrice");

        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            // 先校验该购物车项是否属于当前用户
            String checkSql = "SELECT user_id FROM carts WHERE id = ?";
            try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                checkStmt.setInt(1, cartId);
                try (ResultSet rs = checkStmt.executeQuery()) {
                    if (!rs.next()) {
                        sendResponse(exchange, 404, "{\"success\":false,\"message\":\"购物车项不存在\"}");
                        return;
                    }
                    if (rs.getInt("user_id") != session.userId) {
                        sendResponse(exchange, 403, "{\"success\":false,\"message\":\"无权操作他人购物车\"}");
                        return;
                    }
                }
            }

            StringBuilder sql = new StringBuilder("UPDATE carts SET ");
            List<Object> params = new ArrayList<>();
            boolean first = true;

            if (quantityStr != null) {
                sql.append(first ? "" : ", ").append("quantity = ?");
                params.add(Integer.parseInt(quantityStr));
                first = false;
            }
            if (customPriceStr != null) {
                sql.append(first ? "" : ", ").append("custom_price = ?");
                params.add(new BigDecimal(customPriceStr));
                first = false;
            }

            if (first) {
                sendResponse(exchange, 400, "{\"success\":false,\"message\":\"没有要更新的字段\"}");
                return;
            }

            sql.append(" WHERE id = ?");
            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    stmt.setObject(i + 1, params.get(i));
                }
                stmt.setInt(params.size() + 1, cartId);
                int rows = stmt.executeUpdate();
                if (rows > 0) {
                    sendResponse(exchange, 200, "{\"success\":true,\"message\":\"更新成功\"}");
                } else {
                    sendResponse(exchange, 404, "{\"success\":false,\"message\":\"购物车项不存在\"}");
                }
            }
        } catch (SQLException | InterruptedException e) {
            sendResponse(exchange, 500, "{\"success\":false,\"message\":\"更新失败\"}");
        } finally {
            DBManager.releaseConnection(conn);
        }
    }

    private void handleDeleteCartItem(HttpExchange exchange, String path) throws IOException {
        AuthHandler.SessionInfo session = AuthHandler.getCurrentSession(exchange);
        if (session == null) {
            sendResponse(exchange, 401, "{\"success\":false,\"message\":\"未登录，请先登录\"}");
            return;
        }
        int cartId = Integer.parseInt(path.substring("/api/cart/".length()));
        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            // 先校验该购物车项是否属于当前用户
            String checkSql = "SELECT user_id FROM carts WHERE id = ?";
            try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                checkStmt.setInt(1, cartId);
                try (ResultSet rs = checkStmt.executeQuery()) {
                    if (!rs.next()) {
                        sendResponse(exchange, 404, "{\"success\":false,\"message\":\"购物车项不存在\"}");
                        return;
                    }
                    if (rs.getInt("user_id") != session.userId) {
                        sendResponse(exchange, 403, "{\"success\":false,\"message\":\"无权操作他人购物车\"}");
                        return;
                    }
                }
            }

            String sql = "DELETE FROM carts WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, cartId);
                int rows = stmt.executeUpdate();
                if (rows > 0) {
                    sendResponse(exchange, 200, "{\"success\":true,\"message\":\"删除成功\"}");
                } else {
                    sendResponse(exchange, 404, "{\"success\":false,\"message\":\"购物车项不存在\"}");
                }
            }
        } catch (SQLException | InterruptedException e) {
            sendResponse(exchange, 500, "{\"success\":false,\"message\":\"删除失败\"}");
        } finally {
            DBManager.releaseConnection(conn);
        }
    }

    private void handleCheckout(HttpExchange exchange) throws IOException {
        AuthHandler.SessionInfo session = AuthHandler.getCurrentSession(exchange);
        if (session == null) {
            sendResponse(exchange, 401, "{\"success\":false,\"message\":\"未登录，请先登录\"}");
            return;
        }
        String body = readBody(exchange);
        String userIdStr = getParam(body, "userId");
        String cartIdsStr = getParam(body, "cartIds");
        String merchantIdStr = getParam(body, "merchantId");
        String addressIdStr = getParam(body, "addressId");

        if (userIdStr == null || cartIdsStr == null || cartIdsStr.isBlank()) {
            sendResponse(exchange, 400, "{\"success\":false,\"message\":\"参数不完整\"}");
            return;
        }

        int userId;
        Integer merchantId = null;
        Integer addressId = null;
        String[] cartIdArr = cartIdsStr.split(",");
        try {
            userId = Integer.parseInt(userIdStr);
            if (merchantIdStr != null && !merchantIdStr.isBlank()) {
                merchantId = Integer.parseInt(merchantIdStr);
            }
            if (addressIdStr != null && !addressIdStr.isBlank()) {
                addressId = Integer.parseInt(addressIdStr);
            }
        } catch (NumberFormatException e) {
            sendResponse(exchange, 400, "{\"success\":false,\"message\":\"参数格式错误\"}");
            return;
        }

        if (userId != session.userId) {
            sendResponse(exchange, 403, "{\"success\":false,\"message\":\"无权使用他人账户结算\"}");
            return;
        }

        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            conn.setAutoCommit(false);

            int totalItems = 0;
            BigDecimal totalAmount = BigDecimal.ZERO;

            for (String cid : cartIdArr) {
                int cartId = Integer.parseInt(cid.trim());
                String cartSql = "SELECT c.*, p.stock FROM carts c JOIN products p ON c.product_id = p.id WHERE c.id = ? AND c.user_id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(cartSql)) {
                    stmt.setInt(1, cartId);
                    stmt.setInt(2, userId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (!rs.next()) continue;
                        int productId = rs.getInt("product_id");
                        String productName = rs.getString("product_name");
                        BigDecimal price = new BigDecimal(rs.getString("price"));
                        BigDecimal customPrice = rs.getObject("custom_price") != null
                            ? new BigDecimal(rs.getString("custom_price")) : null;
                        int quantity = rs.getInt("quantity");
                        int stock = rs.getInt("stock");

                        if (quantity > stock) {
                            conn.rollback();
                            sendResponse(exchange, 400, "{\"success\":false,\"message\":\"商品\\\"" + escapeJson(productName) + "\\\"库存不足\"}");
                            return;
                        }

                        BigDecimal finalPrice = customPrice != null ? customPrice : price;
                        BigDecimal subtotal = finalPrice.multiply(new BigDecimal(quantity));
                        totalAmount = totalAmount.add(subtotal);
                        totalItems += quantity;

                        // 扣减库存
                        String updateStock = "UPDATE products SET stock = stock - ? WHERE id = ?";
                        try (PreparedStatement upd = conn.prepareStatement(updateStock)) {
                            upd.setInt(1, quantity);
                            upd.setInt(2, productId);
                            upd.executeUpdate();
                        }

                        // 创建订单
                        String orderSql = "INSERT INTO orders (user_id, product_id, product_name, price, quantity, merchant_id, address_id, status) VALUES (?, ?, ?, ?, ?, ?, ?, 'pending')";
                        try (PreparedStatement ord = conn.prepareStatement(orderSql)) {
                            ord.setInt(1, userId);
                            ord.setInt(2, productId);
                            ord.setString(3, productName);
                            ord.setString(4, finalPrice.toString());
                            ord.setInt(5, quantity);
                            if (merchantId != null) {
                                ord.setInt(6, merchantId);
                            } else {
                                ord.setNull(6, Types.INTEGER);
                            }
                            if (addressId != null) {
                                ord.setInt(7, addressId);
                            } else {
                                ord.setNull(7, Types.INTEGER);
                            }
                            ord.executeUpdate();
                        }
                    }
                }

                // 删除购物车记录
                String delSql = "DELETE FROM carts WHERE id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(delSql)) {
                    stmt.setInt(1, cartId);
                    stmt.executeUpdate();
                }
            }

            conn.commit();
            sendResponse(exchange, 200, String.format(
                "{\"success\":true,\"message\":\"结算成功，共%d件商品，合计¥%.2f\"}", totalItems, totalAmount));
        } catch (SQLException | InterruptedException | NumberFormatException e) {
            if (conn != null) {
                try { conn.rollback(); } catch (SQLException ignored) {}
            }
            sendResponse(exchange, 500, "{\"success\":false,\"message\":\"结算失败\"}");
        } finally {
            if (conn != null) {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
                DBManager.releaseConnection(conn);
            }
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
