package lotes;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Rebuilds the .vsix files from the lote-XX folders, verifies SHA-256 against manifest.json
 * and, with --install, installs them in dependency order using the `code` CLI.
 *
 * <p>Usage: {@code java JoinVsix.java [--root <dir>] [--out <dir>] [--install]}
 */
public final class JoinVsix {

    private static final String MANIFEST_FILE = "manifest.json";
    private static final String DEFAULT_OUT_DIR = "vsix";
    private static final String TEMP_SUFFIX = ".tmp";
    private static final String SHA_256 = "SHA-256";
    private static final int BUFFER_SIZE = 1 << 20;

    private static final Pattern LOTE_DIR = Pattern.compile("lote-\\d+");
    private static final Pattern PART_FILE = Pattern.compile("(.+\\.vsix)\\.part(\\d+)");
    private static final Pattern MANIFEST_ENTRY = Pattern.compile(
            "\"name\"\\s*:\\s*\"([^\"]+)\"[^}]*?\"sha256\"\\s*:\\s*\"([0-9a-fA-F]{64})\"",
            Pattern.DOTALL);

    /** Packs (extensionPack) go last so their members are already installed. */
    private static final List<String> INSTALL_ORDER = List.of(
            "redhat.java",
            "vscjava.vscode-java-debug",
            "vscjava.vscode-java-test",
            "vscjava.vscode-maven",
            "vscjava.vscode-gradle",
            "vscjava.vscode-java-dependency",
            "vmware.vscode-spring-boot",
            "vscjava.vscode-spring-initializr",
            "vscjava.vscode-spring-boot-dashboard",
            "vscjava.vscode-java-pack",
            "vmware.vscode-boot-dev-pack");

    record Options(Path root, Path outDir, boolean install) {}

    record ManifestEntry(String name, String sha256) {}

    record Sources(Map<String, Path> whole, Map<String, TreeMap<Integer, Path>> parts) {}

    static final class JoinException extends Exception {
        JoinException(String message) {
            super(message);
        }

        JoinException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private JoinVsix() {}

    public static void main(String[] args) {
        try {
            run(parseArgs(args));
        } catch (JoinException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void run(Options options) throws JoinException {
        List<ManifestEntry> manifest = readManifest(options.root().resolve(MANIFEST_FILE));
        Sources sources = collectSources(options.root());

        try {
            Files.createDirectories(options.outDir());
        } catch (IOException e) {
            throw new JoinException("Cannot create " + options.outDir(), e);
        }

        for (ManifestEntry entry : manifest) {
            List<Path> pieces = piecesOf(entry.name(), sources);
            assembleAndVerify(entry, pieces, options.outDir().resolve(entry.name()));
            System.out.println("OK  " + entry.name());
        }
        System.out.printf("%n%d extensions ready in %s%n", manifest.size(), options.outDir());

        if (options.install()) {
            installAll(options.outDir());
        }
    }

    // ---- arguments ------------------------------------------------------------------------

    static Options parseArgs(String[] args) throws JoinException {
        Path root = findRoot(Path.of("").toAbsolutePath());
        Path outDir = null;
        boolean install = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--install" -> install = true;
                case "--root" -> root = Path.of(valueOf(args, ++i, "--root")).toAbsolutePath();
                case "--out" -> outDir = Path.of(valueOf(args, ++i, "--out")).toAbsolutePath();
                default -> throw new JoinException("Unknown argument: " + args[i]);
            }
        }
        return new Options(root, outDir != null ? outDir : root.resolve(DEFAULT_OUT_DIR), install);
    }

    private static String valueOf(String[] args, int index, String flag) throws JoinException {
        if (index >= args.length) {
            throw new JoinException(flag + " requires a value");
        }
        return args[index];
    }

    /** Uses the current dir, or its parent when run from inside join-java/. */
    private static Path findRoot(Path start) {
        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            if (Files.exists(candidate.resolve(MANIFEST_FILE))) {
                return candidate;
            }
        }
        return start;
    }

    // ---- inputs ---------------------------------------------------------------------------

    static List<ManifestEntry> readManifest(Path manifestPath) throws JoinException {
        if (!Files.exists(manifestPath)) {
            throw new JoinException(MANIFEST_FILE + " not found at " + manifestPath
                    + " (use --root <dir> pointing at the repository)");
        }
        String json = readString(manifestPath);
        List<ManifestEntry> entries = new ArrayList<>();
        Matcher matcher = MANIFEST_ENTRY.matcher(json);
        while (matcher.find()) {
            entries.add(new ManifestEntry(matcher.group(1), matcher.group(2).toLowerCase()));
        }
        if (entries.isEmpty()) {
            throw new JoinException("No files listed in " + manifestPath);
        }
        return entries;
    }

