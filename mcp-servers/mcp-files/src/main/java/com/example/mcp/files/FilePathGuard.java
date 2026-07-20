package com.example.mcp.files;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

@Component
public class FilePathGuard {

    private static final List<String> WRITE_PREFIXES = List.of(
            "project/docs/",
            "docs/",
            "adr/");
    private static final List<String> WRITE_EXACT = List.of(
            "README.md",
            "CHANGELOG.md");
    private static final List<String> DENIED_SEGMENTS = List.of(
            ".git",
            "node_modules",
            "target",
            ".env",
            "credentials");

    private final Path repoRoot;

    public FilePathGuard(@Value("${files.repo.root:}") String repoRootProperty) {
        this.repoRoot = resolveRepoRoot(repoRootProperty);
    }

    public Path repoRoot() {
        return repoRoot;
    }

    public Path resolveReadable(String relativePath) {
        Path resolved = resolveUnderRepo(relativePath);
        if (isDenied(resolved)) {
            throw new IllegalArgumentException("Path not readable: " + relativePath);
        }
        return resolved;
    }

    public Path resolveWritable(String relativePath) {
        Path resolved = resolveUnderRepo(relativePath);
        if (!isWriteAllowed(resolved)) {
            throw new IllegalArgumentException(
                    "Write not allowed for path: " + relativePath
                            + ". Allowed: project/docs/**, docs/**, adr/**, README.md, CHANGELOG.md");
        }
        return resolved;
    }

    public boolean isWriteAllowed(Path resolved) {
        if (isDenied(resolved)) {
            return false;
        }
        String relative = repoRelative(resolved);
        for (String exact : WRITE_EXACT) {
            if (relative.equalsIgnoreCase(exact)) {
                return true;
            }
        }
        String normalized = relative.replace('\\', '/').toLowerCase(Locale.ROOT);
        for (String prefix : WRITE_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public List<String> writeAllowlistSummary() {
        return List.of(
                "project/docs/**",
                "docs/**",
                "adr/**",
                "README.md",
                "CHANGELOG.md");
    }

    private Path resolveUnderRepo(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        String cleaned = relativePath.trim().replace('\\', '/');
        while (cleaned.startsWith("./")) {
            cleaned = cleaned.substring(2);
        }
        if (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        Path resolved = repoRoot.resolve(cleaned).normalize();
        if (!resolved.startsWith(repoRoot)) {
            throw new IllegalArgumentException("Path escapes repository root: " + relativePath);
        }
        return resolved;
    }

    private boolean isDenied(Path resolved) {
        for (Path part : resolved) {
            String name = part.toString().toLowerCase(Locale.ROOT);
            for (String denied : DENIED_SEGMENTS) {
                if (name.equals(denied)) {
                    return true;
                }
            }
        }
        String relative = repoRelative(resolved).toLowerCase(Locale.ROOT);
        return relative.contains("/.git/")
                || relative.startsWith(".git/")
                || relative.endsWith("application-local.yml")
                || relative.contains("/secrets/");
    }

    private String repoRelative(Path resolved) {
        Path relative = repoRoot.relativize(resolved);
        return relative.toString().replace('\\', '/');
    }

    private static Path resolveRepoRoot(String configured) {
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured.trim()).toAbsolutePath().normalize();
        }
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        Path[] candidates = {cwd, cwd.getParent(), Paths.get("..").toAbsolutePath().normalize()};
        for (Path candidate : candidates) {
            if (candidate != null && Files.isDirectory(candidate.resolve(".git"))) {
                return candidate;
            }
            if (candidate != null
                    && Files.isRegularFile(candidate.resolve("pom.xml"))
                    && Files.isDirectory(candidate.resolve("backend"))
                    && Files.isDirectory(candidate.resolve("mcp-servers"))) {
                return candidate;
            }
        }
        Path parent = cwd.getParent();
        return parent != null ? parent : cwd;
    }
}
