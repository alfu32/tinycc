package org.tinycc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Self-contained JNI access to the libtcc compiler.
 *
 * <p>The supplied embed JAR includes native libraries for the six supported
 * host triples. Native files are extracted once per class loader to a private
 * temporary directory before being loaded.</p>
 */
public final class TinyCC {
    private static final Map<String, CrossTarget> CROSS_TARGETS = Map.ofEntries(
            Map.entry("linux-x86_64", new CrossTarget("linux-x86_64")),
            Map.entry("linux-aarch64", new CrossTarget("linux-aarch64")),
            Map.entry("windows-x86_64", new CrossTarget("windows-x86_64")),
            Map.entry("windows-aarch64", new CrossTarget("windows-aarch64")),
            Map.entry("macos-x86_64", new CrossTarget("macos-x86_64")),
            Map.entry("macos-aarch64", new CrossTarget("macos-aarch64")));

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
        Path sysroot = NATIVE_BUNDLE.sysroot();
        String defaultSysroot = sysroot != null && (options == null || !options.contains("--sysroot"))
                ? sysroot.toString() : "";
        return compileNative(
                RUNTIME_DIRECTORY.toString(), defaultSysroot, source, outputType.nativeValue,
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
     * Runs the bundled TCC driver through its exported {@code main} function.
     * No child executable is spawned; the driver DLL/SO is loaded with JNI.
     */
    public static int executeTcc(String... arguments) {
        List<String> tccArguments = new java.util.ArrayList<>();
        CrossTarget selectedTarget = null;
        for (int index = 0; index < arguments.length; index++) {
            String argument = arguments[index];
            if (argument.equals("--")) {
                for (; index < arguments.length; index++) {
                    tccArguments.add(arguments[index]);
                }
                break;
            }
            if (argument.equals("--target")) {
                if (++index == arguments.length) {
                    throw new IllegalArgumentException("--target requires a target triple");
                }
                if (selectedTarget != null) {
                    throw new IllegalArgumentException("--target may be specified only once");
                }
                selectedTarget = crossTarget(arguments[index]);
            } else if (argument.startsWith("--target=")) {
                if (selectedTarget != null) {
                    throw new IllegalArgumentException("--target may be specified only once");
                }
                selectedTarget = crossTarget(argument.substring("--target=".length()));
            } else {
                tccArguments.add(argument);
            }
        }

        Path driver = NATIVE_BUNDLE.driver();
        Path runtimeDirectory = RUNTIME_DIRECTORY;
        Path sysroot = NATIVE_BUNDLE.sysroot();
        if (selectedTarget != null) {
            if (!selectedTarget.platform().equals(target())) {
                driver = NATIVE_BUNDLE.nativeDirectory().resolve("cross")
                        .resolve(selectedTarget.platform()).resolve("tcc-driver" + nativeLibrarySuffix());
                if (!Files.isRegularFile(driver)) {
                    throw new IllegalArgumentException("cross target '" + selectedTarget.platform()
                            + "' is not bundled in this host-specific JAR; use tinycc-cli.jar or "
                            + "tinycc-embed.jar for all cross targets");
                }
                runtimeDirectory = driver.getParent();
                sysroot = targetSysroot(selectedTarget.platform());
            }
        }

        boolean hasSysroot = false;
        for (String argument : tccArguments) {
            if (argument.equals("--sysroot") || argument.startsWith("--sysroot=")) {
                hasSysroot = true;
                break;
            }
        }
        boolean bundledWindowsLibraries = selectedTarget != null
                && selectedTarget.platform().startsWith("windows-") && !hasSysroot;
        int injected = (selectedTarget == null ? 0 : 2)
                + (bundledWindowsLibraries ? 2 : 0)
                + (sysroot != null && !hasSysroot ? 2 : 0);
        String[] driverArguments = new String[tccArguments.size() + 2 + injected];
        driverArguments[0] = "-B";
        driverArguments[1] = runtimeDirectory.toString();
        int offset = 2;
        if (selectedTarget != null) {
            driverArguments[offset++] = "-L";
            driverArguments[offset++] = runtimeDirectory.toString();
        }
        if (bundledWindowsLibraries) {
            driverArguments[offset++] = "-L";
            driverArguments[offset++] = runtimeDirectory.resolve("lib").toString();
        }
        if (sysroot != null && !hasSysroot) {
            driverArguments[offset++] = "--sysroot";
            driverArguments[offset++] = sysroot.toString();
        }
        for (String argument : tccArguments) {
            driverArguments[offset++] = argument;
        }
        return runLibraryMain(driver, driverArguments);
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
            String runtimeDirectory, String sysrootDirectory, String source, int outputType, String outputPath,
            String options, DiagnosticListener diagnostics);
    private static native int runLibraryMainNative(String library, String[] argv);

    private static NativeBundle loadNativeBundle() {
        String target = target();
        String prefix = "native/" + target + "/";
        if (TinyCC.class.getClassLoader().getResource(prefix + "files.list") == null) {
            throw new IllegalStateException("this TinyCC JAR does not include native payload for host " + target
                    + "; use tinycc-embed.jar or tinycc-cli.jar for all hosts");
        }
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
            Path bundledSysroot = extractionDirectory.resolve("tinycc/sysroot");
            return new NativeBundle(
                    extractionDirectory, nativeDirectory,
                    windows ? nativeDirectory : nativeDirectory.resolve("tcc"),
                    nativeDirectory.resolve("tcc-driver" + suffix),
                    Files.isDirectory(bundledSysroot) ? bundledSysroot : null,
                    windows);
        } catch (IOException exception) {
            throw new IllegalStateException("could not unpack TinyCC native bundle for " + target, exception);
        }
    }

