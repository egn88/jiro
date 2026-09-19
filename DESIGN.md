# jiro — design

## The shape of a cycle

```
  edit .java
      │
      ▼
  SourceWatcher ──── debounce until the filesystem goes quiet (150ms)
      │
      ▼
  IncrementalCompiler ──── javac in-process, only the changed files
      │                    (compile error → publish, stop, wait for the next edit)
      ▼
  Fingerprinter ──── re-hash the output dir, diff against the previous snapshot
      │                    → changed method bodies
      │                    → ABI changes, additions, removals
      ▼
  TestSelector ──── recorded coverage for changed bodies
      │             ClassFirewall closure for ABI changes
      │             everything for tests never run before
      ▼
  ForkedRunner ──── the same long-lived JVM, a fresh classloader
      │
      ▼
  CoverageIndex updated · console line · status.json published
```

Two things make it fast, and neither is the test selection.

**The compiler stays warm.** `javax.tools.JavaCompiler` in Maven's own process, not a forked
`javac`. A one-file edit compiles in tens of milliseconds; forking would cost a second before any
work began.

**The test JVM stays alive.** It is forked once at startup and kept for the whole session, so JIT
state, CDS and any cached framework context survive between cycles. The tenth run of a test costs a
fraction of the first. Application classes are loaded through a throwaway `URLClassLoader` that is
discarded and rebuilt each cycle — that is what lets recompiled bytecode take effect without a
restart, and it is why the application must *not* be on the fork's own classpath: the system loader
would answer first and the new bytecode would never be seen.

## Module layout

| Module | Runs in | Responsibility |
|---|---|---|
| `jiro-runtime` | bootstrap classloader of the test JVM | `CoverageRecorder`, `MethodRegistry`. Zero dependencies, by necessity |
| `jiro-agent` | test JVM, as `-javaagent` | ASM entry probes, ASM relocated so it cannot collide with the project's own |
| `jiro-core` | Maven JVM | watcher, compiler, fingerprinting, selection, reporting |
| `jiro-runner` | forked test JVM, `main` | JUnit Platform Launcher, binds probes to tests |
| `jiro-maven-plugin` | Maven JVM | the `jiro:dev` goal, supervises the fork |

`jiro-runtime` has zero dependencies because instrumented application classes call into it from
classloaders that have no view of anything else. Bootstrap is the only place every loader delegates
to. `jiro-agent` relocates ASM because it shares a classloader with the application under test, and
plenty of applications ship their own ASM.

## Change detection

`Fingerprinter` reads each `.class` file with `SKIP_DEBUG | SKIP_FRAMES` and hashes two things per
class:

- **a digest per method body** — opcodes, operands, referenced members, renumbered jump targets,
  try/catch structure, and the method's own annotations
- **an ABI digest** — supertypes, and the signature, annotations and constant value of every
  non-private member, sorted (javac does not promise member order across compilations)

Dropping debug information is what makes the tool pleasant rather than merely correct. Line numbers
and local variable names are gone before hashing, so reformatting a file, renaming a local, adding a
comment or moving a method down the file all produce identical digests and select **zero tests**.
Only a change a JVM could observe moves a hash.

Method annotations are deliberately part of the *body* digest: flipping `@Transactional(readOnly =
true)` changes what a method does without touching an instruction.

## Selection

In the order the rules apply:

1. **No coverage recorded yet** → run everything, build the baseline. Nothing to select from.
2. **A test the index has never seen** → always run. This is the honest answer to the one question
   dynamic coverage cannot answer about itself.
3. **A changed method body** → exactly the tests that entered it, from the inverted coverage map.
4. **A changed ABI, or a deleted class** → every test covering anything in the firewall closure.

Rule 4 is the static half, and it is there because rule 3 is *unsound* on its own. Recorded
coverage answers "which tests ran this code". That is the right question for a changed body and the
wrong one for a changed signature, supertype or annotation, which can alter the meaning of code no
test ever entered and of code that did not exist when the coverage was recorded. So ABI changes
invert the class reference graph and take the transitive closure. Coarse — a change to a widely
referenced interface will select most of the suite — but it only fires where precision is not
available, and it fires in the safe direction.

## Probes

One probe at method entry, not per branch. jiro needs to know whether a test *reached* a method, not
which path it took, and one probe per method keeps overhead low enough to leave enabled all session.

Probes live in a `boolean[]`. Writes to distinct elements of a boolean array are atomic and the only
value ever written is `true`, so hits recorded on threads the test spawned are collected without
synchronisation — the same reasoning JaCoCo uses for its probe arrays.

