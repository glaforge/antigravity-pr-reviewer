///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS io.github.glaforge.antigravity:antigravity-sdk-wrapper:0.2.18
//DEPS com.fasterxml.jackson.core:jackson-databind:2.18.3
//DEPS org.slf4j:slf4j-simple:2.0.18
//FILES skills/pull-request-reviewer/SKILL.md
//FILES simplelogger.properties
//JAVA_OPTIONS --sun-misc-unsafe-memory-access=allow

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.glaforge.antigravity.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

Logger log = LoggerFactory.getLogger("PrReviewer");
ObjectMapper mapper = new ObjectMapper();

record Review(String body, List<Comment> comments) {
    record Comment(String path, int line, String body) {}
    public List<Comment> comments() { return comments != null ? comments : List.of(); }
}

void main(String[] args) {
    try {
        String diff = (args.length > 0 && !args[0].isBlank())
                ? Files.readString(Path.of(args[0]))
                : new String(System.in.readAllBytes(), StandardCharsets.UTF_8);

        if (diff.isBlank()) {
            System.err.println("No diff provided. Pass a diff file as an argument or pipe via stdin.");
            System.exit(1);
        }

        String repo = System.getenv().getOrDefault("GITHUB_REPOSITORY", System.getenv().getOrDefault("GH_REPO", ""));
        String pr = System.getenv().getOrDefault("PR_NUMBER", "");
        String sha = System.getenv().getOrDefault("HEAD_SHA", System.getenv().getOrDefault("COMMIT_ID", ""));
        String post = System.getenv().getOrDefault("POST_COMMENT", "true");
        String dryRun = System.getenv().getOrDefault("DRY_RUN", "false");

        Review review = reviewDiff(diff);
        IO.println(review.body());

        // Append to GitHub Step Summary if running inside GitHub Actions
        String summaryPath = System.getenv("GITHUB_STEP_SUMMARY");
        if (summaryPath != null && !summaryPath.isBlank()) {
            Files.writeString(Path.of(summaryPath), review.body() + "\n\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        // Post review to GitHub if enabled and not in dry-run mode
        if ("true".equalsIgnoreCase(post) && !"true".equalsIgnoreCase(dryRun) && !repo.isBlank() && !pr.isBlank()) {
            postReview(repo, pr, sha, review);
        }
    } catch (Exception e) {
        log.error("Review failed: {}", e.getMessage(), e);
        System.exit(1);
    }
}

Review reviewDiff(String diff) throws Exception {
    String skill = loadSkillInstructions();
    String model = System.getenv().getOrDefault("MODEL_NAME", "gemini-3.8-flash");

    CapabilitiesConfig caps = CapabilitiesConfig.builder()
            .enableViewFile(true)
            .enableListDir(true)
            .enableGrepSearch(true)
            .enableShell(false)
            .enableWriteFile(false)
            .enableFileEdit(false)
            .enableSubagents(false)
            .build();

    AgentConfig config = AgentConfig.builder()
            .modelName(model)
            .capabilities(caps)
            .instructions("You are a Principal Software Engineer acting as a GitHub PR Reviewer.\n\n" + skill)
            .addPolicy(Policies.allowTools(BuiltinTools.VIEW_FILE.getValue(), BuiltinTools.LIST_DIR.getValue(), BuiltinTools.SEARCH_DIR.getValue()))
            .addPolicy(Policies.denyAll("Only read-only codebase inspection is allowed."))
            .addWorkspace(".")
            .build();

    String prompt = """
            Perform an in-depth technical code review of this pull request according to the `pull-request-reviewer` skill.
            Inspect the codebase using `view_file`, `list_directory`, and `search_directory` to verify callers and types.
            Output your review STRICTLY as a JSON object adhering to the schema described in the skill.

            ### Pull Request Diff
            ```diff
            %s
            ```
            """.formatted(diff.trim());

    try (Agent agent = new Agent(config)) {
        String responseText = agent.chatStream(prompt, chunk -> {}).get().text();
        return extractReview(responseText);
    }
}

void postReview(String repo, String pr, String sha, Review review) {
    try {
        // Attempt atomic Pull Request Review with inline comments if commit SHA is available
        if (sha != null && !sha.isBlank() && !review.comments().isEmpty()) {
            log.info("Submitting GitHub review with {} inline comments via gh api...", review.comments().size());
            Map<String, Object> payload = Map.of(
                    "commit_id", sha.trim(),
                    "event", "COMMENT",
                    "body", review.body(),
                    "comments", review.comments()
            );
            Process p = new ProcessBuilder("gh", "api", "--method", "POST", "/repos/" + repo + "/pulls/" + pr + "/reviews", "--input", "-")
                    .start();
            p.getOutputStream().write(mapper.writeValueAsBytes(payload));
            p.getOutputStream().close();
            if (p.waitFor() == 0) {
                log.info("GitHub review submitted successfully.");
                return;
            }
            log.warn("gh api review submission failed. Falling back to PR comment.");
        }
        // Fallback to standard PR issue comment
        log.info("Posting review as standard PR comment...");
        new ProcessBuilder("gh", "pr", "comment", pr, "--repo", repo, "--body", review.body()).inheritIO().start().waitFor();
    } catch (Exception e) {
        log.warn("Could not post review: {}", e.getMessage());
    }
}

Review extractReview(String text) {
    try {
        int start = text.indexOf('{'), end = text.lastIndexOf('}');
        String json = (start != -1 && end > start) ? text.substring(start, end + 1) : text.trim();
        return mapper.readValue(json, Review.class);
    } catch (Exception e) {
        log.warn("Could not parse JSON review from model. Using raw text as summary.");
        return new Review(text.trim(), List.of());
    }
}

String loadSkillInstructions() throws IOException {
    Path local = Path.of("skills/pull-request-reviewer/SKILL.md");
    if (Files.exists(local)) return Files.readString(local);
    String actionPath = System.getenv("GITHUB_ACTION_PATH");
    if (actionPath != null && !actionPath.isBlank()) {
        Path fromAction = Path.of(actionPath, "skills/pull-request-reviewer/SKILL.md");
        if (Files.exists(fromAction)) return Files.readString(fromAction);
    }
    try (var is = getClass().getResourceAsStream("/skills/pull-request-reviewer/SKILL.md")) {
        if (is != null) return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }
    throw new IllegalStateException("Skill 'pull-request-reviewer' not found");
}