    private record NativeBundle(
            Path root, Path nativeDirectory, Path runtimeDirectory, Path driver, Path sysroot, boolean windows) {
    }

    private static InputStream resource(String name) throws IOException {
        InputStream input = TinyCC.class.getClassLoader().getResourceAsStream(name);
        if (input == null) {
            throw new IOException("missing resource: " + name);
        }
        return input;
    }

    private static CrossTarget crossTarget(String triple) {
        CrossTarget target = CROSS_TARGETS.get(triple);
        if (target == null) {
            throw new IllegalArgumentException("unsupported target '" + triple + "'; expected one of "
                    + String.join(", ", CROSS_TARGETS.keySet()));
        }
        return target;
    }

    private static String nativeLibrarySuffix() {
        return NATIVE_BUNDLE.windows() ? ".dll"
                : target().startsWith("macos-") ? ".dylib" : ".so";
    }

    private static synchronized Path targetSysroot(String platform) {
        if (platform.startsWith("macos-")) {
            return null;
        }
        if (platform.equals(target())) {
            return NATIVE_BUNDLE.sysroot();
        }
        Path extractionRoot = NATIVE_BUNDLE.root().resolve("sysroots").resolve(platform);
        Path destinationRoot = extractionRoot.resolve("tinycc/sysroot");
        try (InputStream manifest = resource("native/" + platform + "/files.list")) {
            String[] files = new String(manifest.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .split("\\R");
            int extracted = 0;
            for (String file : files) {
                if (!file.startsWith("tinycc/sysroot/")) {
                    continue;
                }
                Path destination = extractionRoot.resolve(file).normalize();
                if (!destination.startsWith(extractionRoot)) {
                    throw new IOException("invalid sysroot resource path");
                }
                Files.createDirectories(destination.getParent());
                try (InputStream input = resource("native/" + platform + "/" + file)) {
                    Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
                }
                extracted++;
            }
            return extracted == 0 ? null : destinationRoot;
        } catch (IOException exception) {
            throw new IllegalStateException("could not unpack sysroot for " + platform, exception);
        }
    }

    private record CrossTarget(String platform) {
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