`@BeforeEach` lands in the right bucket for free, because JUnit fires `executionStarted` for a test
before its per-test callbacks. `@BeforeAll` does not: it runs in the class container, before any
test starts. That window is captured separately and unioned into every test of the class, which
over-attributes slightly. Over-attribution is the safe direction — it can only cause a test to run
when it did not strictly need to.

## Agent integration

An agent working in a repo where `jiro:dev` is running should never invoke a build. The design
commitment is that the agent's inner loop is *edit → read verdict*, with no JVM start anywhere in
it.

`target/jiro/status.json` is replaced atomically (temp file plus `ATOMIC_MOVE`), so a reader
polling in a tight loop can never see a partial document. Files rather than a socket or an RPC
endpoint, because the intended reader has shell access: `cat` needs no client library, no port and
no handshake, and works identically from a hook, a script or a person.

The subtle part is **staleness**, and it is the one thing an integration can get wrong in a way that
produces confidently false results. `status.json` always holds *some* verdict. An agent that writes
a file and immediately reads the status gets the previous cycle's answer, and concludes that code it
has just broken still passes. Two fields prevent that:

- `cycleId` — monotonic; a new value means a genuinely new verdict
- `inputWatermarkMillis` — the newest modification time among the sources *that cycle considered*

The contract for a reader is: wait until `terminal` is true **and** `inputWatermarkMillis` is at or
past the mtime of the file you wrote. `bin/jiro-await --since-now` implements exactly this and
returns the verdict as its exit code.

## What the first real projects found

jiro was written against reasoning alone, then pointed at two real Spring Boot codebases
(`service-a`, 156 tests; `service-b`, 960 tests). Five bugs surfaced in the first hour, four of
which a synthetic fixture would almost certainly have missed. They are recorded here because each
one says something about the design, not just about a line of code.

**Shipping our own JUnit Platform made Jupiter silently find nothing.** The fork carried jiro's
`junit-platform-launcher` 1.11.4 on its system classpath and loaded the application child-last, so
platform classes resolved from jiro while `junit-jupiter-engine` 5.10.3 came from the project.
Jupiter registered itself and discovered **zero** tests. No exception, no warning. Cucumber and
ArchUnit kept working, so the run looked plausible: 2 tests instead of 979. The fix was to stop
shipping JUnit entirely — the runner now has no compile-time JUnit dependency at all and drives the
platform reflectively through `JUnitBridge`, against an `ApplicationClassLoader` that is child-first
for everything but the JDK and jiro's own probe runtime. A tool that quietly runs none of your unit
tests is worse than one that crashes.

**Discovering everything is not the same as running the test suite.** Scanning the classpath roots
found `service-a`'s 28 Failsafe `*IT` classes alongside its 38 Surefire `*Test` classes. Run outside
the integration environment they expect, one Spring context failed and 827 tests cascaded off it —
burying the actual result. jiro now filters by Surefire's default class-name patterns, so
`jiro:dev` mirrors `mvn test`. A dev loop should reproduce the fast suite, not the slow one.

**The fingerprint was not deterministic.** ASM models an enum annotation value as a `String[]`, so
`String.valueOf` on an annotation's values baked `[Ljava.lang.String;@1b6d3586` — an identity hash —
into the digest. Every class carrying an annotation with an enum attribute re-hashed on every scan.
A comment-only edit to one file reported 4 changed methods and 26 ABI changes, and the firewall
turned that into 53 tests. Annotation values are now rendered structurally. The lesson generalises:
**any digest built from `toString` output is one array away from being random**, and the failure
mode is silent over-selection, which looks like the tool merely being conservative.

**`events.ndjson` was not NDJSON.** The reporter appended the same indented JSON it wrote to
`status.json`, producing a file no line-oriented reader could parse. Events are now compact.

**Runtime-generated classes polluted the index.** Spring CGLIB proxies
(`Service-aApp$$SpringCGLIB$$0`) and Mockito mocks (`WorkspaceRepository$MockitoMock$l5v3uMAl`) were
instrumented like anything else, landing in the coverage index under names no fingerprint can ever
match — and the Mockito names are random per run, so the index never stabilised. They are now
skipped: a doubled dollar sign is a reliable marker, since the compiler emits only a single one for
nested classes. On `service-a` this removed 120 of 1903 indexed methods.

## Parallel execution

jiro refuses to index a run in which two tests recorded at once, because a single session cannot
attribute interleaved probes and the resulting corruption is silent — tests simply stop being
selected later.

