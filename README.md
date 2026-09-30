# GitHub Pull Request Reviewer

An automated, in-depth, production-grade Pull Request Reviewer built with the **Antigravity Java SDK** (`io.github.glaforge.antigravity:antigravity-sdk-wrapper:0.2.18`), Gemini models, and **JBang**.

Designed to run in **GitHub Actions** as a zero-build composite action or workflow, and locally via the command line with standard Unix pipes.

---

## 🎯 Architecture & Design Philosophy

Rather than shipping compiled JARs or maintaining heavyweight build systems and GitHub client code, this tool embraces the **Unix philosophy**:

- **Zero-Build Java Scripting via JBang**: `PrReviewer.java` is a standalone script declaring its own dependencies (`//DEPS`), file mounts (`//FILES`), and JVM flags. No Maven, Gradle, or compilation steps required.
- **Diff Ingestion via Stdin or File**: Receives the git diff from standard input (`gh pr diff | jbang PrReviewer.java`) or as a file argument.
- **Pure AI Review Engine**: Focuses entirely on what the Antigravity SDK does best:
  1. Resolves the `pull-request-reviewer` skill instructions.
  2. Inspects the codebase using **read-only tools** (`view_file`, `list_dir`, `grep_search`) against the pre-cloned workspace.
  3. Enforces an airtight **Read-Only Security Posture** with `Policies.denyAll()`: shell execution and file modification are blocked.
  4. Generates a concise, direct, practical, and in-depth technical code review.
- **Native GitHub Integration**: 
  - Standard output and `$GITHUB_STEP_SUMMARY` for native GitHub Actions UI visualization.
  - The standard GitHub CLI (`gh pr comment --body-file review.md`) handles posting to PRs using the workflow's built-in `GITHUB_TOKEN`.

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
| `post_comment` | Whether to post review as a comment on the PR | No | `'true'` |

---

## 💻 Running Locally

Requires [JBang](https://www.jbang.dev/) (`curl -Ls https://sh.jbang.dev | bash` or `sdk install jbang`).

### 1. Pipe git diff directly via stdin

```bash
export GEMINI_API_KEY="your-gemini-api-key"

git diff main...HEAD | jbang PrReviewer.java
```

### 2. Review a local diff file

```bash
jbang PrReviewer.java samples/sample.diff
```

### 3. Review a live GitHub PR using the `gh` CLI

```bash
gh pr diff 42 | jbang PrReviewer.java
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
