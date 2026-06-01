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
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class SettingsHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");

        if ("GET".equalsIgnoreCase(method) && "/api/settings".equals(path)) {
            handleGetSettings(exchange);
        } else if ("POST".equalsIgnoreCase(method) && "/api/settings".equals(path)) {
            if (!AuthHandler.requireAdmin(exchange)) return;
            handleUpdateSettings(exchange);
        } else if ("GET".equalsIgnoreCase(method) && "/api/payment-qr".equals(path)) {
            handleGetPaymentQr(exchange);
        } else if ("POST".equalsIgnoreCase(method) && "/api/payment-qr".equals(path)) {
            if (!AuthHandler.requireAdmin(exchange)) return;
            handleUpdatePaymentQr(exchange);
        } else if ("OPTIONS".equalsIgnoreCase(method)) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
            exchange.sendResponseHeaders(204, -1);
        } else {
            sendResponse(exchange, 404, "{\"success\":false,\"message\":\"接口不存在\"}");
        }
    }

    private void handleGetSettings(HttpExchange exchange) throws IOException {
        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            String sql = "SELECT key, value FROM settings";
            Map<String, String> settings = new HashMap<>();
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    settings.put(rs.getString("key"), rs.getString("value"));
                }
            }

            // 默认值
            String bgImage = settings.getOrDefault("bg_image", "");
            String slogan = settings.getOrDefault("slogan", "精选好物，品质生活");
            String pageTitle = settings.getOrDefault("page_title", "Matte 商城");

            // 如果背景图存在，固定返回 /background.jpg（实际文件可能是 png/gif）
            String bgUrl = "";
            if (!bgImage.isEmpty()) {
                java.nio.file.Path bgPath = java.nio.file.Paths.get("web/background.jpg");
                if (!java.nio.file.Files.exists(bgPath)) {
                    bgPath = java.nio.file.Paths.get("web/background.png");
                }
                if (!java.nio.file.Files.exists(bgPath)) {
                    bgPath = java.nio.file.Paths.get("web/background.gif");
                }
                if (java.nio.file.Files.exists(bgPath)) {
                    String ext = bgPath.getFileName().toString().substring(bgPath.getFileName().toString().lastIndexOf('.'));
                    bgUrl = "/background" + ext;
                }
            }

            String json = String.format(
                "{\"success\":true,\"data\":{\"bgImage\":\"%s\",\"slogan\":\"%s\",\"pageTitle\":\"%s\"}}",
                escapeJson(bgUrl), escapeJson(slogan), escapeJson(pageTitle)
            );
            sendResponse(exchange, 200, json);
        } catch (SQLException | InterruptedException e) {
            sendResponse(exchange, 500, "{\"success\":false,\"message\":\"查询失败\"}");
        } finally {
            DBManager.releaseConnection(conn);
        }
    }

    private void handleUpdateSettings(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        String bgImage = getParam(body, "bgImage");
        String slogan = getParam(body, "slogan");
        String pageTitle = getParam(body, "pageTitle");

        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            String upsert = "INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = CURRENT_TIMESTAMP";

            try (PreparedStatement stmt = conn.prepareStatement(upsert)) {
                if (bgImage != null) {
                    stmt.setString(1, "bg_image");
                    stmt.setString(2, bgImage);
                    stmt.executeUpdate();
                }
                if (slogan != null) {
                    stmt.setString(1, "slogan");
                    stmt.setString(2, slogan);
                    stmt.executeUpdate();
                }
                if (pageTitle != null) {
                    stmt.setString(1, "page_title");
                    stmt.setString(2, pageTitle);
                    stmt.executeUpdate();
                }
            }
            sendResponse(exchange, 200, "{\"success\":true,\"message\":\"保存成功\"}");
        } catch (SQLException | InterruptedException e) {
            sendResponse(exchange, 500, "{\"success\":false,\"message\":\"保存失败\"}");
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

    private void handleGetPaymentQr(HttpExchange exchange) throws IOException {
        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            String sql = "SELECT value FROM settings WHERE key = 'payment_qr'";
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
                String qrPath = "";
                if (rs.next()) {
                    qrPath = rs.getString("value");
                }
                // 如果付款二维码存在，固定返回 /payment.jpg（实际文件可能是 png）
                String qrUrl = "";
                if (qrPath != null && !qrPath.isEmpty()) {
                    java.nio.file.Path qrFile = java.nio.file.Paths.get("web/payment.jpg");
                    if (!java.nio.file.Files.exists(qrFile)) {
                        qrFile = java.nio.file.Paths.get("web/payment.png");
                    }
                    if (java.nio.file.Files.exists(qrFile)) {
                        String ext = qrFile.getFileName().toString().substring(qrFile.getFileName().toString().lastIndexOf('.'));
                        qrUrl = "/payment" + ext;
                    }
                }
                sendResponse(exchange, 200, "{\"success\":true,\"data\":{\"qrPath\":\"" + escapeJson(qrUrl) + "\"}}");
            }
        } catch (SQLException | InterruptedException e) {
            sendResponse(exchange, 500, "{\"success\":false,\"message\":\"查询失败\"}");
        } finally {
            DBManager.releaseConnection(conn);
        }
    }

    private void handleUpdatePaymentQr(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        String qrPath = getParam(body, "qrPath");

        Connection conn = null;
        try {
            conn = DBManager.getConnection();
            String upsert = "INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = CURRENT_TIMESTAMP";
            try (PreparedStatement stmt = conn.prepareStatement(upsert)) {
                stmt.setString(1, "payment_qr");
                stmt.setString(2, qrPath != null ? qrPath : "");
                stmt.executeUpdate();
            }
            sendResponse(exchange, 200, "{\"success\":true,\"message\":\"保存成功\"}");
        } catch (SQLException | InterruptedException e) {
            sendResponse(exchange, 500, "{\"success\":false,\"message\":\"保存失败\"}");
        } finally {
            DBManager.releaseConnection(conn);
        }
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