Detection is empirical rather than configuration-based. Reading
`junit.jupiter.execution.parallel.enabled` would miss `@Execution(CONCURRENT)` annotations and
anything a future JUnit version invents, so instead `CoverageRecorder.beginTest()` latches a flag if
a session is already open. That makes the listener's bookkeeping load-bearing: every `beginTest` is
preceded by an `endTest`, including across nested `@Nested` containers, so an open session really
does mean two tests are in flight. The run completes and the mojo then aborts with an explanation,
rather than failing one arbitrary test.

## Known sharp edges

Honest list of what is unfinished or risky, roughly in order of how likely it is to bite.

**Surefire's actual configuration is ignored.** jiro uses Surefire's *default* includes, not the
`<includes>`/`<excludes>` the project may have configured. A project that renames its test classes
will see jiro run the wrong set. Reading the Surefire plugin configuration out of the project model
is the fix.

**Engines that do not filter by class name escape the alignment.** The Cucumber engine discovers
`.feature` files from the classpath root and ignores `ClassNameFilter`, so jiro ran one scenario
`mvn test` does not. Engine-level include/exclude is needed.

**A stale `target/classes` makes the first cycles lie.** jiro seeds its fingerprint snapshot from
whatever bytecode is already in the output directory at startup. If that does not match the source
tree — a killed session, a `git checkout` made while jiro was down — the first edits diff against
the wrong baseline and report nonsense, and the startup baseline run tests code that is not what is
on disk. Running `mvn test-compile` before `jiro:dev` avoids it; jiro should detect the skew itself.

**Failsafe and Surefire configuration is not inherited.** `argLine`, `systemPropertyVariables` and
the active Spring profile have to be passed by hand through `jiro.jvmArgs`. For unit tests this
rarely matters; for integration tests it is the difference between a working context and a wall of
failures. Reading them out of the project model is the fix.

**Static state leaks across cycles.** A fresh classloader per cycle gives fresh statics for
application classes, but anything cached on the bootstrap or system loader — and any thread pool,
JDBC driver registration or shutdown hook the previous cycle left running — survives. Long sessions
may drift. A periodic fork restart is the likely mitigation.

**The firewall has no ceiling.** An ABI change to a core interface takes a closure over most of the
codebase and selects most of the suite. Correct, but it makes the worst case no better than
`mvn test`. A size cap that falls back to a plain full run would at least make the behaviour
predictable.

**Reflection that coverage has not yet observed.** Dynamic coverage sees reflective calls it has
*executed*, which is most of the point — but a code path reached reflectively only under conditions
the baseline run did not hit is invisible until it runs once. This is inherent to the approach, not
a bug to be fixed, and it is why rule 2 exists.

**Annotation processors run on every incremental compile.** Lombok and MapStruct are handled because
processing is left enabled, but compiling one file in isolation is not the same as compiling the
module, and generated-source staleness has not been thought through.

**Multi-module reactor projects.** `jiro:dev` operates on one module. A change in a sibling module
that the current one depends on is invisible. Real projects are multi-module, so this matters, and
it is probably the largest missing feature rather than a sharp edge.

## Roadmap

1. Read the project's Surefire configuration — includes, excludes and system properties — rather
   than assuming its defaults.
2. Engine-level include/exclude, so Cucumber and friends honour the same alignment.
3. Multi-module reactor support.
4. Persist fingerprints alongside the coverage index so a session can resume without a baseline run.
5. A periodic fork restart, to bound static-state drift over a long session.
6. Detect a `target/classes` that does not match the source tree at startup.
7. An MCP server wrapping the index — `jiro_status`, `jiro_failures`, `jiro_impact_of(file)` — so an
   agent can ask "what would change if I touched this?" before editing rather than after.
8. Probe optimisation: a static `boolean[]` field per class resolved once in `<clinit>`, replacing
   the static call per method entry.

## Measured behaviour

Against `service-a` (156 tests) and `service-b` (960 tests), after the fixes above:

| cycle | service-a | service-b |
|---|---|---|
| full baseline | 14s | 35s |
| no-op edit (comment, reformat) | 0 tests, ~0.5s | 0 tests, ~0.5s |
| test-source edit (forces re-discovery) | 2.4s | — |
| one changed method | 1–9 tests, ~1.0s | 7 tests, 2.3s |
| regression | 9 tests, 2 failed, 1.0s | 2 tests, 1 failed, 2.3s |

Caching discovery is what moved a no-op cycle from ~2.6s to ~0.5s: rescanning the classpath roots
cost more than compiling and selecting combined.
