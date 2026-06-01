package top.mcocet.console;

import org.jline.reader.*;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class ConsoleRunner implements Runnable {
    private final CommandRegistry registry = new CommandRegistry();

    public ConsoleRunner() {
        initCommands();
    }

    private void initCommands() {
        registry.register("help", "显示所有可用命令", args -> registry.printHelp());
        registry.register("list-users", "查看所有已注册用户", args -> UserCommandHandler.listUsers());
        registry.register("register", "手动注册用户 (用法: register <用户名> <密码> [手机号] [邮箱])", args -> {
            if (args.size() < 2) {
                System.out.println("用法: register <用户名> <密码> [手机号] [邮箱]");
                return;
            }
            String username = args.get(0);
            String password = args.get(1);
            String phone = args.size() > 2 ? args.get(2) : null;
            String email = args.size() > 3 ? args.get(3) : null;
            UserCommandHandler.registerUser(username, password, phone, email);
        });
        registry.register("delete-user", "注销用户 (用法: delete-user <用户名或ID>)", args -> {
            if (args.isEmpty()) {
                System.out.println("用法: delete-user <用户名或ID>");
                return;
            }
            UserCommandHandler.deleteUser(args.get(0));
        });
        registry.register("exit", "关闭服务器并退出", args -> {
            System.out.println("正在关闭服务器...");
            System.exit(0);
        });
    }

    @Override
    public void run() {
        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer(new StringsCompleter(registry.getCommandNames()))
                    .build();

            System.out.println();
            registry.printHelp();
            System.out.println();

            while (true) {
                try {
                    String line = reader.readLine("matte> ");
                    if (line == null || line.trim().isEmpty()) continue;

                    List<String> parts = Arrays.asList(line.trim().split("\\s+"));
                    String cmd = parts.get(0);
                    List<String> args = parts.subList(1, parts.size());

                    if (registry.hasCommand(cmd)) {
                        registry.execute(cmd, args);
                    } else {
                        System.out.println("未知命令: " + cmd + "，输入 help 查看可用命令");
                    }
                } catch (UserInterruptException e) {
                    System.out.println("^C");
                } catch (EndOfFileException e) {
                    System.out.println("退出控制台...");
                    break;
                }
            }
        } catch (IOException e) {
            System.err.println("控制台初始化失败: " + e.getMessage());
        }
    }
}
