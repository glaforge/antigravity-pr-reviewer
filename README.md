# GitHub Pull Request Reviewer

An automated, in-depth, production-grade Pull Request Reviewer built with the **Antigravity Java SDK** (`io.github.glaforge.antigravity:antigravity-sdk-wrapper:0.2.18`), Gemini models, and **JBang**.

Designed to run in **GitHub Actions** as a zero-build composite action or workflow, and locally via the command line with standard Unix pipes.

---

## 🎯 Architecture & Design Philosophy

Rather than shipping compiled JARs or maintaining heavyweight build systems and GitHub client code, this tool embraces a clean separation of concerns and the **Unix philosophy**:

- **Zero-Build Java Scripting via JBang**: `PrReviewer.java` is a lean (~95 lines) single-file Java 25 source script declaring its dependencies (`//DEPS`) and JVM flags. No Maven, Gradle, or compilation steps required.
- **Diff Ingestion via Stdin or File**: Receives the git diff from standard input (`gh pr diff | jbang PrReviewer.java`) or as a file argument.
- **Structured Review Engine (Records + JSON)**: Enforces strict structured output modeled with Java 25 records (`Review` containing `body` markdown and line-specific `Comment` records). Outputs clean JSON to stdout.
- **Skill-Driven Persona via Antigravity SDK**: Registers the `pull-request-reviewer` skill directly via `addSkillPath()`, giving the agent its review persona, security guidelines, and comment schemas.
- **Offloaded Review Posting & Graceful Fallback**: Review submission is decoupled from Java and handled in `action.yml`. It uses `gh api` to post inline reviews with native ````suggestion` widgets, with automatic fallback to standard PR comments if inline lines cannot be placed.
- **Pure AI Review Engine with Airtight Security**:
  1. Inspects the codebase using **read-only tools** (`view_file`, `list_dir`, `grep_search`) against the workspace.
  2. Enforces an airtight **Read-Only Security Posture** with `Policies.denyAll()`: shell execution, file writes, and code modification are blocked.
  3. The LLM agent **never sees or holds a GitHub token** (only the outer runner's `gh` CLI has it).
- **Native GitHub Visualization**: Renders complete Markdown reports to `$GITHUB_STEP_SUMMARY` and structured JSON to stdout.

---

## 📂 Project Structure

```
github-pr-reviewer/
├── .github/
│   └── workflows/
│       └── pr-review.yml               # GitHub Actions workflow for testing the action
├── skills/
│   └── pull-request-reviewer/
│       └── SKILL.md                    # The PR review skill instructions
├── samples/
│   └── sample.diff                     # Sample test diff
├── action.yml                          # Composite GitHub Action descriptor
├── PrReviewer.java                     # Executable JBang script (Java 25+ JEP 512 compact source file)
├── simplelogger.properties             # SLF4J logging configuration (clean stderr output)
└── README.md
```

---

## 🚀 Using as a GitHub Action

You can consume this action in any repository's `.github/workflows/review.yml`:

```yaml
name: "AI Pull Request Reviewer"

on:
  pull_request:
    types: [opened, synchronize, reopened]

permissions:
  contents: read
  pull-requests: write

jobs:
  review:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout repository
        uses: actions/checkout@v4

      - name: Review Pull Request
        uses: glaforge/antigravity-pr-reviewer@v1
        with:
          gemini_api_key: ${{ secrets.GEMINI_API_KEY }}
          model_name: 'gemini-3.8-flash'
```

### Action Inputs

| Input | Description | Required | Default |
| :--- | :--- | :--- | :--- |
| `gemini_api_key` | Gemini API key for Antigravity AI review | **Yes** | - |
| `model_name` | Gemini model to use | No | `gemini-3.8-flash` |
| `post_comment` | Whether to post review to the pull request | No | `'true'` |

---

## 💻 Running Locally

Requires [JBang](https://www.jbang.dev/) (`curl -Ls https://sh.jbang.dev | bash` or `sdk install jbang`).

`PrReviewer.java` outputs structured JSON (`{ "body": "...", "comments": [...] }`). You can inspect the full JSON with `jq` or extract the markdown review body directly with `jq -r .body`.

### 1. Pipe git diff directly via stdin

```bash
export GEMINI_API_KEY="your-gemini-api-key"

# Pretty-print complete JSON output (summary + inline comments)
git diff main...HEAD | jbang PrReviewer.java | jq .

# View only the markdown summary review
git diff main...HEAD | jbang PrReviewer.java | jq -r .body
```

### 2. Review a local diff file

```bash
jbang PrReviewer.java samples/sample.diff | jq .
```

### 3. Review a live GitHub PR using the `gh` CLI

```bash
gh pr diff 42 | jbang PrReviewer.java | jq .
```

---

## 🧠 The `pull-request-reviewer` Skill

Located in `skills/pull-request-reviewer/SKILL.md`:

Enforces a high-signal, concise review structure:
1. **📋 Summary & Blast Radius**: High-level impact and blast radius assessment.
2. **🚨 Critical & High Severity Issues**: Bugs, SQL/Command injection, connection leaks, concurrency hazards, and API contract breaking changes with concrete code fixes.
3. **⚠️ Improvements & Suggestions**: Performance optimization, resource bounding, cache strategies.
4. **✅ Test Coverage & Verification**: Concrete missing test scenarios (injection payloads, negative flows, concurrency tests).
5. **🏁 Verdict**: Clear `APPROVE`, `REQUEST_CHANGES`, or `COMMENT` with a one-sentence rationale.
