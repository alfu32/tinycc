package org.tinycc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

    private static final Path RUNTIME_DIRECTORY = loadNativeBundle();

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
        if (source == null || outputType == null || outputPath == null) {
            throw new NullPointerException("source, outputType, and outputPath are required");
        }
        return compileNative(
                RUNTIME_DIRECTORY.toString(), source, outputType.nativeValue,
                outputPath.toAbsolutePath().toString(), diagnostics);
    }

    public static int compileExecutable(
            String source, Path outputPath, DiagnosticListener diagnostics) {
        return compile(source, OutputType.EXECUTABLE, outputPath, diagnostics);
    }

    public static int compileDynamicLibrary(
            String source, Path outputPath, DiagnosticListener diagnostics) {
        return compile(source, OutputType.DYNAMIC_LIBRARY, outputPath, diagnostics);
    }

    private static native int compileNative(
            String runtimeDirectory, String source, int outputType, String outputPath,
            DiagnosticListener diagnostics);

    private static Path loadNativeBundle() {
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
            return windows ? nativeDirectory : nativeDirectory.resolve("tcc");
        } catch (IOException exception) {
            throw new IllegalStateException("could not unpack TinyCC native bundle for " + target, exception);
        }
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
