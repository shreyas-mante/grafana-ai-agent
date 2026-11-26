package com.pulseagent.code;

import java.util.List;

public final class CodeModels {
    private CodeModels() {}

    public record BlameInfo(String commit, String shortCommit, String author, String authorLogin, String date,
                            String summary, Integer prNumber, String prTitle, String prUrl) {}

    public record CommitInfo(String sha, String author, String date, String message) {}

    public record CodeContext(String provider, String file, int line, String ref, int startLine,
                              List<String> lines, BlameInfo blame, String note) {}

    public interface CodeProvider {
        String name();

        List<String> listFiles(String ref);

        /** Returns null if the file does not exist at that ref. */
        String fileContent(String ref, String path);

        BlameInfo blame(String ref, String path, int line);

        List<CommitInfo> recentCommits(int hours);
    }
}
