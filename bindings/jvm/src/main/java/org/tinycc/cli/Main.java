package org.tinycc.cli;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.tinycc.TinyCC;

/** Command-line proxy for the TinyCC embed JAR. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || (!args[0].equals("exe") && !args[0].equals("dll"))) {
            System.err.println("usage: java -jar tinycc-cli.jar <exe|dll> <source.c> <output>");
            System.exit(2);
        }
        Path sourcePath = Path.of(args[1]).toAbsolutePath();
        String source = "#line 1 \"" + sourcePath.toString().replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\"\n"
                + Files.readString(sourcePath, StandardCharsets.UTF_8);
        int result = TinyCC.compile(
                source,
                args[0].equals("exe") ? TinyCC.OutputType.EXECUTABLE : TinyCC.OutputType.DYNAMIC_LIBRARY,
                Path.of(args[2]),
                System.err::println);
        System.exit(result == 0 ? 0 : 1);
    }
}
