package com.pulseagent.code;

import java.util.List;

public final class CodeModels {
    private CodeModels() {}

    public record BlameInfo(String commit, String shortCommit, String author, String authorLogin, String date,
                            String summary, Integer prNumber, String prTitle, String prUrl) {}

    public record CommitInfo(String sha, String author, String date, String message) {}

    /**
     * lineCommits: short commit SHA for each snippet line. recentChanges: distinct commits that touched
     * the snippet, newest first, so a nearby changed line (often the real cause) is visible.
     */
    public record CodeContext(String provider, String file, int line, String ref, int startLine,
                              List<String> lines, BlameInfo blame, List<String> lineCommits,
                              List<BlameInfo> recentChanges, String note) {}

    public interface CodeProvider {
        String name();

        List<String> listFiles(String ref);

        /** Returns null if the file does not exist at that ref. */
        String fileContent(String ref, String path);

        /** Blame for lines start..end inclusive, keyed by line number. */
        java.util.Map<Integer, BlameInfo> blameRange(String ref, String path, int start, int end);

        default BlameInfo blame(String ref, String path, int line) {
            return blameRange(ref, path, line, line).get(line);
        }

        List<CommitInfo> recentCommits(int hours);
    }
}
