package top.mcocet.console;

import java.util.List;

@FunctionalInterface
public interface ConsoleCommand {
    void execute(List<String> args);
}
