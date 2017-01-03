package org.revapi.maven;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.util.artifact.ArtifactIdUtils;
import org.revapi.ApiAnalyzer;
import org.revapi.DifferenceTransform;
import org.revapi.ElementFilter;
import org.revapi.Reporter;
import org.revapi.Revapi;

/**
 * Common {@link Analyzer} instantiation logic for mojos.
 *
 * @author Lukas Krejci
 * @since 0.8.0
 */
class AnalyzerBuilder {

    private Log log;
    private Locale locale;
    private MavenProject project;
    private boolean skip;
    private String[] oldGavs;
    private String[] newGavs;
    private final Artifact[] oldArtifacts;
    private final Artifact[] newArtifacts;
    private String oldVersion;
    private String newVersion;
    private String disallowedExtensions;
    private Reporter reporter;
    private String analysisConfiguration;
    private Object[] analysisConfigurationFiles;
    private RepositorySystem repositorySystem;
    private RepositorySystemSession repositorySystemSession;
    private boolean failOnMissingConfigurationFiles;
    private boolean failOnUnresolvedArtifacts;
    private boolean failOnUnresolvedDependencies;
    private boolean alwaysCheckForReleaseVersion;
    private boolean checkDependencies;
    private String versionFormat;
    private Revapi revapi;

    public static AnalyzerBuilder forGavs(String[] oldGavs, String[] newGavs) {
        return new AnalyzerBuilder(oldGavs, newGavs, null, null);
    }

    public static AnalyzerBuilder forArtifacts(Artifact[] oldArtifacts, Artifact[] newArtifacts) {
        return new AnalyzerBuilder(null, null, oldArtifacts, newArtifacts);
    }

    private AnalyzerBuilder(String[] oldGavs, String[] newGavs, Artifact[] oldArtifacts, Artifact[] newArtifacts) {
        this.oldGavs = oldGavs;
        this.newGavs = newGavs;
        this.oldArtifacts = oldArtifacts;
        this.newArtifacts = newArtifacts;
    }

    public AnalyzerBuilder withProject(MavenProject project) {
        this.project = project;
        return this;
    }

    public AnalyzerBuilder withSkip(boolean skip) {
        this.skip = skip;
        return this;
    }

    public AnalyzerBuilder withOldVersion(String oldVersion) {
        this.oldVersion = oldVersion;
        return this;
    }

    public AnalyzerBuilder withNewVersion(String newVersion) {
        this.newVersion = newVersion;
        return this;
    }

    public AnalyzerBuilder withDisallowedExtensions(String disallowedExtensions) {
        this.disallowedExtensions = disallowedExtensions;
        return this;
    }

    public AnalyzerBuilder withReporter(Reporter reporter) {
        this.reporter = reporter;
        return this;
    }

    public AnalyzerBuilder withLocale(Locale locale) {
        this.locale = locale;
        return this;
    }

    public AnalyzerBuilder withAnalysisConfiguration(String analysisConfiguration) {
        this.analysisConfiguration = analysisConfiguration;
        return this;
    }

    public AnalyzerBuilder withAnalysisConfigurationFiles(Object[] analysisConfigurationFiles) {
        this.analysisConfigurationFiles = analysisConfigurationFiles;
        return this;
    }

    public AnalyzerBuilder withRepositorySystem(RepositorySystem repositorySystem) {
        this.repositorySystem = repositorySystem;
        return this;
    }

    public AnalyzerBuilder withRepositorySystemSession(RepositorySystemSession repositorySystemSession) {
        this.repositorySystemSession = repositorySystemSession;
        return this;
    }

    public AnalyzerBuilder withFailOnMissingConfigurationFiles(boolean failOnMissingConfigurationFiles) {
        this.failOnMissingConfigurationFiles = failOnMissingConfigurationFiles;
        return this;
    }

    public AnalyzerBuilder withFailOnUnresolvedDependencies(boolean failOnUnresolvedDependencies) {
        this.failOnUnresolvedDependencies = failOnUnresolvedDependencies;
        return this;
    }

    public AnalyzerBuilder withFailOnUnresolvedArtifacts(boolean failOnUnresolvedArtifacts) {
        this.failOnUnresolvedArtifacts = failOnUnresolvedArtifacts;
        return this;
    }

    public AnalyzerBuilder withAlwaysCheckForReleasedVersion(boolean alwaysCheckForReleaseVersion) {
        this.alwaysCheckForReleaseVersion = alwaysCheckForReleaseVersion;
        return this;
    }

    public AnalyzerBuilder withCheckDependencies(boolean checkDependencies) {
        this.checkDependencies = checkDependencies;
        return this;
    }

    public AnalyzerBuilder withVersionFormat(String versionFormat) {
        this.versionFormat = versionFormat;
        return this;
    }

    public AnalyzerBuilder withLog(Log log) {
        this.log = log;
        return this;
    }

    AnalyzerBuilder withRevapiInstance(Revapi revapi) {
        this.revapi = revapi;
        return this;
    }

