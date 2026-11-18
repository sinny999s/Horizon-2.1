package dev.horizon.shaders.graph;

import dev.horizon.shaders.runtime.GpuCapabilities;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public final class PackFiles {
    private static final int MAX_FILE = 8388608;
    private static final long MAX_PACK = 134217728L;
    private final Map<String, byte[]> files;

    private PackFiles(Map<String, byte[]> files) {
        this.files = Map.copyOf(files);
    }

    public static PackFiles read(Path path) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        long total = 0L;
        if (Files.isDirectory(path)) {
            Path root = path.toRealPath();

            try (Stream<Path> paths = Files.walk(root)) {
                for (Path file : paths.filter(x$0 -> Files.isRegularFile(x$0)).toList()) {
                    if (!file.toRealPath().startsWith(root)) {
                        throw new IOException("Pack symlink escapes its folder");
                    }

                    try (InputStream stream = Files.newInputStream(file)) {
                        byte[] bytes = stream.readNBytes(8388609);
                        total = add(entries, root.relativize(file).toString().replace('\\', '/'), bytes, total);
                    }
                }
            }
        } else {
            try (ZipFile zip = new ZipFile(path.toFile())) {
                Enumeration<? extends ZipEntry> items = zip.entries();

                while (items.hasMoreElements()) {
                    ZipEntry entry = items.nextElement();
                    if (!entry.isDirectory()) {
                        try (InputStream stream = zip.getInputStream(entry)) {
                            total = add(entries, entry.getName(), stream.readNBytes(8388609), total);
                        }
                    }
                }
            }
        }

        return rooted(entries);
    }

    public static PackFiles bundled() throws IOException {
        InputStream resource = PackFiles.class.getResourceAsStream("/sulkan-bundled/Sulkan-Realistic.zip");
        if (resource == null) {
            throw new IOException("Horizon Realistic is missing from this installation. Reinstall Horizon.");
        }

        Map<String, byte[]> entries = new LinkedHashMap<>();
        long total = 0L;

        ZipEntry entry;
        try (ZipInputStream zip = new ZipInputStream(resource)) {
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    total = add(entries, entry.getName(), zip.readNBytes(8388609), total);
                }
            }
        }

        return rooted(entries);
    }

    private static PackFiles rooted(Map<String, byte[]> entries) throws IOException {
        List<String> manifests = entries.keySet().stream().filter(n -> n.equals("sulkan.json") || n.endsWith("/sulkan.json")).toList();
        if (manifests.size() != 1) {
            throw new IOException("Native pack needs exactly one sulkan.json");
        }

        String prefix = manifests.getFirst().substring(0, manifests.getFirst().length() - "sulkan.json".length());
        Map<String, byte[]> rooted = new LinkedHashMap<>();
        entries.forEach((name, bytes) -> {
            if (name.startsWith(prefix)) {
                rooted.put(name.substring(prefix.length()), bytes);
            }
        });
        return new PackFiles(rooted);
    }

    private static long add(Map<String, byte[]> files, String name, byte[] bytes, long total) throws IOException {
        if (!safe(name)) {
            throw new IOException("Invalid pack path: " + name);
        } else if (bytes.length > 8388608 || total + bytes.length > 134217728L || files.size() >= 16384) {
            throw new IOException("Pack exceeds loader size limits");
        } else if (files.putIfAbsent(name, bytes) != null) {
            throw new IOException("Duplicate pack entry: " + name);
        } else {
            return total + bytes.length;
        }
    }

    public static boolean safe(String name) {
        return name != null
            && !name.isBlank()
            && !name.startsWith("/")
            && !name.contains("\\")
            && !name.contains(":")
            && Arrays.stream(name.split("/", -1)).noneMatch(p -> p.isEmpty() || p.equals(".") || p.equals(".."));
    }

    public String text(String name) throws IOException {
        if ("sulkan/frame.glsl".equals(name)) {
            return GraphFrame.declaration();
        }

        if ("sulkan/shadows.glsl".equals(name) || "sulkan/shadow-filter.glsl".equals(name) || "sulkan/terrain-face.glsl".equals(name)) {
            String resource = switch (name) {
                case "sulkan/shadows.glsl" -> "/assets/sulkan/shaders/include/native_shadows.glsl";
                case "sulkan/terrain-face.glsl" -> "/assets/sulkan/shaders/include/terrain_face.glsl";
                default -> "/assets/sulkan/shaders/include/directional_shadow.glsl";
            };

            try (InputStream stream = PackFiles.class.getResourceAsStream(resource)) {
                if (stream == null) {
                    throw new IOException("Missing loader include " + resource);
                } else {
                    return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } else if (safe(name) && this.files.containsKey(name)) {
            return new String(this.files.get(name), StandardCharsets.UTF_8);
        } else {
            throw new IOException("Missing pack file: " + name);
        }
    }

    public byte[] binary(String name) throws IOException {
        if (safe(name) && this.files.containsKey(name)) {
            return (byte[])this.files.get(name).clone();
        } else {
            throw new IOException("Missing pack file: " + name);
        }
    }

    // Defines the jar adds to every pack shader (features the pack turned on in sulkan.json, not user options).
    private final Map<String, Double> features = new java.util.TreeMap<>();

    public void feature(String name) {
        this.features.put(name, 1.0);
    }

    public String shader(String name, Map<String, Double> options) throws IOException {
        Map<String, Double> defines = new java.util.LinkedHashMap<>(options);
        this.features.forEach(defines::putIfAbsent);
        String source = this.expand(name, new ArrayDeque<>(), new HashMap<>());
        int end = source.indexOf(10);
        if (source.startsWith("#version ") && end >= 0) {
            StringBuilder header = new StringBuilder();
            defines.forEach((key, value) -> {
                if (key.matches("[A-Z][A-Z0-9_]*") && Double.isFinite(value)) {
                    String literal = value == Math.rint(value) && Math.abs(value) <= 2.147483647E9 ? Long.toString(value.longValue()) : value.toString();
                    header.append("#define _SULKAN_ENABLED_").append(key).append(value > 0.0 ? " 1\n" : " 0\n");
                    header.append("#define ").append(key).append(' ').append(literal).append('\n');
                } else {
                    throw new IllegalArgumentException("Invalid shader define " + key);
                }
            });
            return source.substring(0, end + 1) + GpuCapabilities.current().shaderHeader() + header + "#line 2 0\n" + source.substring(end + 1);
        } else {
            throw new IOException(name + ": shader must start with #version");
        }
    }

    private String expand(String name, Deque<String> stack, Map<String, Integer> ids) throws IOException {
        if (!stack.contains(name) && stack.size() < 32) {
            stack.addLast(name);
            int id = ids.computeIfAbsent(name, ignored -> ids.size());
            StringBuilder result = new StringBuilder();
            String[] lines = this.text(name).split("\n", -1);

            for (int line = 0; line < lines.length; line++) {
                String trimmed = lines[line].trim();
                if (trimmed.startsWith("#include")) {
                    Matcher matcher = Pattern.compile("#include\\s+\"([^\"]+)\"\\s*(?://.*)?").matcher(trimmed);
                    if (!matcher.matches()) {
                        throw new IOException(name + ":" + (line + 1) + ": expected a quoted pack-root include");
                    }

                    String included = matcher.group(1);
                    int includeId = ids.computeIfAbsent(included, ignored -> ids.size());
                    result.append("#line 1 ").append(includeId).append('\n').append(this.expand(included, stack, ids));
                    result.append("#line ").append(line + 2).append(' ').append(id).append('\n');
                } else {
                    result.append(lines[line]).append('\n');
                }

                if (result.length() > 8388608) {
                    throw new IOException("Expanded shader too large: " + name);
                }
            }

            stack.removeLast();
            return result.toString();
        } else {
            throw new IOException("Include cycle/depth: " + stack + " -> " + name);
        }
    }
}
