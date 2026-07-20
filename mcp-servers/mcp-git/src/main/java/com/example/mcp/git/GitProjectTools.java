package com.example.mcp.git;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class GitProjectTools {

    private static final Logger log = LoggerFactory.getLogger(GitProjectTools.class);
    private static final int DEFAULT_FILE_LIMIT = 80;
    private static final int MAX_DIFF_CHARS = 6000;

    private final Path repoRoot;

    public GitProjectTools(@Value("${git.repo.root:}") String repoRootProperty) {
        this.repoRoot = resolveRepoRoot(repoRootProperty);
        log.info("mcp-git repo root: {}", this.repoRoot);
    }

    @Tool(description = """
            Get the current git branch of the project repository.
            Use when answering developer questions about which branch is checked out.""")
    public Map<String, Object> getCurrentBranch() {
        String branch = runGit("rev-parse", "--abbrev-ref", "HEAD").trim();
        String commit = runGit("rev-parse", "--short", "HEAD").trim();
        log.info("getCurrentBranch -> {} @ {}", branch, commit);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("branch", branch);
        result.put("shortCommit", commit);
        result.put("repoRoot", repoRoot.toString());
        return result;
    }

    @Tool(description = """
            List tracked files in the git repository (git ls-files).
            Optional path prefix filters by directory (e.g. 'backend/' or 'project/docs').""")
    public Map<String, Object> listRepoFiles(
            @ToolParam(description = "Optional path prefix filter", required = false) String pathPrefix,
            @ToolParam(description = "Max files to return (default 80)", required = false) Integer limit) {
        int max = limit != null && limit > 0 ? Math.min(limit, 500) : DEFAULT_FILE_LIMIT;
        String prefix = pathPrefix != null ? pathPrefix.trim().replace('\\', '/') : "";
        List<String> all = List.of(runGit("ls-files").split("\\R"));
        List<String> filtered = all.stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .filter(line -> prefix.isEmpty() || line.replace('\\', '/').startsWith(prefix))
                .limit(max)
                .toList();
        log.info("listRepoFiles prefix='{}' limit={} -> {} file(s)", prefix, max, filtered.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pathPrefix", prefix);
        result.put("limit", max);
        result.put("totalMatched", filtered.size());
        result.put("files", filtered);
        return result;
    }

    @Tool(description = """
            Get a summary of the working tree diff (git diff --stat) and a truncated unified diff.
            Use for questions about uncommitted changes.""")
    public Map<String, Object> getWorkingTreeDiff(
            @ToolParam(description = "Optional path filter for diff", required = false) String path) {
        List<String> statArgs = new ArrayList<>();
        statArgs.add("diff");
        statArgs.add("--stat");
        if (path != null && !path.isBlank()) {
            statArgs.add("--");
            statArgs.add(path.trim());
        }
        String stat = runGit(statArgs.toArray(new String[0])).trim();

        List<String> diffArgs = new ArrayList<>();
        diffArgs.add("diff");
        if (path != null && !path.isBlank()) {
            diffArgs.add("--");
            diffArgs.add(path.trim());
        }
        String diff = runGit(diffArgs.toArray(new String[0]));
        boolean truncated = false;
        if (diff.length() > MAX_DIFF_CHARS) {
            diff = diff.substring(0, MAX_DIFF_CHARS) + "\n… [truncated]";
            truncated = true;
        }
        log.info("getWorkingTreeDiff path='{}' statChars={} truncated={}",
                path != null ? path : "", stat.length(), truncated);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", path != null ? path : "");
        result.put("stat", stat.isBlank() ? "(no unstaged changes)" : stat);
        result.put("diff", diff.isBlank() ? "(no unstaged changes)" : diff);
        result.put("truncated", truncated);
        return result;
    }

    private String runGit(String... gitArgs) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repoRoot.toString());
        command.addAll(List.of(gitArgs));
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }
            boolean finished = process.waitFor(20, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("git command timed out: " + String.join(" ", gitArgs));
            }
            int code = process.exitValue();
            if (code != 0) {
                throw new IllegalStateException(
                        "git " + String.join(" ", gitArgs) + " failed (" + code + "): " + output.trim());
            }
            return output;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Failed to run git " + String.join(" ", gitArgs) + ": " + exception.getMessage(),
                    exception);
        }
    }

    private static Path resolveRepoRoot(String configured) {
        if (configured != null && !configured.isBlank()) {
            Path path = Paths.get(configured.trim()).toAbsolutePath().normalize();
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        Path[] candidates = {cwd, cwd.getParent(), Paths.get("..").toAbsolutePath().normalize()};
        for (Path candidate : candidates) {
            if (candidate != null && Files.isDirectory(candidate.resolve(".git"))) {
                return candidate;
            }
        }
        return cwd;
    }
}
