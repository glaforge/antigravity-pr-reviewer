///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS io.github.glaforge.antigravity:antigravity-sdk-wrapper:0.2.18
//DEPS org.slf4j:slf4j-simple:2.0.18
//FILES skills/pull-request-reviewer/SKILL.md
//FILES simplelogger.properties
//JAVA_OPTIONS --sun-misc-unsafe-memory-access=allow

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

Logger log = LoggerFactory.getLogger("PrReviewer");
String SKILL_PATH = "skills/pull-request-reviewer/SKILL.md";

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

        String review = reviewDiff(diff);
        IO.println(review);

        // Automatically append to the GitHub Step Summary if running inside GitHub Actions
        String stepSummary = System.getenv("GITHUB_STEP_SUMMARY");
        if (stepSummary != null && !stepSummary.isBlank()) {
            Files.writeString(
                    Path.of(stepSummary),
                    """
                    ## 🤖 AI Pull Request Review

                    %s

                    """.formatted(review),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        }
    } catch (Exception e) {
        log.error("Review failed: {}", e.getMessage(), e);
        System.exit(1);
    }
}

String reviewDiff(String diff) throws Exception {
    String skillInstructions = loadSkillInstructions();

    String model = System.getenv().getOrDefault("MODEL_NAME", "gemini-3.8-flash");
    String prTitle = System.getenv().getOrDefault("PR_TITLE", "");
    String prNumber = System.getenv().getOrDefault("PR_NUMBER", "");
    String repo = System.getenv().getOrDefault("GITHUB_REPOSITORY", System.getenv().getOrDefault("GH_REPO", ""));

    Path workspaceDir = prepareWorkspace(repo, prNumber);
    log.info("Using workspace directory: {}", workspaceDir);

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

            %s### Pull Request Diff
            ```diff
            %s
            ```
            """.formatted(workspaceDir, context, diff.trim());

    AgentConfig config = AgentConfig.builder()
            .modelName(model)
            .instructions("""
                    You are a Principal Software Engineer acting as a GitHub Pull Request Reviewer.

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

        return response.text();
    }
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
