///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS io.github.glaforge.antigravity:antigravity-sdk-wrapper:0.2.18
//DEPS com.fasterxml.jackson.core:jackson-databind:2.18.3
//FILES skills/pull-request-reviewer/SKILL.md
//JAVA_OPTIONS --sun-misc-unsafe-memory-access=allow

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.glaforge.antigravity.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.CompletableFuture.completedFuture;

record Review(String body, List<Comment> comments) {
    record Comment(String path, int line, String body) {}
    public List<Comment> comments() {
        return comments != null ? comments : List.of();
    }
}

void main(String[] args) {
    try {
        String diff = (args.length > 0 && !args[0].isBlank())
                ? Files.readString(Path.of(args[0]))
                : new String(System.in.readAllBytes(), UTF_8);

        if (diff.isBlank()) {
            System.err.println("Usage: jbang PrReviewer.java <diff-file>");
            System.exit(1);
        }

        Review review = reviewDiff(diff);

        new ObjectMapper()
                .writerWithDefaultPrettyPrinter()
                .writeValue(System.out, review);
    } catch (Exception e) {
        System.err.println("Review failed: " + e.getMessage());
        System.exit(1);
    }
}

Review reviewDiff(String diff) throws Exception {
    String skill = new String(
            getClass().getResourceAsStream("/SKILL.md").readAllBytes(),
            UTF_8
    );
    String model = System.getenv().getOrDefault(
            "MODEL_NAME", "gemini-3.8-flash"
    );

    CapabilitiesConfig caps = CapabilitiesConfig.builder()
            .enableViewFile(true)
            .enableListDir(true)
            .enableGrepSearch(true)
            .build();

    AgentConfig config = AgentConfig.builder()
            .modelName(model)
            .capabilities(caps)
            .finishToolSchema(Review.class)
            .instructions("You are a Principal Software Engineer.\n\n" + skill)
            .addPolicy(Policies.allowTools(
                    BuiltinTools.VIEW_FILE.getValue(),
                    BuiltinTools.LIST_DIR.getValue(),
                    BuiltinTools.SEARCH_DIR.getValue()
            ))
            .addPolicy(Policies.denyAll(
                    "Only read-only codebase inspection is allowed."
            ))
            .addOnToolErrorHook((call, err, ctx) ->
                    completedFuture("Note: " + err.getMessage() + ". Continue.")
            )
            .addWorkspace(".")
            .build();

    String prompt = """
            Perform an in-depth technical code review of this PR.
            Inspect codebase files using tools to verify callers and types.
            Return your review with markdown 'body' and line 'comments'.

            ### Pull Request Diff
            ```diff
            %s
            ```
            """.formatted(diff.trim());

    try (Agent agent = new Agent(config)) {
        AgentResponse res = agent.chat(prompt).get();
        Review review = res.getStructuredOutput(Review.class);
        return (review != null) ? review : new Review(res.text(), List.of());
    }
}
