package org.tinycc.cli;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** CLI proxy that delegates to a system-installed {@code tcc} executable. */
public final class SystemMain {
    private SystemMain() {
    }

    public static void main(String[] args) throws Exception {
        int result;
        try {
            result = Main.run(args, SystemMain::executeSystemTcc);
        } catch (IOException exception) {
            System.err.println("could not start system TinyCC executable 'tcc': " + exception.getMessage());
            result = 127;
        }
        System.exit(result);
    }

    private static int executeSystemTcc(String[] arguments) throws Exception {
        List<String> command = new ArrayList<>(arguments.length + 1);
        command.add("tcc");
        Collections.addAll(command, arguments);
        return new ProcessBuilder(command).inheritIO().start().waitFor();
    }
}
