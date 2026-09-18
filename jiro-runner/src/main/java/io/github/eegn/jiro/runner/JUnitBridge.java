package io.github.eegn.jiro.runner;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Drives the JUnit Platform entirely through reflection, against classes loaded from the project.
 *
 * <p>The runner deliberately has no compile-time dependency on JUnit at all. If it did, its own
 * copy would be on the fork's system classpath and would win for every {@code org.junit.*} class,
 * which is precisely the version skew that made Jupiter discover zero tests. Binding late, through
 * whatever the project actually built against, is the only arrangement that cannot skew.
 *
 * <p>The cost is this class. It is tedious but shallow: every method here is one reflective call
 * against an API that has been stable across the whole 1.x line.
 */
final class JUnitBridge {

    private final Class<?> launcherType;
    private final Class<?> requestType;
    private final Class<?> listenerType;
    private final Class<?> testPlanType;
    private final Class<?> identifierType;
    private final Class<?> resultType;
    private final Class<?> selectorsType;
    private final Class<?> builderType;
    private final Class<?> factoryType;
    private final Class<?> filterType;
    private final Class<?> classNameFilterType;

    JUnitBridge(ClassLoader application) throws ClassNotFoundException {
        this.launcherType = application.loadClass("org.junit.platform.launcher.Launcher");
        this.requestType = application.loadClass("org.junit.platform.launcher.LauncherDiscoveryRequest");
        this.listenerType = application.loadClass("org.junit.platform.launcher.TestExecutionListener");
        this.testPlanType = application.loadClass("org.junit.platform.launcher.TestPlan");
        this.identifierType = application.loadClass("org.junit.platform.launcher.TestIdentifier");
        this.resultType = application.loadClass("org.junit.platform.engine.TestExecutionResult");
        this.selectorsType = application.loadClass("org.junit.platform.engine.discovery.DiscoverySelectors");
        this.builderType = application.loadClass(
                "org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder");
        this.factoryType = application.loadClass("org.junit.platform.launcher.core.LauncherFactory");
        this.filterType = application.loadClass("org.junit.platform.engine.Filter");
        this.classNameFilterType =
                application.loadClass("org.junit.platform.engine.discovery.ClassNameFilter");
    }

    Object newLauncher() throws ReflectiveOperationException {
        return factoryType.getMethod("create").invoke(null);
    }

    /**
     * A request that scans the given output directories, keeping only classes whose fully qualified
     * name matches {@code classNamePattern}.
     *
     * <p>The filter is what keeps jiro aligned with {@code mvn test}. Scanning the classpath roots
     * unfiltered also finds the {@code *IT} classes Failsafe owns, and running those outside the
     * integration environment they expect produces a wall of context-loading failures that says
     * nothing about the developer's edit.
     */
    Object requestForRoots(Set<Path> roots, String classNamePattern)
            throws ReflectiveOperationException {
        Object selectors = selectorsType.getMethod("selectClasspathRoots", Set.class)
                .invoke(null, roots);
        Object builder = builderType.getMethod("request").invoke(null);
        builder = builderType.getMethod("selectors", List.class).invoke(builder, selectors);
        if (classNamePattern != null && !classNamePattern.isBlank()) {
            Object patterns = java.lang.reflect.Array.newInstance(String.class, 1);
            java.lang.reflect.Array.set(patterns, 0, classNamePattern);
            Object filter = classNameFilterType
                    .getMethod("includeClassNamePatterns", patterns.getClass())
                    .invoke(null, patterns);
            Object filters = java.lang.reflect.Array.newInstance(filterType, 1);
            java.lang.reflect.Array.set(filters, 0, filter);
            builder = builderType.getMethod("filters", filters.getClass()).invoke(builder, filters);
        }
        return builderType.getMethod("build").invoke(builder);
    }

    /** A request naming exactly the tests the selector chose. */
    Object requestForUniqueIds(List<String> uniqueIds) throws ReflectiveOperationException {
        Method selectUniqueId = selectorsType.getMethod("selectUniqueId", String.class);
        List<Object> selectors = new ArrayList<>(uniqueIds.size());
        for (String uniqueId : uniqueIds) {
            selectors.add(selectUniqueId.invoke(null, uniqueId));
        }
        return buildRequest(selectors);
    }

    private Object buildRequest(List<?> selectors) throws ReflectiveOperationException {
        Object builder = builderType.getMethod("request").invoke(null);
        builder = builderType.getMethod("selectors", List.class).invoke(builder, selectors);
        return builderType.getMethod("build").invoke(builder);
    }

    Object discover(Object launcher, Object request) throws ReflectiveOperationException {
        return launcherType.getMethod("discover", requestType).invoke(launcher, request);
    }

    void execute(Object launcher, Object request, Object listener) throws ReflectiveOperationException {
        Object listeners = java.lang.reflect.Array.newInstance(listenerType, 1);
        java.lang.reflect.Array.set(listeners, 0, listener);
        launcherType.getMethod("execute", requestType, listeners.getClass())
                .invoke(launcher, request, listeners);
    }

    /** Unique ids of every leaf test in a discovered plan. */
    List<String> testUniqueIds(Object testPlan) throws ReflectiveOperationException {
        Method getDescendants = testPlanType.getMethod("getDescendants", identifierType);
        Set<?> roots = (Set<?>) testPlanType.getMethod("getRoots").invoke(testPlan);
        List<String> uniqueIds = new ArrayList<>();
        for (Object root : roots) {
            for (Object identifier : (Set<?>) getDescendants.invoke(testPlan, root)) {
                if (isTest(identifier)) {
                    uniqueIds.add(uniqueId(identifier));
                }
            }
        }
        return uniqueIds;
    }

    Object newListenerProxy(ClassLoader application, InvocationHandler handler) {
        return Proxy.newProxyInstance(application, new Class<?>[]{listenerType}, handler);
    }

    boolean isTest(Object identifier) throws ReflectiveOperationException {
        return (boolean) identifierType.getMethod("isTest").invoke(identifier);
    }

    String uniqueId(Object identifier) throws ReflectiveOperationException {
        return (String) identifierType.getMethod("getUniqueId").invoke(identifier);
    }

    String displayName(Object identifier) throws ReflectiveOperationException {
        return (String) identifierType.getMethod("getDisplayName").invoke(identifier);
    }

    /**
     * Whether this identifier is a test class rather than an engine or a nested grouping. Compared
     * by class name instead of {@code instanceof} because the source type is loaded by the
     * application's classloader, not this one.
     */
    boolean isClassSource(Object identifier) throws ReflectiveOperationException {
        Object source = identifierType.getMethod("getSource").invoke(identifier);
        Optional<?> maybe = (Optional<?>) source;
        return maybe.isPresent() && maybe.get().getClass().getName()
                .equals("org.junit.platform.engine.support.descriptor.ClassSource");
    }

    String status(Object result) throws ReflectiveOperationException {
        return String.valueOf(resultType.getMethod("getStatus").invoke(result));
    }

    String failure(Object result) throws ReflectiveOperationException {
        Optional<?> throwable = (Optional<?>) resultType.getMethod("getThrowable").invoke(result);
        return throwable.map(String::valueOf).orElse(null);
    }
}
