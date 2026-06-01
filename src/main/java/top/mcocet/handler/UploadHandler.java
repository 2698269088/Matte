package top.mcocet.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;


public class UploadHandler implements HttpHandler {
    private static final String WEB_DIR = "web";
    private static final String IMAGES_DIR = WEB_DIR + "/images";
    // 每个分区的商品图片计数器: categoryId -> counter
    private static final java.util.Map<Integer, Integer> categoryCounters = new java.util.HashMap<>();

    static {
        // 启动时扫描已有图片，按分区确定计数器起始值
        try {
            Path imagesPath = Paths.get(IMAGES_DIR);
            if (Files.exists(imagesPath)) {
                Files.list(imagesPath).forEach(p -> {
                    String fileName = p.getFileName().toString();
                    // 匹配格式: 分区号-序号.扩展名 如 1-7.png
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+)-\\d+\\.(jpg|png|gif)$").matcher(fileName);
                    if (m.matches()) {
                        int catId = Integer.parseInt(m.group(1));
                        int num = Integer.parseInt(fileName.substring(fileName.indexOf('-') + 1, fileName.lastIndexOf('.')));
                        categoryCounters.merge(catId, num, Math::max);
                    }
                });
            }
        } catch (IOException e) {
            // 忽略
        }
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");

        if ("POST".equalsIgnoreCase(method) && "/api/upload".equals(path)) {
            if (!AuthHandler.requireAdmin(exchange)) return;
            handleUpload(exchange, 0);
        } else if ("POST".equalsIgnoreCase(method) && "/api/upload/bg".equals(path)) {
            if (!AuthHandler.requireAdmin(exchange)) return;
            handleUpload(exchange, 1);
        } else if ("POST".equalsIgnoreCase(method) && "/api/upload/payment".equals(path)) {
            if (!AuthHandler.requireAdmin(exchange)) return;
            handleUpload(exchange, 2);
        } else if ("OPTIONS".equalsIgnoreCase(method)) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
            exchange.sendResponseHeaders(204, -1);
        } else {
            sendResponse(exchange, 404, "{\"success\":false,\"message\":\"接口不存在\"}");
        }
    }

    private void handleUpload(HttpExchange exchange, int uploadType) throws IOException {
        boolean isBackground = uploadType == 1;
        boolean isPayment = uploadType == 2;
        String body = readBody(exchange);
        
        // 解析 base64 数据
        String base64Data = body;
        String mimeType = "image/png";
        
        if (body.contains(",")) {
            String[] parts = body.split(",", 2);
            String header = parts[0];
            base64Data = parts[1];
            if (header.contains("data:image/")) {
                mimeType = header.substring(header.indexOf("data:image/") + 11, header.indexOf(";"));
            }
        }

        try {
            byte[] imageBytes = Base64.getDecoder().decode(base64Data);
            
            // 确保目录存在
            Path webPath = Paths.get(WEB_DIR);
            if (!Files.exists(webPath)) {
                Files.createDirectories(webPath);
            }
            
            String extension = mimeType.equals("jpeg") ? "jpg" : mimeType;
            String fileName;
            String imageUrl;
            
            if (isBackground) {
                // 背景图固定名称
                fileName = "background." + extension;
                Path filePath = webPath.resolve(fileName);
                Files.write(filePath, imageBytes);
                imageUrl = "/" + fileName;
            } else if (isPayment) {
                // 付款二维码固定名称
                fileName = "payment." + extension;
                Path filePath = webPath.resolve(fileName);
                Files.write(filePath, imageBytes);
                imageUrl = "/" + fileName;
            } else {
                // 商品图按分区-序号命名，如 1-7.png
                Path imagesPath = Paths.get(IMAGES_DIR);
                if (!Files.exists(imagesPath)) {
                    Files.createDirectories(imagesPath);
                }
                // 从请求头或参数中获取分区ID，默认1
                int categoryId = 1;
                String catHeader = exchange.getRequestHeaders().getFirst("X-Category-Id");
                if (catHeader != null) {
                    try { categoryId = Integer.parseInt(catHeader); } catch (NumberFormatException ignored) {}
                }
                int currentCount = categoryCounters.getOrDefault(categoryId, 0);
                currentCount++;
                categoryCounters.put(categoryId, currentCount);
                fileName = categoryId + "-" + currentCount + "." + extension;
                Path filePath = imagesPath.resolve(fileName);
                Files.write(filePath, imageBytes);
                imageUrl = "/images/" + fileName;
            }
            
            sendResponse(exchange, 200, 
                "{\"success\":true,\"message\":\"上传成功\",\"url\":\"" + imageUrl + "\"}");
        } catch (IllegalArgumentException e) {
            sendResponse(exchange, 400, "{\"success\":false,\"message\":\"无效的图片数据\"}");
        } catch (IOException e) {
            sendResponse(exchange, 500, "{\"success\":false,\"message\":\"上传失败\"}");
        }
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
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
