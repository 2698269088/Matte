package top.mcocet;

import com.sun.net.httpserver.HttpServer;
import top.mcocet.console.ConsoleShell;
import top.mcocet.handler.AddressHandler;
import top.mcocet.handler.AdminHandler;
import top.mcocet.handler.CartHandler;
import top.mcocet.handler.CategoryHandler;
import top.mcocet.handler.ProductHandler;
import top.mcocet.handler.SettingsHandler;
import top.mcocet.handler.StaticResourceHandler;
import top.mcocet.handler.UploadHandler;
import top.mcocet.handler.UserHandler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public class Main {
    private static final int PORT = 8080;

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        server.createContext("/", new StaticResourceHandler());
        server.createContext("/api/products", new ProductHandler());
        server.createContext("/api/buy", new ProductHandler());
        server.createContext("/api/register", new UserHandler());
        server.createContext("/api/login", new UserHandler());
        server.createContext("/api/logout", new UserHandler());
        server.createContext("/api/user", new UserHandler());
        server.createContext("/api/addresses", new AddressHandler());
        server.createContext("/api/address", new AddressHandler());
        server.createContext("/api/admin/products", new ProductHandler());
        server.createContext("/api/admin/orders", new AdminHandler());
        server.createContext("/api/admin/users", new AdminHandler());
        server.createContext("/api/admin/categories", new CategoryHandler());
        server.createContext("/api/categories", new CategoryHandler());
        server.createContext("/api/cart", new CartHandler());
        server.createContext("/api/upload", new UploadHandler());
        server.createContext("/api/settings", new SettingsHandler());
        server.createContext("/api/merchants", new AdminHandler());
        server.createContext("/api/payment-qr", new SettingsHandler());
        server.createContext("/images", new StaticResourceHandler());
        server.createContext("/background", new StaticResourceHandler());
        server.createContext("/payment", new StaticResourceHandler());

        server.setExecutor(Executors.newFixedThreadPool(10));
        server.start();

        System.out.println("============================================");
        System.out.println("  Matte 商城服务已启动！");
        System.out.println("  访问地址: http://localhost:" + PORT);
        System.out.println("============================================");

        // 启动控制台交互线程
        Thread consoleThread = new Thread(new ConsoleShell());
        consoleThread.setName("console-thread");
        consoleThread.setDaemon(true);
        consoleThread.start();
    }
}