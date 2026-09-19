# jiro

Continuous, method-level test impact analysis for Maven projects.

Start it once and leave it running:

```
mvn jiro:dev
```

From then on, saving a `.java` file recompiles only that file and runs only the tests that the
change can actually affect — usually a handful, usually in well under a second.

```
[jiro] watching 51 directories, status at target/jiro/status.json
[jiro] building coverage baseline, running the full suite once
[jiro] baseline complete: 156 tests, 1782 methods indexed
[jiro] compiled in 314ms, ran 9 test(s), 0 failed — 1 method(s), 0 ABI change(s), 0 added, 0 removed
[jiro] compiled in 903ms, no tests to run (no observable change)
```

That second-to-last line is the normal case. The last one is a comment-only edit: jiro compares
normalised bytecode, so reformatting, renaming a local or moving a method selects nothing at all.

## Getting started

Needs a JDK 17 or later — a JDK, not a JRE, because jiro compiles in-process — Maven 3.9+, and
JUnit 5 (JUnit 4 works through the vintage engine).

jiro is not on Maven Central yet, so install it into your local repository first:

```
git clone git@github.com:egn88/jiro.git
cd jiro
mvn install
```

Then, in the project you want to work on, either declare the plugin:

```xml
<plugin>
  <groupId>io.github.egn88</groupId>
  <artifactId>jiro-maven-plugin</artifactId>
  <version>0.0.1</version>
</plugin>
```

or invoke it without touching the pom at all:

```
mvn io.github.egn88:jiro-maven-plugin:0.0.1:dev
```

To get the short `mvn jiro:dev` form in any project, add the group once to `~/.m2/settings.xml`:

```xml
<pluginGroups>
  <pluginGroup>io.github.egn88</pluginGroup>
</pluginGroups>
```

Build the project once before starting. jiro seeds its baseline from whatever bytecode is already
in `target/classes`, so if that does not match your sources the first few cycles will diff against
the wrong thing:

```
mvn test-compile
mvn jiro:dev
```

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
see a partial write. Failures are structured for acting on, not just for reading:

```json
{
  "cycleId": 47,
  "state": "RED",
  "terminal": true,
  "inputWatermarkMillis": 1758231847221,
  "selectedTests": 9,
  "passed": 7,
  "failed": 2,
  "compileErrors": [],
  "failures": [
    {
      "uniqueId": "[engine:junit-jupiter]/[class:com.acme.UserMapperTest]/[method:testUserToUserDTO()]",
      "displayName": "UserMapperTest.testUserToUserDTO()",
      "type": "org.opentest4j.AssertionFailedError",
      "message": "\nexpected: \"johndoe\"\n but was: \"BROKEN-johndoe\"",
      "trace": ["com.acme.UserMapperTest.testUserToUserDTO(UserMapperTest.java:74)"]
    }
  ]
}
```

The `trace` is filtered to the project's own frames — JDK, JUnit, AssertJ, Mockito and Spring test
infrastructure are stripped — so the first entry is almost always the assertion that failed, with
its file and line. That is the difference between an agent knowing something broke and knowing
where to edit.

Compile errors are structured the same way, and carry the offending line so the file need not be
reopened:

```json
"compileErrors": [
  {
    "file": "/home/you/project/src/main/java/com/acme/UserDTO.java",
    "line": 64,
    "column": 26,
    "message": "cannot find symbol\n  symbol:   method getLogimn()\n  location: variable user of type com.acme.User",
    "sourceLine": "this.login = user.getLogimn();"
  }
]
```

### Staying in step

`status.json` always holds *some* verdict, so reading it straight after an edit returns the
**previous** cycle's answer. Two guards are available, and `bin/jiro-await` implements both:

```
bin/jiro-await --since-now          # verdict must cover the newest .java on disk
bin/jiro-await --after-cycle 46     # verdict must have cycleId > 46
```

`--after-cycle` is the stronger one for a scripted loop: `cycleId` is a counter jiro owns, so it
needs no clock and cannot be confused by filesystem timestamp resolution. Read `cycleId` from each
verdict, pass it to the next call.

Exit code is the verdict: `0` green, `1` red, `2` compile error, `3` timeout, `4` no session.

You do **not** need to delete `status.json` between reads. It is replaced every cycle rather than
appended to, so it never grows, and its reappearance would not prove the verdict covers *your*
edit — a cycle triggered by something else recreates it too. Deleting it is nonetheless harmless if
you prefer: the writer holds no handle on it.

`target/jiro/events.ndjson` keeps one line per completed cycle, rotated at 4MB, and
`target/jiro/coverage.index` is plain text — grep it to find out which tests cover a given method.

## Integration tests

By default jiro runs what `mvn test` runs: Surefire's default class-name patterns, which exclude the
`*IT` classes Failsafe owns. That is deliberate — a dev loop should reproduce the fast suite.

It *can* run them:

```
mvn jiro:dev -Djiro.testClassPatterns='^(.*\.)?[^.$]*IT$' \
             -Djiro.jvmArgs=-Dspring.profiles.active=testdev
```

But jiro does not inherit Failsafe's configuration — its `argLine`, `systemPropertyVariables` and
active profile have to be passed through `jiro.jvmArgs` by hand, and if the environment those tests
need is missing they fail here exactly as they would under `mvn verify`. Testcontainers itself works
(the long-lived JVM actually helps: a container started in the first cycle is still up for the
tenth), but a thirty-second container start does not belong in a loop whose selling point is
sub-second feedback. Treat integration tests as something to opt into deliberately, not as the
default mode.

## Status

Early, but it works. Validated end to end against two real Spring Boot codebases:

| | tests | baseline | no-op edit | regression caught |
|---|---|---|---|---|
| `service-a` | 156 | 14s | **0 selected**, ~0.5s | 9 selected, 2 failed, 1.0s |
| `service-b` | 960 | 35s | **0 selected**, ~0.5s | 2 selected, 1 failed, 2.3s |

On `service-a` the baseline reproduces `mvn test` exactly: 154 passed and one pre-existing ArchUnit
failure, in both. Breaking an asserted value turned the loop red in a second, having run 9 of 156
tests. On `service-b` a regression was found by running **2 of 960** tests.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
