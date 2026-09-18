# jiro

Continuous, method-level test impact analysis for Maven projects.

Start it once and leave it running:

```
mvn jiro:dev
```

From then on, saving a `.java` file recompiles only that file and runs only the tests that the
change can actually affect — usually a handful, usually in well under a second.

```
[jiro] watching 42 directories, status at target/jiro/status.json
[jiro] building coverage baseline, running the full suite once
[jiro] baseline complete: 318 tests, 2841 methods indexed
[jiro] compiled in 47ms, ran 3 test(s), 0 failed — 1 method(s), 0 ABI change(s), 0 added, 0 removed
[jiro] compiled in 41ms, no tests to run (no observable change)
```

That second-to-last line is the normal case. The last one is a comment-only edit: jiro compares
normalised bytecode, so reformatting, renaming a local or moving a method selects nothing at all.

## Why this exists

Quarkus has continuous testing and it is genuinely good, but it only works if your application is
a Quarkus application. The general-purpose equivalents are all either abandoned (Ekstazi, STARTS,
Infinitest), commercial (Develocity Predictive Test Selection), or IDE-locked. There is no
maintained, framework-agnostic, open-source Maven plugin that does this. jiro is that.

## How it decides what to run

Two mechanisms, because neither is sufficient alone.

**Recorded coverage, for changed method bodies.** A JVM agent puts one probe at the entry of every
application method. As the suite runs, jiro records which methods each *test method* actually
entered, and inverts it. A changed method body then selects exactly the tests that ran it — and
because this is observed rather than inferred, it sees straight through Spring wiring, JPA
repository proxies, Mockito, `ServiceLoader` and reflection, all of which defeat a static call
graph.

**A static class firewall, for everything coverage cannot answer.** Recorded coverage says nothing
about a test that has never run, and nothing about a change to a *signature*, supertype or
annotation, which can alter the meaning of code no test ever entered. So ABI changes fall back to
inverting the class reference graph and taking the transitive closure. Coarse, but it only fires
where precision is not available.

See [DESIGN.md](DESIGN.md) for the mechanics, and for the sharp edges.

## Using jiro from a coding agent

This is a first-class use case, not an afterthought. An agent working in a repo where `jiro:dev`
is already running should **never invoke a build**. It edits files and reads verdicts.

Every cycle is published to `target/jiro/status.json`, replaced atomically so a reader can never
see a partial write:

```json
{
  "cycleId": 47,
  "state": "RED",
  "terminal": true,
  "inputWatermarkMillis": 1758231847221,
  "durationMillis": 312,
  "selectionReason": "2 method(s), 0 ABI change(s), 0 added, 0 removed",
  "selectedTests": 4,
  "passed": 3,
  "failed": 1,
  "compileErrors": [],
  "failures": [
    {"uniqueId": "[engine:junit-jupiter]/[class:com.acme.OrderServiceTest]/[method:appliesDiscount()]",
     "displayName": "OrderServiceTest.appliesDiscount",
     "message": "org.opentest4j.AssertionFailedError: expected: <90> but was: <100>"}
  ]
}
```

The one thing an agent must get right is staleness: `status.json` always holds *some* verdict, and
reading it straight after an edit returns the **previous** cycle's answer. `inputWatermarkMillis`
is the guard — it is the newest modification time among the sources that cycle considered. Wait
until `terminal` is true *and* the watermark is at or past your edit.

`bin/jiro-await` does exactly that:

```
bin/jiro-await --since-now --timeout 30
```

Exit code is the verdict: `0` green, `1` red, `2` compile error, `3` timeout, `4` no session.

So an agent's inner loop becomes: edit → `jiro-await` → read failures → edit. No `mvn` invocation,
no JVM start, no full suite. The feedback is the same few hundred milliseconds a human gets.

`target/jiro/events.ndjson` keeps one line per completed cycle if you want the history, and
`target/jiro/coverage.index` is plain text — grep it to find out which tests cover a given method.

## Requirements

- JDK 17 or later (a JDK, not a JRE: jiro compiles in-process)
- Maven 3.9+
- JUnit 5 (JUnit 4 works through the vintage engine)

## Status

Early, but it works. Validated end to end against two real Spring Boot codebases:

| | tests | baseline | no-op edit | regression caught |
|---|---|---|---|---|
| `service-a` | 156 | 14s | **0 selected**, ~0.5s | 9 selected, 2 failed, 1.0s |
| `service-b` | 960 | 35s | **0 selected**, ~0.5s | 2 selected, 1 failed, 2.3s |

On `service-a` the baseline reproduces `mvn test` exactly: 154 passed and one pre-existing ArchUnit
failure, in both. Breaking an asserted value turned the loop red in a second, having run 9 of 156
tests. On `service-b` a regression was found by running **2 of 960** tests.

Five bugs surfaced in the first hour against real code — including one that made JUnit Jupiter
silently discover zero tests, and one that made a comment-only edit look like 26 changed classes.
All five are fixed and written up in [DESIGN.md](DESIGN.md), along with what is still unfinished.
