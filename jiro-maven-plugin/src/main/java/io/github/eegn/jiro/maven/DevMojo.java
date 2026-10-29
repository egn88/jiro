package io.github.eegn.jiro.maven;

import io.github.eegn.jiro.core.analyze.ChangeSet;
import io.github.eegn.jiro.core.analyze.ClassFingerprint;
import io.github.eegn.jiro.core.analyze.FingerprintStore;
import io.github.eegn.jiro.core.analyze.Fingerprinter;
import io.github.eegn.jiro.core.compile.CompilationResult;
import io.github.eegn.jiro.core.compile.CompileError;
import io.github.eegn.jiro.core.compile.IncrementalCompiler;
import io.github.eegn.jiro.core.report.CycleReport;
import io.github.eegn.jiro.core.report.JsonReporter;
import io.github.eegn.jiro.core.resume.ClasspathEntry;
import io.github.eegn.jiro.core.resume.Inputs;
import io.github.eegn.jiro.core.resume.ResumeCheck;
import io.github.eegn.jiro.core.resume.ResumeState;
import io.github.eegn.jiro.core.resume.ResumeStore;
import io.github.eegn.jiro.core.select.ClassFirewall;
import io.github.eegn.jiro.core.select.CoverageIndex;
import io.github.eegn.jiro.core.select.Selection;
import io.github.eegn.jiro.core.select.TestSelector;
import io.github.eegn.jiro.core.watch.FileChange;
import io.github.eegn.jiro.core.watch.SourceWatcher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

/**
 * {@code mvn jiro:dev} &mdash; watch, recompile what changed, run the tests that change can affect,
 * and keep doing it until interrupted.
 *
 * <p>The goal blocks for the whole session. Maven has no notion of a daemon, so a long-running dev
 * mode has to be a foreground goal the developer starts deliberately, the way {@code quarkus:dev}
 * does.
 *
 * <p>Every cycle is also published as JSON under {@code target/jiro/}, which is how a coding agent
 * consumes jiro: it edits files and reads verdicts, without ever invoking a build of its own.
 */
@Mojo(name = "dev",
        requiresDependencyResolution = ResolutionScope.TEST,
        threadSafe = true)
