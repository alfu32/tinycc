package org.tinycc.cli;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.tinycc.TinyCC;

/** Command-line proxy for the complete bundled TinyCC driver. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--launcher")) {
            createLauncher(args);
            return;
        }

        // Compatibility shorthands. All other invocations are passed unchanged
        // to native tcc, including multiple inputs, -run, -E, and -ar.
        List<String> tccArguments = new ArrayList<>();
        if (args.length > 0 && args[0].equals("exe")) {
            for (int index = 1; index < args.length; index++) {
                tccArguments.add(args[index]);
            }
        } else if (args.length > 0 && args[0].equals("dll")) {
            tccArguments.add("-shared");
            for (int index = 1; index < args.length; index++) {
                tccArguments.add(args[index]);
            }
        } else {
            for (String argument : args) {
                tccArguments.add(argument);
            }
        }
        System.exit(TinyCC.executeTcc(tccArguments.toArray(String[]::new)));
    }

    private static void createLauncher(String[] args) throws Exception {
        if (args.length < 3) {
            usageAndExit();
        }
        String language = args[1].equals("kotln") ? "kotlin" : args[1];
        if (!language.equals("python") && !language.equals("java") && !language.equals("kotlin")) {
            usageAndExit();
        }

        Path sourcePath = Path.of(args[2]).toAbsolutePath();
        Path libraryPath = defaultLibraryPath(sourcePath);
        List<String> tccArguments = new ArrayList<>();
        tccArguments.add("-shared");
        tccArguments.add("-rdynamic");
        tccArguments.add(sourcePath.toString());
        boolean hasOutput = false;
        for (int index = 3; index < args.length; index++) {
            String argument = args[index];
            tccArguments.add(argument);
            if (argument.equals("-o")) {
                if (++index == args.length) {
                    throw new IllegalArgumentException("-o requires a library path");
                }
                libraryPath = Path.of(args[index]).toAbsolutePath();
                tccArguments.add(args[index]);
                hasOutput = true;
            } else if (argument.startsWith("-o") && argument.length() > 2) {
                libraryPath = Path.of(argument.substring(2)).toAbsolutePath();
                hasOutput = true;
            }
        }
        if (!hasOutput) {
            tccArguments.add("-o");
            tccArguments.add(libraryPath.toString());
        }

        int result = TinyCC.executeTcc(tccArguments.toArray(String[]::new));
        if (result != 0) {
            System.exit(result);
        }

        String className = launcherClassName(libraryPath);
        Path launcherPath = launcherPath(libraryPath, language, className);
        String libraryName = libraryPath.getFileName().toString();
        String launcher = switch (language) {
            case "python" -> pythonLauncher(libraryName);
            case "java" -> javaLauncher(libraryName, className);
            case "kotlin" -> kotlinLauncher(libraryName, className);
            default -> throw new AssertionError(language);
        };
        Files.writeString(launcherPath, launcher, StandardCharsets.UTF_8);
        System.out.println("created " + libraryPath);
        System.out.println("created " + launcherPath);
    }

    private static void usageAndExit() {
        System.err.println("usage: java -jar tinycc-cli.jar [all native tcc options]");
        System.err.println("   or: java -jar tinycc-cli.jar --launcher <python|java|kotlin> <source.c> [tcc options]");
        System.exit(2);
    }

    private static Path defaultLibraryPath(Path sourcePath) {
        String name = sourcePath.getFileName().toString();
        int suffix = name.lastIndexOf('.');
        String stem = suffix > 0 ? name.substring(0, suffix) : name;
        String os = System.getProperty("os.name").toLowerCase();
        String extension = os.contains("win") ? ".dll" : os.contains("mac") ? ".dylib" : ".so";
        return sourcePath.resolveSibling(stem + extension);
    }

    private static Path launcherPath(Path libraryPath, String language, String className) {
        String name = libraryPath.getFileName().toString();
        int suffix = name.lastIndexOf('.');
        String stem = suffix > 0 ? name.substring(0, suffix) : name;
        return switch (language) {
            case "python" -> libraryPath.resolveSibling(stem + ".py");
            case "java" -> libraryPath.resolveSibling(className + ".java");
            case "kotlin" -> libraryPath.resolveSibling(className + ".kt");
            default -> throw new AssertionError(language);
        };
    }

    private static String launcherClassName(Path libraryPath) {
        String name = libraryPath.getFileName().toString();
        int suffix = name.lastIndexOf('.');
        String stem = suffix > 0 ? name.substring(0, suffix) : name;
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < stem.length(); index++) {
            char character = stem.charAt(index);
            result.append(Character.isJavaIdentifierPart(character) ? character : '_');
        }
        if (result.isEmpty() || !Character.isJavaIdentifierStart(result.charAt(0))) {
            result.insert(0, "TinyCC");
        }
        result.setCharAt(0, Character.toUpperCase(result.charAt(0)));
        return result + "Launcher";
    }

    private static String pythonLauncher(String libraryName) {
        String quotedLibrary = libraryName.replace("\\", "\\\\").replace("'", "\\'");
        return """
                #!/usr/bin/env python3
                # Generated by tinycc-cli.jar. It forwards this script's arguments
                # to int main(int argc, char **argv) in the adjacent shared library.
                import ctypes
                import os
                import sys
                from pathlib import Path

                library = ctypes.CDLL(str(Path(__file__).with_name('%s')))
                main = library.main
                main.argtypes = [ctypes.c_int, ctypes.POINTER(ctypes.c_char_p)]
                main.restype = ctypes.c_int
                argv = [os.fsencode(sys.argv[0])] + [os.fsencode(arg) for arg in sys.argv[1:]]
                argv_c = (ctypes.c_char_p * (len(argv) + 1))()
                for index, argument in enumerate(argv):
                    argv_c[index] = argument
                raise SystemExit(main(len(argv), argv_c))
                """.formatted(quotedLibrary);
    }

    private static String javaLauncher(String libraryName, String className) {
        return """
                import java.nio.file.Path;
                import org.tinycc.TinyCC;

                public final class %s {
                    private static Path library() throws Exception {
                        Path location = Path.of(%s.class.getProtectionDomain()
                                .getCodeSource().getLocation().toURI());
                        Path directory = java.nio.file.Files.isDirectory(location)
                                ? location : location.getParent();
                        return directory.resolve("%s");
                    }

                    public static void main(String[] args) throws Exception {
                        System.exit(TinyCC.runLibraryMain(library(), args));
                    }
                }
                """.formatted(className, className, javaString(libraryName));
    }

    private static String kotlinLauncher(String libraryName, String className) {
        return """
                import java.nio.file.Files
                import java.nio.file.Path
                import kotlin.system.exitProcess
                import org.tinycc.TinyCC

                object %s {
                    private fun library(): Path {
                        val location = Path.of(%s::class.java.protectionDomain.codeSource.location.toURI())
                        val directory = if (Files.isDirectory(location)) location else location.parent
                        return directory.resolve("%s")
                    }

                    @JvmStatic
                    fun main(args: Array<String>) {
                        exitProcess(TinyCC.runLibraryMain(library(), *args))
                    }
                }
                """.formatted(className, className, javaString(libraryName));
    }

    private static String javaString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
