# jiro — working notes

Continuous, method-level test impact analysis for Maven. Read [DESIGN.md](DESIGN.md) before
changing selection logic; the rules there are load-bearing and the order they apply in matters.

## Module boundaries that must not be crossed

- **`jiro-runtime` takes no dependencies, ever.** It is appended to the bootstrap classpath of the
  test JVM. Adding anything outside `java.base` breaks instrumented classes in loaders that cannot
  see it. This is not a style preference.
- **`jiro-agent` keeps ASM relocated.** It shares a classloader with the application under test.
  The `"io/github/eegn/jiro/runtime/CoverageRecorder"` literal in `CoverageTransformer` must stay a
  literal — deriving it from `.class` would let the shade relocation rewrite it.
- **`jiro-core` never imports the wire protocol.** Analysis does not know how the fork is spoken to.

## Selection changes are correctness changes

Over-selection costs time. Under-selection tells a developer their broken code passes. Any change
to `TestSelector`, `Fingerprinter` or `ClassFirewall` must say which direction it errs in, and it
must err toward running too much.

## Verifying a change

Unit tests cover `Fingerprinter`, `TestSelector` and `JsonReporter`. They cannot catch a broken
probe, a classloader mistake or a protocol change, so anything touching the agent, the runner or
the fork needs a manual end-to-end run against a real Spring Boot project: start `jiro:dev`, make a
comment-only edit (expect zero tests), then break an asserted value (expect red, with the failing
assertion's file and line in `status.json`).

## Agent workflow in a repo running jiro

Do not run `mvn test` or `mvn compile`. If `mvn jiro:dev` is running, edit sources and then:

```
bin/jiro-await --since-now --timeout 30
```

Exit code is the verdict: `0` green, `1` red, `2` compile error, `3` timeout, `4` no session.
Read `target/jiro/status.json` for the failure details.

Never read `status.json` without the `--since` guard. It always holds *some* verdict, and reading it
straight after an edit returns the **previous** cycle's answer — which looks exactly like the code
you just broke still passing.
