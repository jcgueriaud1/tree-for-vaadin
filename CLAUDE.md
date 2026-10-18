# CLAUDE.md

Tree for Vaadin: a Vaadin add-on (Java 21, Vaadin 25.3, no Spring) and its Spring Boot demo, as a Maven reactor:

- `addon/`: the add-on, `org.vaadin.addons:tree-for-vaadin`, package `org.vaadin.addons.tree`.
  The `<tfv-tree>` Lit element is in `addon/src/main/resources/META-INF/resources/frontend/tfv-tree/`.
- `demo/`: the demo app, package `org.vaadin.addons.tree.demo`.

- Run: `./mvnw` at the root, then open http://localhost:8080
- Test: `./mvnw verify` (browserless tests, Lit tests with `@web/test-runner`, Playwright + axe-core tests against the demo)
- Lit tests only: `cd addon && npm test`
- `playwright.version` in `pom.xml` and `playwright` in `addon/package.json` must use the same Chromium build: bump them together.

## Agent skills

### Issue tracker

Issues are tracked in GitHub Issues for `jcgueriaud1/tree-for-vaadin` (via the `gh` CLI). See `docs/agents/issue-tracker.md`.

### Triage labels

Default vocabulary: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `GLOSSARY.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.
