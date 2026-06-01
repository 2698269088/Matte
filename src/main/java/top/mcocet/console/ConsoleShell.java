package top.mcocet.console;

import org.jline.reader.*;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.reader.impl.completer.StringsCompleter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class ConsoleShell implements Runnable {
    private final CommandRegistry registry = new CommandRegistry();

    public ConsoleShell() {
        UserCommands.registerAll(registry);
    }

    @Override
    public void run() {
        try {
            Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .build();

            LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(new StringsCompleter(registry.getCommandNames()))
                .build();

            System.out.println("\n[控制台已启动，输入 help 查看命令列表，输入 exit 退出]\n");

            while (true) {
                try {
                    String line = reader.readLine("matte> ");
                    if (line == null || line.trim().isEmpty()) continue;

                    String[] parts = line.trim().split("\\s+");
                    String cmd = parts[0];
                    List<String> args = Arrays.asList(parts).subList(1, parts.length);

                    if ("exit".equals(cmd) || "quit".equals(cmd)) {
                        System.out.println("控制台已退出");
                        break;
                    }

                    if (registry.hasCommand(cmd)) {
                        registry.execute(cmd, args);
                    } else {
                        System.out.println("未知命令: " + cmd + "，输入 help 查看可用命令");
                    }
                } catch (UserInterruptException e) {
                    System.out.println("控制台已退出");
                    break;
                } catch (EndOfFileException e) {
                    break;
                }
            }
        } catch (IOException e) {
            System.err.println("控制台初始化失败: " + e.getMessage());
        }
    }
}
