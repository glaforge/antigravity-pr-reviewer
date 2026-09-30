///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS io.github.glaforge.antigravity:antigravity-sdk-wrapper:0.2.18
//DEPS org.slf4j:slf4j-simple:2.0.18
//DEPS com.fasterxml.jackson.core:jackson-databind:2.18.3
//FILES skills/pull-request-reviewer/SKILL.md
//FILES simplelogger.properties
//JAVA_OPTIONS --sun-misc-unsafe-memory-access=allow

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.glaforge.antigravity.Agent;
import io.github.glaforge.antigravity.AgentConfig;
import io.github.glaforge.antigravity.AgentResponse;
import io.github.glaforge.antigravity.Policies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

Logger log = LoggerFactory.getLogger("PrReviewer");
String SKILL_PATH = "skills/pull-request-reviewer/SKILL.md";
ObjectMapper mapper = new ObjectMapper();

@JsonIgnoreProperties(ignoreUnknown = true)
record InlineComment(
        String path,
        int line,
        Integer startLine,
        String side,
        String severity,
        String commentText,
        String codeSuggestion
) {
    public String effectiveSide() {
        return (side != null && !side.isBlank()) ? side.toUpperCase() : "RIGHT";
    }

    public String effectiveSeverity() {
        return (severity != null && !severity.isBlank()) ? severity.toUpperCase() : "MEDIUM";
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
record ReviewResult(
        String summary,
        String verdict,
        List<String> generalFeedback,
        List<InlineComment> comments
) {
    public List<String> effectiveFeedback() {
        return generalFeedback != null ? generalFeedback : List.of();
    }

    public List<InlineComment> effectiveComments() {
        return comments != null ? comments : List.of();
    }

    public String effectiveVerdict() {
        return (verdict != null && !verdict.isBlank()) ? verdict.toUpperCase() : "COMMENT";
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
record GhReviewComment(
        String path,
        int line,
        Integer start_line,
        String side,
        String start_side,
        String body
) {}

@JsonInclude(JsonInclude.Include.NON_NULL)
record GhReviewRequest(
        String commit_id,
        String body,
        String event,
        List<GhReviewComment> comments
) {}

record DiffLineKey(String path, int line, String side) {}

void main(String[] args) {
    try {
        String diff;
        if (args.length > 0 && !args[0].isBlank()) {
            diff = Files.readString(Path.of(args[0]));
        } else {
            diff = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        }

        if (diff.isBlank()) {
            System.err.println("No diff provided. Pass a diff file as an argument or pipe via stdin.");
            System.exit(1);
        }

        String repo = System.getenv().getOrDefault("GITHUB_REPOSITORY", System.getenv().getOrDefault("GH_REPO", ""));
        String prNumber = System.getenv().getOrDefault("PR_NUMBER", "");
        String postComment = System.getenv().getOrDefault("POST_COMMENT", "true");
        String reviewMode = System.getenv().getOrDefault("REVIEW_MODE", "inline");
        String eventType = System.getenv().getOrDefault("REVIEW_EVENT", "COMMENT");
        String dryRun = System.getenv().getOrDefault("DRY_RUN", "false");

        Path workspaceDir = prepareWorkspace(repo, prNumber);
        log.info("Using workspace directory: {}", workspaceDir);

        Set<DiffLineKey> validHunkLines = parseDiffHunks(diff);
        log.info("Parsed {} valid diff hunk lines across files in PR diff", validHunkLines.size());

        ReviewResult reviewResult = reviewDiff(diff, workspaceDir);

        String fullMarkdown = renderFullMarkdown(reviewResult);
        IO.println(fullMarkdown);

        // Write review.md file in current working directory
        try {
            Files.writeString(Path.of("review.md"), fullMarkdown);
        } catch (Exception ignored) {}

        // Append to GitHub Step Summary if running in GitHub Actions
        String stepSummary = System.getenv("GITHUB_STEP_SUMMARY");
        if (stepSummary != null && !stepSummary.isBlank()) {
            Files.writeString(
                    Path.of(stepSummary),
                    fullMarkdown + "\n\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        }

        // Post review to GitHub if enabled and not a dry run
        if ("true".equalsIgnoreCase(postComment) && !"true".equalsIgnoreCase(dryRun) && !repo.isBlank() && !prNumber.isBlank()) {
            String headSha = resolveHeadSha(workspaceDir);
            submitReview(repo, prNumber, headSha, reviewResult, validHunkLines, reviewMode, eventType, fullMarkdown);
        }
    } catch (Exception e) {
        log.error("Review failed: {}", e.getMessage(), e);
        System.exit(1);
    }
}

ReviewResult reviewDiff(String diff, Path workspaceDir) throws Exception {
    String skillInstructions = loadSkillInstructions();

    String model = System.getenv().getOrDefault("MODEL_NAME", "gemini-3.8-flash");
    String prTitle = System.getenv().getOrDefault("PR_TITLE", "");
    String prNumber = System.getenv().getOrDefault("PR_NUMBER", "");
    String repo = System.getenv().getOrDefault("GITHUB_REPOSITORY", System.getenv().getOrDefault("GH_REPO", ""));

    String context = (prTitle.isBlank() && prNumber.isBlank() && repo.isBlank()) ? "" : """
            ### Pull Request Context
            %s%s%s- **Workspace Directory**: %s
            """.formatted(
            repo.isBlank() ? "" : "- **Repository**: " + repo + "\n",
            prNumber.isBlank() ? "" : "- **PR Number**: #" + prNumber + "\n",
            prTitle.isBlank() ? "" : "- **Title**: " + prTitle + "\n",
            workspaceDir);

    String prompt = """
            Please perform an in-depth technical code review of this pull request according to the `pull-request-reviewer` skill.

            The repository is cloned and checked out at the PR state in your workspace directory: `%s`.
            Use `view_file`, `list_dir`, and `grep_search` to inspect callers, types, interfaces, tests, and configuration files across the codebase to ensure an accurate, context-aware review.

            Output your review STRICTLY as a JSON object adhering to the schema described in the skill.

            %s### Pull Request Diff
            ```diff
            %s
            ```
            """.formatted(workspaceDir, context, diff.trim());

    AgentConfig config = AgentConfig.builder()
            .modelName(model)
            .instructions("""
                    You are a Principal Software Engineer acting as an automated GitHub Pull Request Reviewer.

                    Apply the following skill instructions for all reviews:

                    %s

                    The repository is pre-cloned in your workspace. Use `view_file`, `list_dir`, and `grep_search` to inspect broader codebase context beyond the diff.
                    Modifying repository files is strictly forbidden.
                    """.formatted(skillInstructions))
            .addPolicy(Policies.allowTools("view_file", "list_dir", "grep_search"))
            .addPolicy(Policies.denyAll("Only read-only codebase inspection (view_file, list_dir, grep_search) is allowed."))
            .addWorkspace(workspaceDir.toString())
            .build();

    try (Agent agent = new Agent(config)) {
        CompletableFuture<AgentResponse> future = agent.chatStream(prompt, chunk -> {});
        AgentResponse response = future.get(180, TimeUnit.SECONDS);

        if (response.usageMetadata() != null) {
            log.info("Token usage - Prompt: {}, Output: {}, Total: {}",
                    response.usageMetadata().promptTokenCount(),
                    response.usageMetadata().candidatesTokenCount(),
                    response.usageMetadata().totalTokenCount());
        }

        return parseReviewResult(response.text());
    }
}

ReviewResult parseReviewResult(String rawText) {
    String json = extractJson(rawText);
    try {
        return mapper.readValue(json, ReviewResult.class);
    } catch (Exception e) {
        log.warn("Failed to deserialize structured JSON review ({}). Falling back to text summary.", e.getMessage());
        return new ReviewResult(
                rawText.trim(),
                "COMMENT",
                List.of(),
                List.of()
        );
    }
}

String extractJson(String text) {
    if (text == null || text.isBlank()) {
        return "{}";
    }
    Pattern fencePattern = Pattern.compile("```(?:json)?\\s*\\n([\\s\\S]*?)\\n```", Pattern.CASE_INSENSITIVE);
    Matcher matcher = fencePattern.matcher(text);
    if (matcher.find()) {
        return matcher.group(1).trim();
    }
    int start = text.indexOf('{');
    int end = text.lastIndexOf('}');
    if (start != -1 && end != -1 && end > start) {
        return text.substring(start, end + 1).trim();
    }
    return text.trim();
}

Set<DiffLineKey> parseDiffHunks(String diff) {
    Set<DiffLineKey> validKeys = new HashSet<>();
    String currentPath = null;
    Pattern filePattern = Pattern.compile("^\\+\\+\\+\\s+b/(.*)$");
    Pattern hunkPattern = Pattern.compile("^@@\\s+-(\\d+)(?:,(\\d+))?\\s+\\+(\\d+)(?:,(\\d+))?\\s+@@");

    for (String line : diff.split("\r?\n")) {
        Matcher fileMatcher = filePattern.matcher(line);
        if (fileMatcher.find()) {
            currentPath = fileMatcher.group(1).trim();
            continue;
        }

        if (currentPath != null) {
            Matcher hunkMatcher = hunkPattern.matcher(line);
            if (hunkMatcher.find()) {
                int leftStart = Integer.parseInt(hunkMatcher.group(1));
                int leftCount = hunkMatcher.group(2) != null ? Integer.parseInt(hunkMatcher.group(2)) : 1;
                for (int i = 0; i < leftCount; i++) {
                    validKeys.add(new DiffLineKey(currentPath, leftStart + i, "LEFT"));
                }

                int rightStart = Integer.parseInt(hunkMatcher.group(3));
                int rightCount = hunkMatcher.group(4) != null ? Integer.parseInt(hunkMatcher.group(4)) : 1;
                for (int i = 0; i < rightCount; i++) {
                    validKeys.add(new DiffLineKey(currentPath, rightStart + i, "RIGHT"));
                }
            }
        }
    }
    return validKeys;
}

String resolveHeadSha(Path workspaceDir) {
    String headSha = System.getenv("HEAD_SHA");
    if (headSha != null && !headSha.isBlank()) {
        return headSha.trim();
    }
    headSha = System.getenv("COMMIT_ID");
    if (headSha != null && !headSha.isBlank()) {
        return headSha.trim();
    }
    if (workspaceDir != null) {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .directory(workspaceDir.toFile())
                    .start();
            String sha = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (p.waitFor() == 0 && !sha.isBlank()) {
                return sha;
            }
        } catch (Exception ignored) {}
    }
    return null;
}

void submitReview(
        String repo,
        String prNumber,
        String headSha,
        ReviewResult result,
        Set<DiffLineKey> validHunkLines,
        String reviewMode,
        String eventType,
        String fullMarkdown
) {
    // If comment_only mode requested, or headSha is unavailable, post a general PR issue comment
    if ("comment_only".equalsIgnoreCase(reviewMode) || headSha == null || headSha.isBlank()) {
        if (headSha == null || headSha.isBlank()) {
            log.info("HEAD SHA not available. Falling back to issue comment.");
        }
        postIssueComment(repo, prNumber, fullMarkdown);
        return;
    }

    try {
        GhReviewRequest ghRequest = buildGhReviewRequest(headSha, result, validHunkLines, eventType);
        String requestJson = mapper.writeValueAsString(ghRequest);

        log.info("Submitting GitHub Pull Request Review via gh api ({} inline comment(s))...", ghRequest.comments().size());

        ProcessBuilder pb = new ProcessBuilder(
                "gh", "api",
                "--method", "POST",
                "/repos/" + repo + "/pulls/" + prNumber + "/reviews",
                "--input", "-"
        );
        Process p = pb.start();
        try (var out = p.getOutputStream()) {
            out.write(requestJson.getBytes(StandardCharsets.UTF_8));
        }

        int exitCode = p.waitFor();
        String errorOutput = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

        if (exitCode == 0) {
            log.info("GitHub Pull Request Review submitted successfully!");
        } else {
            log.warn("gh api review submission failed (exit {}): {}. Falling back to issue comment.", exitCode, errorOutput);
            postIssueComment(repo, prNumber, fullMarkdown);
        }
    } catch (Exception e) {
        log.warn("Exception during gh api review submission: {}. Falling back to issue comment.", e.getMessage());
        postIssueComment(repo, prNumber, fullMarkdown);
    }
}

GhReviewRequest buildGhReviewRequest(
        String commitId,
        ReviewResult result,
        Set<DiffLineKey> validHunkLines,
        String eventType
) {
    List<GhReviewComment> ghComments = new ArrayList<>();
    List<String> deferredFeedback = new ArrayList<>(result.effectiveFeedback());

    for (InlineComment c : result.effectiveComments()) {
        String side = c.effectiveSide();
        DiffLineKey key = new DiffLineKey(c.path(), c.line(), side);

        // Guardrail: if line is not within the PR diff hunks, demote to general feedback to prevent API rejection
        if (!validHunkLines.isEmpty() && !validHunkLines.contains(key)) {
            deferredFeedback.add("📌 **%s** (line %d, outside diff): %s".formatted(c.path(), c.line(), c.commentText()));
            continue;
        }

        String icon = switch (c.effectiveSeverity()) {
            case "CRITICAL" -> "🔴";
            case "HIGH" -> "🟠";
            case "MEDIUM" -> "🟡";
            default -> "🟢";
        };

        StringBuilder body = new StringBuilder();
        body.append(icon).append(" **").append(c.effectiveSeverity()).append("**: ").append(c.commentText().trim());
        if (c.codeSuggestion() != null && !c.codeSuggestion().isBlank()) {
            body.append("\n\n```suggestion\n").append(c.codeSuggestion().stripTrailing()).append("\n```");
        }

        ghComments.add(new GhReviewComment(
                c.path(),
                c.line(),
                c.startLine(),
                side,
                c.startLine() != null ? side : null,
                body.toString()
        ));
    }

    String summaryMarkdown = renderReviewSummaryBody(result.summary(), result.effectiveVerdict(), deferredFeedback);
    String event = (eventType != null && !eventType.isBlank()) ? eventType : "COMMENT";

    return new GhReviewRequest(commitId, summaryMarkdown, event, ghComments);
}

void postIssueComment(String repo, String prNumber, String markdownBody) {
    try {
        log.info("Posting review as standard PR comment via gh pr comment...");
        ProcessBuilder pb = new ProcessBuilder(
                "gh", "pr", "comment", prNumber,
                "--repo", repo,
                "--body", markdownBody
        );
        pb.inheritIO();
        int code = pb.start().waitFor();
        if (code == 0) {
            log.info("PR comment posted successfully.");
        } else {
            log.error("Failed to post PR comment (exit code {})", code);
        }
    } catch (Exception e) {
        log.error("Failed to post issue comment: {}", e.getMessage(), e);
    }
}

String renderReviewSummaryBody(String summary, String verdict, List<String> feedback) {
    StringBuilder sb = new StringBuilder();
    sb.append("## 🤖 AI Pull Request Review\n\n");
    sb.append("**Verdict:** `").append(verdict).append("`\n\n");
    sb.append("### 📋 Summary\n\n").append(summary.trim()).append("\n\n");

    if (!feedback.isEmpty()) {
        sb.append("### 💡 General Feedback & Observations\n\n");
        for (String item : feedback) {
            sb.append("- ").append(item.trim()).append("\n");
        }
        sb.append("\n");
    }

    return sb.toString();
}

String renderFullMarkdown(ReviewResult result) {
    StringBuilder sb = new StringBuilder();
    sb.append("## 🤖 AI Pull Request Review\n\n");
    sb.append("**Verdict:** `").append(result.effectiveVerdict()).append("`\n\n");
    sb.append("### 📋 Summary\n\n").append(result.summary().trim()).append("\n\n");

    if (!result.effectiveFeedback().isEmpty()) {
        sb.append("### 💡 General Feedback\n\n");
        for (String item : result.effectiveFeedback()) {
            sb.append("- ").append(item.trim()).append("\n");
        }
        sb.append("\n");
    }

    List<InlineComment> comments = result.effectiveComments();
    if (!comments.isEmpty()) {
        sb.append("### 🔍 Inline Comments & Code Suggestions (").append(comments.size()).append(")\n\n");
        for (InlineComment c : comments) {
            String icon = switch (c.effectiveSeverity()) {
                case "CRITICAL" -> "🔴";
                case "HIGH" -> "🟠";
                case "MEDIUM" -> "🟡";
                default -> "🟢";
            };
            sb.append("#### ").append(icon).append(" `").append(c.path()).append(":").append(c.line()).append("` (").append(c.effectiveSeverity()).append(")\n\n");
            sb.append(c.commentText().trim()).append("\n\n");
            if (c.codeSuggestion() != null && !c.codeSuggestion().isBlank()) {
                sb.append("```suggestion\n").append(c.codeSuggestion().stripTrailing()).append("\n```\n\n");
            }
        }
    } else {
        sb.append("✅ *No inline code issues detected.*\n\n");
    }

    return sb.toString();
}

Path prepareWorkspace(String repo, String prNumber) {
    String githubWorkspace = System.getenv("GITHUB_WORKSPACE");
    if (githubWorkspace != null && !githubWorkspace.isBlank()) {
        Path path = Path.of(githubWorkspace).toAbsolutePath().normalize();
        if (Files.exists(path)) {
            return path;
        }
    }

    Path currentDir = Path.of(".").toAbsolutePath().normalize();
    if (Files.exists(currentDir.resolve(".git"))) {
        return currentDir;
    }

    if (repo != null && !repo.isBlank()) {
        try {
            Path tempDir = Files.createTempDirectory("pr-review-" + repo.replace('/', '_') + "-");
            log.info("Cloning repository {} into temporary workspace {}...", repo, tempDir);
            ProcessBuilder clonePb = new ProcessBuilder("gh", "repo", "clone", repo, tempDir.toString());
            clonePb.inheritIO();
            if (clonePb.start().waitFor() == 0) {
                if (prNumber != null && !prNumber.isBlank()) {
                    log.info("Checking out PR #{}...", prNumber);
                    ProcessBuilder coPb = new ProcessBuilder("gh", "pr", "checkout", prNumber);
                    coPb.directory(tempDir.toFile());
                    coPb.inheritIO();
                    coPb.start().waitFor();
                }
                return tempDir;
            }
        } catch (Exception e) {
            log.warn("Could not clone repository beforehand: {}", e.getMessage());
        }
    }

    return currentDir;
}

String loadSkillInstructions() throws IOException {
    Path local = Path.of(SKILL_PATH);
    if (Files.exists(local)) {
        return Files.readString(local);
    }
    String actionPath = System.getenv("GITHUB_ACTION_PATH");
    if (actionPath != null && !actionPath.isBlank()) {
        Path fromAction = Path.of(actionPath, SKILL_PATH);
        if (Files.exists(fromAction)) {
            return Files.readString(fromAction);
        }
    }
    try (var is = getClass().getResourceAsStream("/" + SKILL_PATH)) {
        if (is != null) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    throw new IllegalStateException("Skill 'pull-request-reviewer' not found at " + SKILL_PATH);
}
