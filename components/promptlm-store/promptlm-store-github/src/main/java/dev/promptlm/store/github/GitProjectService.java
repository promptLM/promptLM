/*
 * Copyright 2025 promptLM
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.promptlm.store.github;

import dev.promptlm.domain.AppContext;
import dev.promptlm.domain.events.ProjectCreatedEvent;
import dev.promptlm.domain.projectspec.ProjectSpec;
import dev.promptlm.repository.template.ArtifactCoordinateSanitizer;
import dev.promptlm.repository.template.RepositoryTemplateExtractor;
import dev.promptlm.repository.template.TemplateContext;
import dev.promptlm.repository.template.TemplateSubstitutionEngine;
import dev.promptlm.store.api.ProjectService;
import dev.promptlm.store.api.ReleaseProvider;
import dev.promptlm.store.api.RemoteRepositoryAlreadyExistsException;
import dev.promptlm.store.api.RepositoryGenerationConfig;
import dev.promptlm.store.api.RepositoryOwner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

@Component
public class GitProjectService implements ProjectService {

    private static final Logger log = LoggerFactory.getLogger(GitProjectService.class);
    public static final String DEVELOPMENT_BRANCH = "development";
    static final String DEFAULT_PROJECT_DESCRIPTION = "prompts managed by promptLM";
    private final AppContext appContext;
    private final GitHubProperties gitHubProperties;
    private final Git git;
    private final ApplicationEventPublisher eventPublisher;
    private final RepositoryTemplateExtractor repositoryTemplateExtractor;
    private final RemoteGitRepositoryProvisioner remoteGitRepositoryProvisioner;
    private final TrustedRemotePolicy trustedRemotePolicy;
    private final LocalWorkspacePathPolicy localWorkspacePathPolicy;

    public GitProjectService(AppContext appContext,
                             GitHubProperties gitHubProperties,
                             Git git,
                             ApplicationEventPublisher eventPublisher,
                             RepositoryTemplateExtractor repositoryTemplateExtractor,
                             RemoteGitRepositoryProvisioner remoteGitRepositoryProvisioner,
                             TrustedRemotePolicy trustedRemotePolicy,
                             LocalWorkspacePathPolicy localWorkspacePathPolicy) {

        this.appContext = appContext;
        this.gitHubProperties = gitHubProperties;
        this.git = git;
        this.eventPublisher = eventPublisher;
        this.repositoryTemplateExtractor = repositoryTemplateExtractor;
        this.remoteGitRepositoryProvisioner = remoteGitRepositoryProvisioner;
        this.trustedRemotePolicy = trustedRemotePolicy;
        this.localWorkspacePathPolicy = localWorkspacePathPolicy;
    }

    @Override
    public ProjectSpec newProject(Path baseDir, String projectName) throws RemoteRepositoryAlreadyExistsException {
        OwnerAndRepo ownerAndRepo = resolveOwnerAndRepo(null, projectName);
        return newProject(baseDir, ownerAndRepo);
    }

    @Override
    public ProjectSpec newProject(Path baseDir, String owner, String projectName) throws RemoteRepositoryAlreadyExistsException {
        OwnerAndRepo ownerAndRepo = resolveOwnerAndRepo(owner, projectName);
        return newProject(baseDir, ownerAndRepo);
    }

    private ProjectSpec newProject(Path baseDir, OwnerAndRepo ownerAndRepo) throws RemoteRepositoryAlreadyExistsException {
        Path validatedBaseDir = localWorkspacePathPolicy.assertWithinWorkspace(baseDir, "repoDir");
        Path repoPath = localWorkspacePathPolicy.assertWithinWorkspace(
                validatedBaseDir.resolve(ownerAndRepo.repo()),
                "repoDir"
        );

        ProjectSpec projectSpec = new ProjectSpec();
        RemoteRepository repository = createRemoteRepository(ownerAndRepo);

        String gitCloneUrl = repository.getHttpTransportUrl();
        log.debug("Using Git clone URL: {}", gitCloneUrl);

        git.createRepository(repoPath, gitCloneUrl);

        // Derive artifact-coordinate defaults from owner/repo so the rolled-out repository
        // never ships literal REPLACE_ME_* sentinels in pom.xml / .promptlm/artifacts.toml.
        // Users can still override these by editing the generated files. See issue #323.
        String repo = ownerAndRepo.repo();
        String owner = ownerAndRepo.owner();
        TemplateContext templateContext = new TemplateContext(
                repo,
                owner,
                DEFAULT_PROJECT_DESCRIPTION,
                Instant.now(),
                TemplateSubstitutionEngine.BUILD_GENERATOR_VERSION,
                ArtifactCoordinateSanitizer.projectName(repo),
                ArtifactCoordinateSanitizer.mavenGroupId(owner),
                ArtifactCoordinateSanitizer.mavenArtifactId(repo),
                ArtifactCoordinateSanitizer.pythonDistributionName(repo),
                ArtifactCoordinateSanitizer.pythonImportName(repo),
                ArtifactCoordinateSanitizer.npmPackageName(repo)
        );
        repositoryTemplateExtractor.extractTo(repoPath, templateContext);

        git.addAllAndCommit(repoPath.toFile(), "initial commit");
        push(repoPath);

        git.checkoutOrCreateBranch(DEVELOPMENT_BRANCH, repoPath.toFile());

        projectSpec.setOrg(repository.getOwnerName());
        projectSpec.setName(ownerAndRepo.repo());
        projectSpec.setRepoDir(repoPath);
        projectSpec.setRepoUrl(repository.getHtmlUrl().toExternalForm());

        appContext.addProject(projectSpec);
        appContext.setActiveProject(projectSpec);

        push(repoPath);

        eventPublisher.publishEvent(new ProjectCreatedEvent(projectSpec));

        return projectSpec;
    }

    private void push(Path repoPath) {
        try {
            git.pushAll(repoPath.toFile());
        } catch (Exception pushError) {
            throw new RuntimeException("Git push failed: %s. Find the project here '%s' to fix issues manually.".formatted(pushError.getMessage(), repoPath.toString()), pushError);
        }
    }

    @Override
    public ProjectSpec importProject(String repoUrl, Path targetDir) {
        return importProject(URI.create(repoUrl), targetDir);
    }

    @Override
    public ProjectSpec importProject(URI remoteUrl, Path targetDir) {
        URI trustedRemoteUrl = trustedRemotePolicy.assertCloneImportRemoteAllowed(remoteUrl);
        Path validatedTargetDir = localWorkspacePathPolicy.assertWithinWorkspace(targetDir, "targetDir");
        ProjectSpec projectSpec = new ProjectSpec();

        String path = trustedRemoteUrl.getPath(); // e.g. /owner/repo.git

        // Remove leading '/' and optional trailing '.git'
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.endsWith(".git")) {
            path = path.substring(0, path.length() - 4);
        }

        String[] parts = path.split("/");
        if (parts.length >= 2) {
            String owner = parts[0];
            String repo = parts[1];

            git.cloneRepository(trustedRemoteUrl.toString(), validatedTargetDir.toFile());

            projectSpec.setOrg(owner);
            projectSpec.setName(repo);
            projectSpec.setRepoDir(localWorkspacePathPolicy.assertWithinWorkspace(
                    validatedTargetDir.resolve(repo),
                    "targetDir"
            ));
            projectSpec.setRepoUrl(trustedRemoteUrl.toString());

            appContext.addProject(projectSpec);
            appContext.setActiveProject(projectSpec);

            return projectSpec;
        }

        throw new IllegalArgumentException("Invalid GitHub repository path: " + path);
    }

    @Override
    public ProjectSpec switchProject(Path path) {
        Path normalizedPath = path.toAbsolutePath().normalize();
        ProjectSpec projectSpec = appContext.getProjects().stream()
                .filter(project -> project.getRepoDir() != null
                        && project.getRepoDir().toAbsolutePath().normalize().equals(normalizedPath))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + normalizedPath));
        appContext.setActiveProject(projectSpec);
        return projectSpec;
    }

    @Override
    public ProjectSpec connectProject(Path repoPath) {
        Path validatedRepoPath = localWorkspacePathPolicy.assertWithinWorkspace(repoPath, "repoPath");
        if (isPromptLMRepository(validatedRepoPath)) {
            ProjectSpec projectSpec = ProjectSpec.fromRepo(validatedRepoPath);
            projectSpec.setRepoDir(validatedRepoPath);
            appContext.setActiveProject(projectSpec);
            return projectSpec;
        } else {
            throw new IllegalArgumentException("Invalid GitHub repository path: " + validatedRepoPath);
        }
    }

    @Override
    public List<RepositoryOwner> listAvailableOwners() {
        return remoteGitRepositoryProvisioner.listAvailableOwners();
    }

    private boolean isPromptLMRepository(Path repoPath) {
        return repoPath.resolve(".git").toFile().exists() &&
                repoPath.resolve(".git").toFile().isDirectory() &&
                repoPath.resolve(".promptlm").toFile() != null &&
                repoPath.resolve(".promptlm").toFile().isDirectory();
    }

    public RemoteRepository createRemoteRepository(OwnerAndRepo ownerAndRepo) throws RemoteRepositoryAlreadyExistsException {
        if (repositoryExists(ownerAndRepo)) {
            throw new RemoteRepositoryAlreadyExistsException(
                    gitHubProperties.getBaseUrl(),
                    ownerAndRepo.owner(),
                    ownerAndRepo.repo()
            );
        }
        // TODO(#275): drive description / defaultBranch / release config from
        // promptlm.yml once the progressive-disclosure (Mode 1 default + opt-in
        // promotion) UX ships. Until then, #276 has us default new repositories
        // to release-enabled (Mode 2) so the full release pipeline is reachable
        // without a user-facing "Enable releases" toggle. Revert this branch
        // back to RepositoryGenerationConfig.defaults(...) when #275 lands.
        RepositoryGenerationConfig config = new RepositoryGenerationConfig(
                ownerAndRepo.repo(),
                ownerAndRepo.owner(),
                RepositoryGenerationConfig.DEFAULT_DESCRIPTION,
                RepositoryGenerationConfig.DEFAULT_BRANCH,
                true,
                ReleaseProvider.GITHUB_ACTIONS
        );
        return remoteGitRepositoryProvisioner.createRemoteRepository(config);
    }


    public boolean repositoryExists(String name) {
        OwnerAndRepo ownerAndRepo = resolveOwnerAndRepo(name);
        return repositoryExists(ownerAndRepo);
    }

    public boolean repositoryExists(OwnerAndRepo ownerAndRepo) {
        return remoteGitRepositoryProvisioner.repositoryExists(
                gitHubProperties.getBaseUrl(),
                ownerAndRepo.owner(),
                ownerAndRepo.repo());
    }

    private OwnerAndRepo resolveOwnerAndRepo(String name) {
        return resolveOwnerAndRepo(null, name);
    }

    private OwnerAndRepo resolveOwnerAndRepo(String explicitOwner, String name) {
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("Project name must not be empty");
        }
        String owner = explicitOwner;
        String repo;
        if (name.contains("/")) {
            String[] parts = name.split("/", 2);
            owner = parts[0];
            repo = parts[1];
        } else if (StringUtils.hasText(owner)) {
            owner = owner.trim();
            repo = name;
        } else if (StringUtils.hasText(gitHubProperties.getUsername())) {
            owner = gitHubProperties.getUsername().trim();
            repo = name;
        } else {
            owner = remoteGitRepositoryProvisioner.getDefaultOwner();
            if (!StringUtils.hasText(owner)) {
                throw new IllegalStateException("Unable to determine repository owner from name, properties, or local git config");
            }
            repo = name;
        }
        return new OwnerAndRepo(owner, repo);
    }

    private record OwnerAndRepo(String owner, String repo) {}
}
