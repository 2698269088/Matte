package top.mcocet.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class StaticResourceHandler implements HttpHandler {
    
    // 静态初始化块：启动时自动复制 web 资源到外部目录
    static {
        try {
            initWebFolder();
        } catch (IOException e) {
            System.err.println("初始化 web 文件夹失败: " + e.getMessage());
        }
    }
    
    private static void initWebFolder() throws IOException {
        Path webDir = Path.of("web");
        if (!Files.exists(webDir)) {
            Files.createDirectories(webDir);
        }
        
        // 复制 index.html
        copyResourceToWeb("/web/index.html", "web/index.html");
        // 复制 admin.html
        copyResourceToWeb("/web/admin.html", "web/admin.html");
    }
    
    private static void copyResourceToWeb(String resourcePath, String targetPath) throws IOException {
        URL resourceUrl = StaticResourceHandler.class.getResource(resourcePath);
        if (resourceUrl != null) {
            Path target = Path.of(targetPath);
            if (!Files.exists(target)) {
                try (InputStream is = resourceUrl.openStream()) {
                    Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("已复制文件: " + targetPath);
                }
            }
        }
    }
    
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        
        if ("/".equals(path)) {
            path = "/index.html";
        }

        // 优先从外部 web 文件夹读取文件，方便管理员修改
        java.nio.file.Path externalPath = java.nio.file.Paths.get("web" + path);
        if (java.nio.file.Files.exists(externalPath)) {
            handleExternalFile(exchange, path, externalPath);
            return;
        }

        // 如果外部文件不存在，从 jar 包内部读取
        String resourcePath = "/web" + path;
        URL resourceUrl = getClass().getResource(resourcePath);

        if (resourceUrl == null) {
            String notFound = "<html><body><h1>404 Not Found</h1></body></html>";
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(404, notFound.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(notFound.getBytes());
            }
            return;
        }

        String contentType = getContentType(path);
        exchange.getResponseHeaders().set("Content-Type", contentType);

        try (InputStream is = resourceUrl.openStream()) {
            byte[] data = is.readAllBytes();
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(data);
            }
        }
    }

    private void handleExternalFile(HttpExchange exchange, String path, java.nio.file.Path filePath) throws IOException {
        String contentType = getContentType(path);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        byte[] data = java.nio.file.Files.readAllBytes(filePath);
        exchange.sendResponseHeaders(200, data.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(data);
        }
    }



    private String getContentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=UTF-8";
        if (path.endsWith(".css")) return "text/css; charset=UTF-8";
        if (path.endsWith(".js")) return "application/javascript; charset=UTF-8";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".gif")) return "image/gif";
        if (path.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }
}