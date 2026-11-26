package com.pulseagent.code;

import com.pulseagent.code.CodeModels.BlameInfo;
import com.pulseagent.code.CodeModels.CommitInfo;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Reads code and blame from a local git checkout. Commands are run without a shell, with
 * fixed arguments; refs are pattern-validated and paths come only from `git ls-files`.
 */
class LocalGitProvider implements CodeModels.CodeProvider {

    private static final int MAX_OUTPUT = 2 * 1024 * 1024;
    private final Path repo;
    private volatile List<String> fileCache;
    private volatile long fileCacheAt;

    LocalGitProvider(String repoPath) {
        Path p = Path.of(repoPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(p.resolve(".git")) && !Files.isRegularFile(p.resolve(".git"))) {
            throw new IllegalStateException("APP_CODE_REPO_PATH is not a git repository");
        }
        this.repo = p;
    }

    @Override public String name() { return "local-git"; }

    @Override
    public List<String> listFiles(String ref) {
        long now = System.currentTimeMillis();
        if (fileCache == null || now - fileCacheAt > 60_000) {
            String out = git("ls-files");
            fileCache = out == null ? List.of() : out.lines().filter(l -> !l.isBlank()).toList();
            fileCacheAt = now;
        }
        return fileCache;
    }

    @Override
    public String fileContent(String ref, String path) {
        return git("show", ref + ":" + path);
    }

    @Override
    public BlameInfo blame(String ref, String path, int line) {
        String out = git("blame", "--porcelain", "-L", line + "," + line, ref, "--", path);
        if (out == null) return null;
        String commit = null, author = null, summary = null, date = null;
        for (String l : out.lines().toList()) {
            if (commit == null && l.matches("^[0-9a-f]{40} .*")) commit = l.substring(0, 40);
            else if (l.startsWith("author ")) author = l.substring(7);
            else if (l.startsWith("author-time ")) date = Instant.ofEpochSecond(Long.parseLong(l.substring(12).trim())).toString();
            else if (l.startsWith("summary ")) summary = l.substring(8);
        }
        if (commit == null) return null;
        boolean uncommitted = commit.chars().allMatch(c -> c == '0');
        return new BlameInfo(commit, commit.substring(0, 7), uncommitted ? "Not committed yet" : author, null, date,
                summary, null, null, null);
    }

    @Override
    public List<CommitInfo> recentCommits(int hours) {
        String out = git("log", "--since=" + Math.max(1, Math.min(hours, 24 * 30)) + ".hours.ago",
                "-n", "20", "--date=iso-strict", "--pretty=format:%H%x1f%an%x1f%ad%x1f%s");
        List<CommitInfo> list = new ArrayList<>();
        if (out == null) return list;
        for (String l : out.lines().toList()) {
            String[] parts = l.split("\u001f", 4);
            if (parts.length == 4) list.add(new CommitInfo(parts[0].substring(0, 7), parts[1], parts[2], parts[3]));
        }
        return list;
    }

    private String git(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(repo.toString());
        cmd.add("-c");
        cmd.add("core.quotepath=off");
        cmd.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            p.getOutputStream().close();
            byte[] out = readCapped(p.getInputStream());
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? new String(out, StandardCharsets.UTF_8) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readCapped(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (bos.size() + n > MAX_OUTPUT) break;
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
