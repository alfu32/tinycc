package org.tinycc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * Self-contained JNI access to the libtcc compiler.
 *
 * <p>The supplied embed JAR includes native libraries for the six supported
 * host triples. Native files are extracted once per class loader to a private
 * temporary directory before being loaded.</p>
 */
public final class TinyCC {
    public enum OutputType {
        EXECUTABLE(2),
        DYNAMIC_LIBRARY(4);

        private final int nativeValue;

        OutputType(int nativeValue) {
            this.nativeValue = nativeValue;
        }
    }

    private static final NativeBundle NATIVE_BUNDLE = loadNativeBundle();
    private static final Path RUNTIME_DIRECTORY = NATIVE_BUNDLE.runtimeDirectory();

    private TinyCC() {
    }

    /**
     * Compiles C source into an executable or dynamic library.
     *
     * @return zero on success; TinyCC's non-zero result otherwise.
     */
    public static int compile(
            String source, OutputType outputType, Path outputPath,
            DiagnosticListener diagnostics) {
        return compile(source, outputType, outputPath, "", diagnostics);
    }

    /**
     * Compiles C source with command-line-compatible TinyCC options.
     *
     * <p>The output type and output path are controlled by this API; do not
     * pass {@code -o}, {@code -shared}, or {@code -run} in {@code options}.</p>
     */
    public static int compile(
            String source, OutputType outputType, Path outputPath, String options,
            DiagnosticListener diagnostics) {
        if (source == null || outputType == null || outputPath == null) {
            throw new NullPointerException("source, outputType, and outputPath are required");
        }
        return compileNative(
                RUNTIME_DIRECTORY.toString(), source, outputType.nativeValue,
                outputPath.toAbsolutePath().toString(), options == null ? "" : options, diagnostics);
    }

    public static int compileExecutable(
            String source, Path outputPath, DiagnosticListener diagnostics) {
        return compile(source, OutputType.EXECUTABLE, outputPath, diagnostics);
    }

    public static int compileDynamicLibrary(
            String source, Path outputPath, DiagnosticListener diagnostics) {
        return compile(source, OutputType.DYNAMIC_LIBRARY, outputPath, diagnostics);
    }

    /**
     * Runs the bundled native {@code tcc} command with its exact command-line
     * semantics. This is the full-driver counterpart to {@link #compile}.
     */
    public static int executeTcc(String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        if (NATIVE_BUNDLE.windows()) {
            command.add(NATIVE_BUNDLE.nativeDirectory().resolve("tcc.exe").toString());
        } else {
            Path compiler = NATIVE_BUNDLE.root().resolve("tinycc/bin/tcc-bin");
            makeExecutable(compiler);
            command.add(compiler.toString());
            command.add("-B");
            command.add(RUNTIME_DIRECTORY.toString());
        }
        command.addAll(Arrays.asList(arguments));
        Process process = new ProcessBuilder(command).inheritIO().start();
        return process.waitFor();
    }

    /**
     * Invokes {@code int main(int argc, char **argv)} exported by a shared
     * library. The library path is provided as {@code argv[0]}.
     */
    public static int runLibraryMain(Path library, String... arguments) {
        if (library == null || arguments == null) {
            throw new NullPointerException("library and arguments are required");
        }
        String[] argv = new String[arguments.length + 1];
        argv[0] = library.toAbsolutePath().toString();
        System.arraycopy(arguments, 0, argv, 1, arguments.length);
        return runLibraryMainNative(library.toAbsolutePath().toString(), argv);
    }

    private static native int compileNative(
            String runtimeDirectory, String source, int outputType, String outputPath,
            String options, DiagnosticListener diagnostics);
    private static native int runLibraryMainNative(String library, String[] argv);

    private static NativeBundle loadNativeBundle() {
        String target = target();
        String prefix = "native/" + target + "/";
        Path extractionDirectory;
        try {
            extractionDirectory = Files.createTempDirectory("tinycc-" + target + "-");
            try (InputStream fileList = resource(prefix + "files.list")) {
                String[] files = new String(fileList.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                        .split("\\R");
                for (String file : files) {
                    if (file.isEmpty()) {
                        continue;
                    }
                    Path destination = extractionDirectory.resolve(file).normalize();
                    if (!destination.startsWith(extractionDirectory)) {
                        throw new IOException("invalid native resource path");
                    }
                    Files.createDirectories(destination.getParent());
                    try (InputStream input = resource(prefix + file)) {
                        Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            boolean windows = target.startsWith("windows-");
            Path nativeDirectory = windows
                    ? extractionDirectory.resolve("tinycc/bin")
                    : extractionDirectory.resolve("tinycc/lib");
            String suffix = windows ? ".dll" : target.startsWith("macos-") ? ".dylib" : ".so";
            System.load(nativeDirectory.resolve("libtcc" + suffix).toString());
            System.load(nativeDirectory.resolve("libtinycc_jni" + suffix).toString());
            return new NativeBundle(
                    extractionDirectory, nativeDirectory,
                    windows ? nativeDirectory : nativeDirectory.resolve("tcc"), windows);
        } catch (IOException exception) {
            throw new IllegalStateException("could not unpack TinyCC native bundle for " + target, exception);
        }
    }

    private static void makeExecutable(Path executable) throws IOException {
        try {
            Files.setPosixFilePermissions(executable, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            // Windows does not use POSIX execute permissions.
        }
    }

    private record NativeBundle(
            Path root, Path nativeDirectory, Path runtimeDirectory, boolean windows) {
    }

    private static InputStream resource(String name) throws IOException {
        InputStream input = TinyCC.class.getClassLoader().getResourceAsStream(name);
        if (input == null) {
            throw new IOException("missing resource: " + name);
        }
        return input;
    }

    private static String target() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String platform;
        if (os.contains("win")) {
            platform = "windows";
        } else if (os.contains("mac") || os.contains("darwin")) {
            platform = "macos";
        } else if (os.contains("linux")) {
            platform = "linux";
        } else {
            throw new IllegalStateException("unsupported operating system: " + os);
        }
        String cpu;
        if (arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64")) {
            cpu = "x86_64";
        } else if (arch.equals("aarch64") || arch.equals("arm64")) {
            cpu = "aarch64";
        } else {
            throw new IllegalStateException("unsupported CPU architecture: " + arch);
        }
        return platform + "-" + cpu;
    }
}