    public Result build() {
        Result res = new Result();

        res.isOnClasspath = initializeComparisonArtifacts();

        res.newArtifacts = newGavs;
        res.oldArtifacts = oldGavs;

        res.skip = skip;

        res.analyzer = prepareAnalyzer();

        return res;
    }

    private Analyzer prepareAnalyzer() {
        if (skip) {
            return null;
        }


        final List<String> disallowedExtensions = this.disallowedExtensions == null
                ? Collections.emptyList()
                : Arrays.asList(this.disallowedExtensions.split("\\s*,\\s*"));

        Supplier<Revapi.Builder> ctor = getDisallowedExtensionsAwareRevapiConstructor(disallowedExtensions);

        return new Analyzer(analysisConfiguration, analysisConfigurationFiles, oldArtifacts, newArtifacts, oldGavs,
                newGavs, project, repositorySystem, repositorySystemSession, reporter, locale, log,
                failOnMissingConfigurationFiles, failOnUnresolvedArtifacts, failOnUnresolvedDependencies,
                alwaysCheckForReleaseVersion, checkDependencies, versionFormat, ctor, revapi);
    }

    /**
     * @return true if artifacts are initialized, false if not and the analysis should not proceed
     */
    private boolean initializeComparisonArtifacts() {
        if (oldArtifacts == null) {
            return initializeComparisonGavs();
        } else {
            String coords = Analyzer.getProjectArtifactCoordinates(project, null);

            boolean projectInOlds = Stream.of(oldArtifacts).anyMatch(a -> ArtifactIdUtils.toId(a).equals(coords));
            boolean projectInNews = Stream.of(newArtifacts).anyMatch(a -> ArtifactIdUtils.toId(a).equals(coords));

            if ((projectInOlds || projectInNews) && !project.getArtifact().getArtifactHandler().isAddedToClasspath()) {
                return false;
            }

            return true;
        }
    }

    private boolean initializeComparisonGavs() {
        if (newGavs != null && newGavs.length == 1 && "BUILD".equals(newGavs[0])) {
            log.warn("\"BUILD\" coordinates are deprecated. Just leave \"newArtifacts\" undefined and specify" +
                    " \"${project.version}\" as the value for \"newVersion\" (which is the default, so you don't" +
                    " actually have to do that either).");
            oldGavs = null;
        }

        if (oldGavs == null || oldGavs.length == 0) {
            //non-intuitively, we need to initialize the artifacts even if we will not proceed with the analysis itself
            //that's because we need know the versions when figuring out the version modifications -
            //see AbstractVersionModifyingMojo
            oldGavs = new String[]{
                    Analyzer.getProjectArtifactCoordinates(project, oldVersion)};

            //bail out quickly for POM artifacts (or any other packaging without a file result) - there's nothing we can
            //analyze there
            //only do it here, because oldArtifacts might point to another artifact.
            //if we end up here in this branch, we know we'll be comparing the current artifact with something.
            if (!project.getArtifact().getArtifactHandler().isAddedToClasspath()) {
                return false;
            }
        }

        if (newGavs == null || newGavs.length == 0) {
            newGavs = new String[]{
                    Analyzer.getProjectArtifactCoordinates(project, newVersion)};

            //bail out quickly for POM artifacts (or any other packaging without a file result) - there's nothing we can
            //analyze there
            //again, do this check only here, because oldArtifact might point elsewhere. But if we end up here, it
            //means that oldArtifacts would be compared against the current artifact (in some version). Comparing
            //against a POM artifact is always no-op.
            if (!project.getArtifact().getArtifactHandler().isAddedToClasspath()) {
                return false;
            }
        }

        return true;
    }

    private static Supplier<Revapi.Builder>
    getDisallowedExtensionsAwareRevapiConstructor(List<String> disallowedExtensions) {
        return () -> {
            Revapi.Builder bld = Revapi.builder();

            List<ApiAnalyzer> analyzers = new ArrayList<>();
            List<ElementFilter> filters = new ArrayList<>();
            List<DifferenceTransform<?>> transforms = new ArrayList<>();
            List<Reporter> reporters = new ArrayList<>();

            addAllAllowed(analyzers, ServiceLoader.load(ApiAnalyzer.class), disallowedExtensions);
            addAllAllowed(filters, ServiceLoader.load(ElementFilter.class), disallowedExtensions);
            addAllAllowed(transforms, ServiceLoader.load(DifferenceTransform.class), disallowedExtensions);
            addAllAllowed(reporters, ServiceLoader.load(Reporter.class), disallowedExtensions);

            bld.withAnalyzers(analyzers).withFilters(filters).withTransforms(transforms).withReporters(reporters);

            return bld;
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> void addAllAllowed(List<T> list, Iterable<?> candidates, List<String> disallowedClassNames) {
        for (Object o : candidates) {
            if (o != null && !disallowedClassNames.contains(o.getClass().getName())) {
                list.add((T) o);
            }
        }
    }

    static class Result {
        boolean skip;
        boolean isOnClasspath;
        String[] oldArtifacts;
        String[] newArtifacts;
        Analyzer analyzer;

        private Result() {}
    }
}
