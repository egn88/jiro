package io.github.eegn.jiro.maven;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;

/**
 * Resolves {@code jiro-rewrite} and its OpenRewrite dependencies through Maven, and instantiates
 * the service in a classloader of its own.
 *
 * <p>The loader's parent is the <em>platform</em> loader, not the plugin's realm. OpenRewrite
 * brings Jackson, its own javac bridge and a few dozen other jars; none of them may meet the copies
 * Maven or jiro carry, in either direction. The only types crossing the
 * boundary are {@code java.base} ones: the service is a
 * {@code Function<Map<String, Object>, Map<String, Object>>} built from a
 * {@code Map<String, Object>} of {@code Path}, {@code List}, {@code String}, a {@code Lock} and a
 * {@code Consumer}.
 */
final class RewriteServiceLoader {

    static final String GROUP_ID = "io.github.egn88";
    static final String ARTIFACT_ID = "jiro-rewrite";
    static final String SERVICE_CLASS = "io.github.eegn.jiro.rewrite.RewriteService";

    private RewriteServiceLoader() {
    }

    @SuppressWarnings("unchecked")
    static Function<Map<String, Object>, Map<String, Object>> load(
            RepositorySystem repositorySystem, RepositorySystemSession session,
            List<RemoteRepository> repositories, String version, Map<String, Object> config)
            throws Exception {
        CollectRequest collect = new CollectRequest(
                new Dependency(new DefaultArtifact(GROUP_ID, ARTIFACT_ID, "jar", version), "runtime"),
                repositories);
        DependencyFilter runtimeOnly = (node, parents) -> {
            Dependency dependency = node.getDependency();
            if (dependency == null) {
                return true;
            }
            String scope = dependency.getScope();
            return !dependency.isOptional()
                    && (scope.isEmpty() || "compile".equals(scope) || "runtime".equals(scope));
        };
        List<ArtifactResult> resolved = repositorySystem
                .resolveDependencies(session, new DependencyRequest(collect, runtimeOnly))
                .getArtifactResults();

        List<URL> urls = new ArrayList<>();
        for (ArtifactResult result : resolved) {
            urls.add(result.getArtifact().getFile().toURI().toURL());
        }
        URLClassLoader loader = new URLClassLoader("jiro-rewrite", urls.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader());
        Class<?> type = loader.loadClass(SERVICE_CLASS);
        return (Function<Map<String, Object>, Map<String, Object>>)
                type.getConstructor(Map.class).newInstance(config);
    }
}