public class DevMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${plugin.artifacts}", readonly = true, required = true)
    private List<Artifact> pluginArtifacts;

    @Component
    private org.eclipse.aether.RepositorySystem repositorySystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private org.eclipse.aether.RepositorySystemSession repositorySession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true)
    private List<org.eclipse.aether.repository.RemoteRepository> remoteRepositories;

    @Parameter(defaultValue = "${project.remotePluginRepositories}", readonly = true)
    private List<org.eclipse.aether.repository.RemoteRepository> remotePluginRepositories;

    @Parameter(defaultValue = "${plugin}", readonly = true)
    private org.apache.maven.plugin.descriptor.PluginDescriptor pluginDescriptor;

    /**
     * Package prefixes to instrument. Inferred from the packages present in the output directories
     * when left unset, which is almost always what is wanted: instrumenting dependencies would slow
     * the run down to record edges no source change can ever move.
     */
    @Parameter(property = "jiro.includes")
    private List<String> includes;

    /**
     * Regular expressions matching the fully qualified names of classes to treat as tests.
     *
     * <p>Defaults to Surefire's own default includes, so {@code jiro:dev} runs the same set as
     * {@code mvn test}. That alignment matters more than it sounds: scanning the classpath roots
     * unfiltered also picks up the {@code *IT} classes Failsafe owns, and running those outside the
     * integration environment they expect buries the developer's actual result under hundreds of
     * context-loading failures.
     */
    @Parameter(property = "jiro.testClassPatterns")
    private List<String> testClassPatterns;

    /** Extra JVM arguments for the forked test JVM, e.g. {@code -Xmx1g}. */
    @Parameter(property = "jiro.jvmArgs")
    private List<String> jvmArgs;

    /** How long the filesystem must be quiet before a batch of edits is treated as complete. */
    @Parameter(property = "jiro.quietPeriodMillis", defaultValue = "150")
    private long quietPeriodMillis;

    /** Where status.json, events.ndjson and the coverage index are kept. */
    @Parameter(property = "jiro.stateDirectory", defaultValue = "${project.build.directory}/jiro")
    private java.io.File stateDirectory;

    /**
     * Run the full suite at startup even when the state persisted by a previous session would let
     * this one resume. Without it, jiro resumes only when it can prove the persisted index still
     * describes the code (same jiro build, JDK, dependency jars, resources and options) and runs
     * a full baseline otherwise. Sources and class files that changed while jiro was stopped are
     * recompiled and their tests selected in the first cycle.
     */
    @Parameter(property = "jiro.baselineOnStartup", defaultValue = "false")
    private boolean baselineOnStartup;

    /**
     * Answer OpenRewrite requests dropped into {@code target/jiro/rewrite/requests}. Costs one
     * directory listing every few tens of milliseconds until the first request arrives; OpenRewrite
     * itself is resolved, loaded and run only then.
     */
    @Parameter(property = "jiro.rewrite.enabled", defaultValue = "true")
    private boolean rewriteEnabled;

    /**
     * Load OpenRewrite and parse the project in the background once the baseline is done, so the
     * first rewrite is as fast as the later ones. Off by default: it costs a few seconds of CPU and a
     * few hundred megabytes of heap in sessions that never rewrite anything.
     */
    @Parameter(property = "jiro.rewrite.prewarm", defaultValue = "false")
    private boolean rewritePrewarm;

    /**
     * Held while javac writes class files and while the rewrite service parses, so a parse never
     * reads a class file halfway through being written.
     */
    private final java.util.concurrent.locks.ReentrantLock compileLock =
            new java.util.concurrent.locks.ReentrantLock();

    // Session state carried between cycles and persisted to resume.state after each one.

    /** Sources of a cycle that did not compile; compiled again with the next edit. */
    private final Set<Path> pendingSources = new LinkedHashSet<>();
    /** Digest of every source as last compiled, keyed relative to the base directory. */
    private final Map<String, String> compiledSources = new HashMap<>();
    /** Tests whose last run failed or never reported; re-run when a session resumes. */
    private final Set<String> failingTests = new HashSet<>();
    /** Tests to add to the next selection: the failing ones carried over from a stopped session. */
    private Set<String> carriedOver = Set.of();
    /** The first cycle after resuming, until it compiles. */
    private boolean resumeCycle;
    /** Discovery must run on the next cycle whatever changed; set when resuming. */
    private boolean forceDiscovery;
    private ResumeStore resumeStore;
    private FingerprintStore fingerprintStore;
    private Map<String, String> environment;
    private List<ClasspathEntry> classpathEntries;
    private Map<String, String> resourceDigests;

    @Override
    public void execute() throws MojoExecutionException {
        if ("pom".equals(project.getPackaging())) {
            getLog().info("[jiro] skipping aggregator module");
            return;
        }
        try {
            run();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            getLog().info("[jiro] stopped");
        } catch (IOException failure) {
            throw new MojoExecutionException("jiro dev mode failed", failure);
        }
    }

    private void run() throws IOException, InterruptedException, MojoExecutionException {
        Path classes = Path.of(project.getBuild().getOutputDirectory());
        Path testClasses = Path.of(project.getBuild().getTestOutputDirectory());
        Path state = stateDirectory.toPath();

        List<Path> sourceRoots = existingDirectories(Stream.concat(
                project.getCompileSourceRoots().stream(),
                project.getTestCompileSourceRoots().stream()).map(Path::of).toList());
        if (sourceRoots.isEmpty()) {
            getLog().info("[jiro] no source roots, nothing to watch");
            return;
        }

        List<Path> classpath = ensureLauncherPresent(testClasspath());
        JsonReporter reporter = new JsonReporter(state);
        Fingerprinter fingerprinter = new Fingerprinter();
        FingerprintStore fingerprints = new FingerprintStore();
        fingerprintStore = fingerprints;
        TestSelector selector = new TestSelector();
        Path indexFile = state.resolve("coverage.index");
        CoverageIndex index = new CoverageIndex();
        resumeStore = new ResumeStore(state.resolve("resume.state"));

        IncrementalCompiler compiler = new IncrementalCompiler(classpath, classes, List.of());
        IncrementalCompiler testCompiler = new IncrementalCompiler(classpath, testClasses, List.of());

        ForkedRunner.Options options = new ForkedRunner.Options(
                Path.of(System.getProperty("java.home"), "bin", "java"),
                pluginJar("jiro-agent"),
                pluginJar("jiro-runtime"),
                pluginJar("jiro-runner"),
                classpath,
                List.of(classes, testClasses),
                includes == null || includes.isEmpty()
                        ? inferPackages(List.of(classes, testClasses))
                        : includes,
                jvmArgs == null ? List.of() : jvmArgs,
                classNamePattern());

        RewriteEndpoint rewrite = rewriteEnabled ? rewriteEndpoint(state) : null;
        SessionFile session = new SessionFile(state, rewrite);
        session.publish();
        try (ForkedRunner runner = new ForkedRunner(options);
             SourceWatcher watcher = new SourceWatcher(sourceRoots);
             SessionFile ignored = session;
             AutoCloseable stopRewrite = rewrite == null ? () -> { } : rewrite) {

            runner.start();
            if (rewrite != null) {
                rewrite.start();
            }
            getLog().info("[jiro] watching " + watcher.watchedDirectoryCount()
                    + " directories, status at " + reporter.statusFile());

            // Hashed after the watcher is registered: an edit made from here on produces an
            // event and its own cycle, so nothing written during startup can be missed.
            Path baseDir = project.getBasedir().toPath();
            Map<String, String> sourcesNow = Inputs.sources(baseDir, sourceRoots);
            environment = environment(options);
            ResumeState stored = null;
            ResumeCheck.Decision decision;
            long startedAt = System.nanoTime();
            if (baselineOnStartup) {
                decision = new ResumeCheck.Decision(false, "jiro.baselineOnStartup=true", Set.of());
            } else {
                try {
                    stored = resumeStore.load();
                    decision = null;
                } catch (ResumeStore.Unusable unusable) {
                    decision = new ResumeCheck.Decision(false, unusable.getMessage(), Set.of());
                }
            }
            classpathEntries = Inputs.classpath(dependencyClasspath(classpath, classes, testClasses),
                    stored == null ? List.of() : stored.classpath());
            resourceDigests = Inputs.resources(List.of(classes, testClasses));
            if (decision == null) {
                decision = ResumeCheck.decide(stored, new ResumeCheck.Current(environment,
                        classpathEntries, resourceDigests, sourcesNow, Inputs.fileDigest(indexFile)));
            }
            long checkMillis = (System.nanoTime() - startedAt) / 1_000_000;

            long cycleId;
            List<String> discovered;
            if (decision.resume()) {
                getLog().info("[jiro] resuming without a baseline (" + decision.reason()
                        + ", checked in " + checkMillis + "ms)");
                index = CoverageIndex.load(indexFile);
                fingerprints.restore(stored.fingerprints());
                compiledSources.putAll(stored.sources());
                failingTests.addAll(stored.failingTests());
                carriedOver = Set.copyOf(stored.failingTests());
                resumeCycle = true;
                forceDiscovery = true;
                List<Path> changedWhileStopped = decision.changedSources().stream()
                        .map(baseDir::resolve)
                        .toList();
                cycleId = stored.lastCycleId() + 1;
                discovered = cycle(runner, reporter, fingerprinter, fingerprints, selector, index,
                        compiler, testCompiler, classes, testClasses, state,
                        changedWhileStopped, cycleId, List.of());
                if (rewrite != null && rewritePrewarm) {
                    rewrite.prewarm();
                }
            } else {
                getLog().info("[jiro] not resuming: " + decision.reason());
                // Seed the fingerprint store so the first edit diffs against reality rather than
                // against nothing, which would report every class as added.
                fingerprints.update(scan(fingerprinter, classes, testClasses));
                compiledSources.putAll(sourcesNow);
                cycleId = stored == null ? 1 : stored.lastCycleId() + 1;
                baseline(runner, index, reporter, state, cycleId);
                if (rewrite != null && rewritePrewarm) {
                    rewrite.prewarm();
                }
                discovered = runner.discover();
            }

            while (true) {
                List<FileChange> changes = watcher.awaitChanges(Duration.ofMillis(quietPeriodMillis));
                List<Path> changedSources = changes.stream()
                        .filter(FileChange::isJavaSource)
                        .map(FileChange::path)
                        .toList();
                if (changedSources.isEmpty()) {
                    continue;
                }
                cycleId++;
                discovered = cycle(runner, reporter, fingerprinter, fingerprints, selector, index,
                        compiler, testCompiler, classes, testClasses, state,
                        changedSources, cycleId, discovered);
            }
        } catch (IOException | InterruptedException | MojoExecutionException | RuntimeException rethrown) {
            throw rethrown;
        } catch (Exception closing) {
            throw new IOException(closing);
        }
    }

    private RewriteEndpoint rewriteEndpoint(Path state) throws IOException, MojoExecutionException {
        Map<String, Object> config = new java.util.HashMap<>();
        config.put("baseDir", project.getBasedir().toPath());
        config.put("mainSourceRoots", ownSourceRoots(project.getCompileSourceRoots()));
        config.put("testSourceRoots", ownSourceRoots(project.getTestCompileSourceRoots()));
        try {
            config.put("compileClasspath",
                    project.getCompileClasspathElements().stream().distinct().map(Path::of).toList());
        } catch (org.apache.maven.artifact.DependencyResolutionRequiredException unresolved) {
            throw new MojoExecutionException("Compile dependencies are not resolved", unresolved);
        }
        config.put("testClasspath", testClasspath().stream().distinct().toList());
        String encoding = project.getProperties().getProperty("project.build.sourceEncoding");
        if (encoding != null) {
            config.put("charset", encoding);
        }
        config.put("lock", compileLock);
        java.util.function.Consumer<String> info = getLog()::info;
        config.put("log", info);
        String version = pluginDescriptor.getVersion();
        return new RewriteEndpoint(state,
                () -> RewriteServiceLoader.load(repositorySystem, repositorySession,
                        remotePluginRepositories, version, config),
                info, getLog()::warn);
    }

    /**
     * Source roots minus generated ones under the build directory, as rewrite-maven-plugin does:
     * annotation-processor output is regenerated, not rewritten.
     */
    private List<Path> ownSourceRoots(List<String> roots) {
        Path build = Path.of(project.getBuild().getDirectory()).toAbsolutePath().normalize();
        return existingDirectories(roots.stream().map(Path::of)
                .filter(root -> !root.toAbsolutePath().normalize().startsWith(build))
                .toList());
    }

    private void baseline(ForkedRunner runner, CoverageIndex index, JsonReporter reporter,
                          Path state, long cycleId) throws IOException, MojoExecutionException {
        getLog().info("[jiro] building coverage baseline, running the full suite once");
        CycleReport report = CycleReport.starting(cycleId, System.currentTimeMillis())
                .running("baseline", 0);
        reporter.publish(report);

        ForkedRunner.RunReport run = runner.runAll();
        failIfParallel(run);
        failingTests.clear();
        applyResults(index, run);
        index.save(state.resolve("coverage.index"));
        persistOrInvalidate(state, run, cycleId, Set.of());
        publishOutcome(reporter, report, run, "baseline");
        getLog().info("[jiro] baseline complete: " + run.results().size() + " tests, "
                + index.coveredMethodCount() + " methods indexed");
    }

    /**
     * Runs one cycle and returns the test list to carry into the next one.
     *
     * @param discovered the previously discovered tests, reused unless this cycle could have
     *                   changed the set
     */
    private List<String> cycle(ForkedRunner runner, JsonReporter reporter, Fingerprinter fingerprinter,
                       FingerprintStore fingerprints, TestSelector selector, CoverageIndex index,
                       IncrementalCompiler compiler, IncrementalCompiler testCompiler,
                       Path classes, Path testClasses, Path state,
                       List<Path> changedSources, long cycleId, List<String> discovered)
            throws IOException, MojoExecutionException {

        // The sources of a cycle that did not compile are still not reflected in the class files,
        // so they are compiled again together with whatever changed next.
        Set<Path> toCompile = new LinkedHashSet<>(pendingSources);
        toCompile.addAll(changedSources);
        pendingSources.clear();
        pendingSources.addAll(toCompile);

        // A resumed session's first cycle stands for everything written while jiro was stopped,
        // whatever its timestamp, so its watermark is now rather than the newest recompiled file.
        CycleReport report = CycleReport.starting(cycleId,
                resumeCycle ? System.currentTimeMillis() : watermarkOf(List.copyOf(toCompile)));
        reporter.publish(report);

        List<Path> mainSources = new ArrayList<>();
        List<Path> testSources = new ArrayList<>();
        for (Path source : toCompile) {
            (isTestSource(source) ? testSources : mainSources).add(source);
        }

        // Digested before javac reads them. If a file changes in between, the stored digest is the
        // older content and a later start sees a difference and recompiles: the safe direction.
        Path baseDir = project.getBasedir().toPath();
        Map<String, String> compiledNow = new HashMap<>();
        for (Path source : toCompile) {
            String digest = Inputs.sourceDigest(source);
            if (digest != null) {
                compiledNow.put(Inputs.sourceKey(baseDir, source), digest);
            }
        }

        long startedAt = System.nanoTime();
        List<CompileError> errors = new ArrayList<>();
        compileLock.lock();
        try {
            CompilationResult main = compiler.compile(mainSources);
            errors.addAll(main.errors());
            if (main.successful()) {
                CompilationResult tests = testCompiler.compile(testSources);
                errors.addAll(tests.errors());
            }
        } finally {
            compileLock.unlock();
        }
        if (!errors.isEmpty()) {
            reporter.publish(report.compileFailed(errors));
            getLog().error("[jiro] compilation failed");
            for (CompileError error : errors) {
                getLog().error("  " + error.describe());
                if (error.sourceLine() != null) {
                    getLog().error("      " + error.sourceLine());
                }
            }
            return discovered;
        }
        long compileMillis = (System.nanoTime() - startedAt) / 1_000_000;
        pendingSources.clear();
        compiledSources.putAll(compiledNow);

        Map<String, ClassFingerprint> fresh = scan(fingerprinter, classes, testClasses);
        ChangeSet changeSet = fingerprints.update(fresh);

        // Discovery spins a fresh classloader and rescans the classpath roots, which dominates the
        // cost of a cycle on a large project. The set of tests can only have moved if a test source
        // was recompiled or a class appeared or vanished, so otherwise the previous list stands.
        if (forceDiscovery
                || !testSources.isEmpty()
                || !changeSet.addedClasses().isEmpty()
                || !changeSet.removedClasses().isEmpty()) {
            discovered = runner.discover();
            forgetDeletedTests(index, discovered);
            forceDiscovery = false;
        }

        Selection selection = selector.select(
                changeSet, index, new HashSet<>(discovered), new ClassFirewall(fresh));
        if (resumeCycle) {
            selection = resumeSelection(selection, changeSet, discovered, toCompile.size());
        }
        resumeCycle = false;
        carriedOver = Set.of();

        if (selection.isEmpty()) {
            persist(state, cycleId);
            reporter.publish(report.finished(selection.reason(), 0, 0, List.of()));
            getLog().info("[jiro] compiled in " + compileMillis + "ms, no tests to run ("
                    + selection.reason() + ")");
            return discovered;
        }

        reporter.publish(report.running(selection.reason(),
                selection.full() ? discovered.size() : selection.size()));

        ForkedRunner.RunReport run = selection.full()
                ? runner.runAll()
                : runner.run(selection.testIds());
        failIfParallel(run);
        applyResults(index, run);
        index.save(state.resolve("coverage.index"));
        persistOrInvalidate(state, run, cycleId,
                selection.full() ? new HashSet<>(discovered) : selection.testIds());

        publishOutcome(reporter, report, run, selection.reason());
        logOutcome(run, compileMillis, selection);
        return discovered;
    }

    /**
     * Widens the first selection after a resume. Errs toward running more: tests that were red
     * when the previous session stopped run again, so the verdict published now is not a green
     * that only means "nothing was re-checked"; and a class file that vanished while jiro was
     * stopped means the output directory was touched by something jiro cannot reason about, so
     * everything runs.
     */
    private Selection resumeSelection(Selection selection, ChangeSet changes,
                                      List<String> discovered, int recompiled) {
        String prefix = "resumed, " + recompiled + " source(s) recompiled: ";
        if (!changes.removedClasses().isEmpty()) {
            return Selection.everything(prefix + changes.removedClasses().size()
                    + " class file(s) vanished while stopped, running everything");
        }
        if (selection.full()) {
            return Selection.everything(prefix + selection.reason());
        }
        Set<String> previouslyFailing = new LinkedHashSet<>(carriedOver);
        previouslyFailing.retainAll(new HashSet<>(discovered));
        Set<String> tests = new LinkedHashSet<>(selection.testIds());
        tests.addAll(previouslyFailing);
        String reason = prefix + selection.reason()
                + (previouslyFailing.isEmpty() ? "" : ", " + previouslyFailing.size()
                + " previously failing");
        return tests.isEmpty() ? Selection.nothing(reason) : new Selection(tests, false, reason);
    }

    /**
     * Saves the resume state after a run, unless the run did not complete. A selected test that
     * never reported is counted as failing, so it runs again on resume. A runner error means some
     * changes may never have been tested; the state is dropped and the next start runs a baseline.
     */
    private void persistOrInvalidate(Path state, ForkedRunner.RunReport run, long cycleId,
                                     Set<String> selected) throws IOException {
        Set<String> missing = new HashSet<>(selected);
        for (ForkedRunner.TestResult result : run.results()) {
            missing.remove(result.uniqueId());
        }
        failingTests.addAll(missing);
        if (!run.errors().isEmpty()) {
            resumeStore.invalidate();
            return;
        }
        persist(state, cycleId);
    }

    private void persist(Path state, long cycleId) throws IOException {
        resumeStore.save(new ResumeState(environment, classpathEntries, resourceDigests,
                compiledSources, Inputs.fileDigest(state.resolve("coverage.index")),
                failingTests, cycleId, fingerprintStore.current()));
    }

    /**
     * Stops the session if the fork reported overlapping tests.
     *
     * <p>Indexing coverage that cannot be attributed would corrupt every later selection, and the
     * corruption is invisible: tests simply stop being chosen. Failing loudly here is the only
     * honest option.
     */
    private void failIfParallel(ForkedRunner.RunReport run) throws MojoExecutionException {
        for (String error : run.errors()) {
            if (error.contains("simultaneously")) {
                throw new MojoExecutionException(
                        "jiro does not support parallel test execution: " + error
                        + ". Disable junit.jupiter.execution.parallel.enabled, or remove"
                        + " @Execution(CONCURRENT), and restart jiro:dev.");
            }
        }
    }

    private void publishOutcome(JsonReporter reporter, CycleReport report,
                                ForkedRunner.RunReport run, String reason) throws IOException {
        List<CycleReport.FailedTest> failures = new ArrayList<>();
        for (ForkedRunner.TestResult result : run.results()) {
            if (!result.passed()) {
                failures.add(new CycleReport.FailedTest(
                        result.uniqueId(),
                        shortName(result.uniqueId()),
                        result.failureType() == null ? result.status() : result.failureType(),
                        result.failureMessage() == null ? "" : result.failureMessage(),
                        result.trace()));
            }
        }
        for (String error : run.errors()) {
            failures.add(new CycleReport.FailedTest(
                    "<runner>", "<runner>", "RunnerError", error, List.of()));
        }
        int passed = run.results().size() - (int) run.failures();
        reporter.publish(report.finished(reason, run.results().size(), passed, failures));
    }

    private void logOutcome(ForkedRunner.RunReport run, long compileMillis, Selection selection) {
        long failed = run.failures();
        String summary = "[jiro] compiled in " + compileMillis + "ms, ran "
                + run.results().size() + " test(s), " + failed + " failed  — " + selection.reason();
        if (failed == 0) {
            getLog().info(summary);
            return;
        }
        getLog().error(summary);
        for (ForkedRunner.TestResult result : run.results()) {
            if (!result.passed()) {
                getLog().error("  " + shortName(result.uniqueId()) + "  " + result.summary());
            }
        }
    }

    private void applyResults(CoverageIndex index, ForkedRunner.RunReport run) {
        for (ForkedRunner.TestResult result : run.results()) {
            index.record(result.uniqueId(), result.coveredMethods());
            if (result.passed()) {
                failingTests.remove(result.uniqueId());
            } else {
                failingTests.add(result.uniqueId());
            }
        }
    }

    /**
     * What the persisted index depends on besides the code and the dependency jars. Any
     * difference forces a baseline. The jiro build is identified by the content of its own jars,
     * not just the version, because a rebuilt snapshot can fingerprint or instrument differently.
     */
    private Map<String, String> environment(ForkedRunner.Options options) throws IOException {
        Map<String, String> environment = new java.util.TreeMap<>();
        List<Path> jiroJars = new ArrayList<>();
        if (pluginDescriptor != null) {
            environment.put("jiro.version", pluginDescriptor.getVersion());
            if (pluginDescriptor.getPluginArtifact() != null
                    && pluginDescriptor.getPluginArtifact().getFile() != null) {
                jiroJars.add(pluginDescriptor.getPluginArtifact().getFile().toPath());
            }
        }
        for (Artifact artifact : pluginArtifacts) {
            if (artifact.getArtifactId().startsWith("jiro-") && artifact.getFile() != null) {
                jiroJars.add(artifact.getFile().toPath());
            }
        }
        jiroJars.sort(null);
        StringBuilder build = new StringBuilder();
        for (ClasspathEntry jar : Inputs.classpath(jiroJars, List.of())) {
            build.append(jar.digest(), 0, 16).append(' ');
        }
        environment.put("jiro.build", build.toString().strip());
        for (String property : List.of("java.version", "java.runtime.version", "java.vendor",
                "java.vm.name", "java.vm.version")) {
            environment.put(property, String.valueOf(System.getProperty(property)));
        }
        environment.put("jiro.includes", String.join(",", options.instrumentedPackages()));
        environment.put("jiro.jvmArgs", String.join("\u0001", options.jvmArguments()));
        environment.put("jiro.testClassPattern", options.classNamePattern());
        return environment;
    }

    /** The test classpath without the project's own output directories, which are fingerprinted. */
    private static List<Path> dependencyClasspath(List<Path> classpath, Path classes, Path testClasses) {
        Path ownClasses = classes.toAbsolutePath().normalize();
        Path ownTests = testClasses.toAbsolutePath().normalize();
        List<Path> dependencies = new ArrayList<>();
        for (Path element : classpath) {
            Path normalised = element.toAbsolutePath().normalize();
            if (!normalised.equals(ownClasses) && !normalised.equals(ownTests)) {
                dependencies.add(element);
            }
        }
        return dependencies;
    }

    private static void forgetDeletedTests(CoverageIndex index, List<String> discovered) {
        Set<String> alive = new HashSet<>(discovered);
        for (String known : index.knownTests()) {
            if (!alive.contains(known)) {
                index.forget(known);
            }
        }
    }

    private static Map<String, ClassFingerprint> scan(Fingerprinter fingerprinter, Path... roots)
            throws IOException {
        Map<String, ClassFingerprint> all = new java.util.HashMap<>();
        for (Path root : roots) {
            all.putAll(fingerprinter.scan(root));
        }
        return all;
    }

    private boolean isTestSource(Path source) {
        for (String testRoot : project.getTestCompileSourceRoots()) {
            if (source.startsWith(Path.of(testRoot))) {
                return true;
            }
        }
        return false;
    }

    private static long watermarkOf(List<Path> sources) {
        long newest = 0;
        for (Path source : sources) {
            try {
                newest = Math.max(newest, Files.getLastModifiedTime(source).toMillis());
            } catch (IOException deleted) {
                newest = Math.max(newest, System.currentTimeMillis());
            }
        }
        return newest == 0 ? System.currentTimeMillis() : newest;
    }

    private List<Path> testClasspath() throws MojoExecutionException {
        try {
            List<Path> classpath = new ArrayList<>();
            for (String element : project.getTestClasspathElements()) {
                classpath.add(Path.of(element));
            }
            return classpath;
        } catch (org.apache.maven.artifact.DependencyResolutionRequiredException unresolved) {
            throw new MojoExecutionException("Test dependencies are not resolved", unresolved);
        }
    }

    /**
     * Guarantees the project's classpath carries a {@code junit-platform-launcher}.
     *
     * <p>jiro drives JUnit entirely through the project's own platform jars, which is the only way
     * to avoid the version skew that makes Jupiter silently discover nothing. Most projects never
     * declare the launcher though — Surefire supplies it at test time — so when it is missing jiro
     * resolves the one matching the platform version the project already depends on. Resolving a
     * version jiro chose, rather than one it shipped, is the whole point.
     */
    private List<Path> ensureLauncherPresent(List<Path> classpath) {
        boolean present = classpath.stream()
                .anyMatch(entry -> entry.getFileName().toString().startsWith("junit-platform-launcher"));
        if (present) {
            return classpath;
        }
        String platformVersion = null;
        for (Artifact artifact : project.getArtifacts()) {
            if ("org.junit.platform".equals(artifact.getGroupId())
                    && ("junit-platform-commons".equals(artifact.getArtifactId())
                    || "junit-platform-engine".equals(artifact.getArtifactId()))) {
                platformVersion = artifact.getVersion();
                break;
            }
        }
        if (platformVersion == null) {
            getLog().warn("[jiro] no JUnit Platform on the test classpath; nothing will be discovered");
            return classpath;
        }
        try {
            org.eclipse.aether.resolution.ArtifactResult resolved = repositorySystem.resolveArtifact(
                    repositorySession,
                    new org.eclipse.aether.resolution.ArtifactRequest(
                            new org.eclipse.aether.artifact.DefaultArtifact(
                                    "org.junit.platform", "junit-platform-launcher", "jar", platformVersion),
                            remoteRepositories, null));
            List<Path> extended = new ArrayList<>(classpath);
            extended.add(resolved.getArtifact().getFile().toPath());
            getLog().info("[jiro] added junit-platform-launcher " + platformVersion
                    + " to match the project's platform");
            return extended;
        } catch (org.eclipse.aether.resolution.ArtifactResolutionException unresolved) {
            getLog().warn("[jiro] could not resolve junit-platform-launcher " + platformVersion
                    + "; add it as a test dependency. " + unresolved.getMessage());
            return classpath;
        }
    }

    /** Surefire's default includes, as JUnit Platform class-name regexes. */
    private static final List<String> SUREFIRE_DEFAULT_PATTERNS = List.of(
            "^(.*\\.)?Test[^.$]*$",
            "^(.*\\.)?[^.$]*Test$",
            "^(.*\\.)?[^.$]*Tests$",
            "^(.*\\.)?[^.$]*TestCase$");

    /** The configured patterns as a single alternation, which is what the filter takes. */
    private String classNamePattern() {
        List<String> patterns = testClassPatterns == null || testClassPatterns.isEmpty()
                ? SUREFIRE_DEFAULT_PATTERNS
                : testClassPatterns;
        StringBuilder combined = new StringBuilder();
        for (String pattern : patterns) {
            if (combined.length() > 0) {
                combined.append('|');
            }
            combined.append("(?:").append(pattern).append(')');
        }
        return combined.toString();
    }

    private Path pluginJar(String artifactId) throws MojoExecutionException {
        for (Artifact artifact : pluginArtifacts) {
            if (artifactId.equals(artifact.getArtifactId()) && artifact.getFile() != null) {
                return artifact.getFile().toPath();
            }
        }
        throw new MojoExecutionException("Could not locate " + artifactId + " on the plugin classpath");
    }

    private static List<Path> existingDirectories(List<Path> candidates) {
        List<Path> directories = new ArrayList<>();
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                directories.add(candidate);
            }
        }
        return directories;
    }

    /**
     * Derives instrumentation prefixes from the packages that actually contain compiled classes,
     * then drops any package already covered by a shorter one, so a normal project ends up with a
     * single prefix like {@code com.acme}.
     */
    private static List<String> inferPackages(List<Path> classRoots) throws IOException {
        Set<String> packages = new TreeSet<>();
        for (Path root : classRoots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".class"))
                        .map(path -> root.relativize(path).getParent())
                        .filter(java.util.Objects::nonNull)
                        .map(parent -> parent.toString().replace(java.io.File.separatorChar, '.'))
                        .forEach(packages::add);
            }
        }
        Set<String> minimal = new LinkedHashSet<>();
        for (String candidate : packages) {
            boolean covered = minimal.stream().anyMatch(kept -> candidate.startsWith(kept + "."));
            if (!covered) {
                minimal.add(candidate);
            }
        }
        return new ArrayList<>(minimal);
    }

    private static String shortName(String uniqueId) {
        int lastClass = uniqueId.lastIndexOf("[class:");
        int lastMethod = uniqueId.lastIndexOf("[method:");
        if (lastClass < 0 || lastMethod < 0) {
            return uniqueId;
        }
        String className = uniqueId.substring(lastClass + 7, uniqueId.indexOf(']', lastClass));
        String methodName = uniqueId.substring(lastMethod + 8, uniqueId.indexOf(']', lastMethod));
        return className.substring(className.lastIndexOf('.') + 1) + "." + methodName;
    }
}