    private static String readString(Path path) throws JoinException {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new JoinException("Cannot read " + path, e);
        }
    }

    static Sources collectSources(Path root) throws JoinException {
        Map<String, Path> whole = new TreeMap<>();
        Map<String, TreeMap<Integer, Path>> parts = new TreeMap<>();

        try (Stream<Path> lotes = Files.list(root)) {
            List<Path> loteDirs = lotes
                    .filter(Files::isDirectory)
                    .filter(dir -> LOTE_DIR.matcher(dir.getFileName().toString()).matches())
                    .sorted()
                    .toList();
            for (Path lote : loteDirs) {
                classifyFiles(lote, whole, parts);
            }
        } catch (IOException e) {
            throw new JoinException("Cannot read lote folders in " + root, e);
        }
        return new Sources(whole, parts);
    }

    private static void classifyFiles(Path lote, Map<String, Path> whole,
                                      Map<String, TreeMap<Integer, Path>> parts) throws IOException {
        try (Stream<Path> files = Files.list(lote)) {
            for (Path file : files.sorted().toList()) {
                String fileName = file.getFileName().toString();
                Matcher part = PART_FILE.matcher(fileName);
                if (part.matches()) {
                    parts.computeIfAbsent(part.group(1), key -> new TreeMap<>())
                            .put(Integer.parseInt(part.group(2)), file);
                } else if (fileName.endsWith(".vsix")) {
                    whole.put(fileName, file);
                }
            }
        }
    }

    private static List<Path> piecesOf(String name, Sources sources) throws JoinException {
        Path single = sources.whole().get(name);
        if (single != null) {
            return List.of(single);
        }
        TreeMap<Integer, Path> indexed = sources.parts().get(name);
        if (indexed == null) {
            throw new JoinException("Missing file or parts for " + name);
        }
        int expected = 1;
        for (int index : indexed.keySet()) {
            if (index != expected) {
                throw new JoinException("Missing part " + expected + " of " + name);
            }
            expected++;
        }
        return List.copyOf(indexed.values());
    }

    // ---- assemble + verify ----------------------------------------------------------------

    private static void assembleAndVerify(ManifestEntry entry, List<Path> pieces, Path target)
            throws JoinException {
        Path temp = target.resolveSibling(target.getFileName() + TEMP_SUFFIX);
        try {
            String actual = concatenate(pieces, temp);
            if (!actual.equals(entry.sha256())) {
                Files.deleteIfExists(temp);
                throw new JoinException("Checksum mismatch for " + entry.name()
                        + " (corrupt or incomplete upload)");
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new JoinException("Cannot build " + entry.name(), e);
        }
    }

    /** Streams every piece into {@code target} and returns the SHA-256 (hex) of the result. */
    private static String concatenate(List<Path> pieces, Path target) throws IOException {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[BUFFER_SIZE];
        try (OutputStream out = Files.newOutputStream(target)) {
            for (Path piece : pieces) {
                try (InputStream in = Files.newInputStream(piece)) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        digest.update(buffer, 0, read);
                        out.write(buffer, 0, read);
                    }
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(SHA_256);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(SHA_256 + " is required by the Java spec", e);
        }
    }

    // ---- install --------------------------------------------------------------------------

    private static void installAll(Path outDir) throws JoinException {
        List<Path> vsixFiles = listVsix(outDir);
        for (String id : INSTALL_ORDER) {
            Path file = vsixFiles.stream()
                    .filter(path -> path.getFileName().toString().startsWith(id + "-"))
                    .findFirst()
                    .orElseThrow(() -> new JoinException("No .vsix found for " + id));
            System.out.println("Installing " + file.getFileName());
            runCode(file);
        }
    }

    private static List<Path> listVsix(Path outDir) throws JoinException {
        try (Stream<Path> files = Files.list(outDir)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".vsix")).toList();
        } catch (IOException e) {
            throw new JoinException("Cannot read " + outDir, e);
        }
    }

    private static void runCode(Path vsix) throws JoinException {
        List<String> command = new ArrayList<>();
        if (isWindows()) {
            command.addAll(List.of("cmd", "/c")); // `code` is code.cmd on Windows
        }
        command.addAll(List.of("code", "--install-extension", vsix.toString()));

        try {
            int exitCode = new ProcessBuilder(command).inheritIO().start().waitFor();
            if (exitCode != 0) {
                throw new JoinException("`code` exited with " + exitCode + " installing " + vsix.getFileName());
            }
        } catch (IOException e) {
            throw new JoinException("Cannot run the `code` CLI (is VS Code on your PATH?)", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JoinException("Interrupted while installing " + vsix.getFileName(), e);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
