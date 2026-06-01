package top.mcocet.console;

import java.util.*;

public class CommandRegistry {
    private final Map<String, ConsoleCommand> commands = new LinkedHashMap<>();
    private final Map<String, String> descriptions = new LinkedHashMap<>();

    public void register(String name, String description, ConsoleCommand command) {
        commands.put(name, command);
        descriptions.put(name, description);
    }

    public boolean hasCommand(String name) {
        return commands.containsKey(name);
    }

    public void execute(String name, List<String> args) {
        ConsoleCommand cmd = commands.get(name);
        if (cmd != null) {
            cmd.execute(args);
        }
    }

    public void printHelp() {
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║           Matte 控制台命令列表            ║");
        System.out.println("╠══════════════════════════════════════════╣");
        for (Map.Entry<String, String> entry : descriptions.entrySet()) {
            System.out.printf("║ %-12s - %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println("╚══════════════════════════════════════════╝");
    }

    public Collection<String> getCommandNames() {
        return commands.keySet();
    }
}
