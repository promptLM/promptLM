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
import dev.promptlm.domain.promptspec.PromptSpec;
import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.errors.*;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.merge.ContentMergeStrategy;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

@Component
class Git {

    private static final Logger log = LoggerFactory.getLogger(Git.class);
    public static final String INITIAL_BRANCH = "main";
    private static String mainBranch = "main";
    public static final String MAIN_BRANCH = mainBranch;
    private final EnvGitCredentialsProvider credentialsProvider;
    private final TrustedRemotePolicy trustedRemotePolicy;
    private final AppContext appContext;

    public Git(EnvGitCredentialsProvider credentialsProvider,
               TrustedRemotePolicy trustedRemotePolicy,
               AppContext appContext) {
        this.credentialsProvider = credentialsProvider;
        this.trustedRemotePolicy = trustedRemotePolicy;
        this.appContext = appContext;
    }

    /**
     * Creates a new repository.
     *
     * @param targetPath the path to the directory where the store should be created
     * @param remoteUrl  the URL of the remote repository
     * @throws GitException if there is an error initializing the store
     */
    public void createRepository(Path targetPath, String remoteUrl) {
        try {
            File repoDir = targetPath.toFile();
            createDirIfNotExists(repoDir);
            org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.init().setInitialBranch(INITIAL_BRANCH).setDirectory(repoDir).call();
            URIish uri = new URIish(remoteUrl);
            git.remoteAdd()
                    .setName("origin")
                    .setUri(uri)
                    .call();
            git.close();
        } catch (GitAPIException e) {
            throw new GitException("Failed to initialize store", e);
        } catch (URISyntaxException e) {
            throw new GitException("Invalid remote URL: " + remoteUrl, e);
        }
    }

    private static void createDirIfNotExists(File repoDir) {
        if (!repoDir.exists()) {
            boolean created = repoDir.mkdirs();
            if (!created) {
                throw new GitException("Failed to create store directory: " + repoDir);
            }
        }
    }

    public static Ref createBranch(File repoPath, String branchName) {
        org.eclipse.jgit.api.Git git = open(repoPath);
        if (branchExists(git, branchName)) {
            return checkout(git, branchName);
        } else {
            Ref newBranch = null;
            try {
                newBranch = git.branchCreate()
                        .setName(branchName)
                        .call();
                log.debug("Created branch: " + newBranch.getName());
                return newBranch;
            } catch (GitAPIException e) {
                throw new GitException("Failed to create branch " + branchName, e);
            }
        }
    }

    private static Ref checkout(org.eclipse.jgit.api.Git git, String branchName) {
        try {
            Ref ref = git.checkout().setName(branchName).call();
            return ref;
        } catch (GitAPIException e) {
            throw new GitException("Failed to checkout branch " + branchName, e);
        }
    }

    private static org.eclipse.jgit.api.Git open(File repoPath) {
        try {
            return org.eclipse.jgit.api.Git.open(repoPath);
        } catch (IOException e) {
            throw new GitException("Failed to open store " + repoPath, e);
        }
    }

    private static boolean branchExists(org.eclipse.jgit.api.Git repoPath, String branchName) {
        try {
            List<Ref> branches = repoPath.branchList().call();
            for (Ref branch : branches) {
                String foundBranch = branch.getName();
                // Branch names are stored as refs/heads/branchName
                if (foundBranch.endsWith("/" + branchName)) {
                    return true;
                }
            }
            return false;
        } catch (GitAPIException e) {
            throw new GitException("Failed to list branches", e);
        }
    }

    public RevCommit addAllAndCommit(File repoPath, String commitMessage) {
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(repoPath)) {
            addAllFiles(git, repoPath.toPath());
            RevCommit call = git.commit().setMessage(commitMessage).call();
            log.debug("Committed all changes with message: " + commitMessage);
            return call;
        } catch(GitAPIException rnfe) {
            throw new GitException(rnfe.getMessage(), rnfe);
        } catch (IOException e) {
            throw new GitException("Failed to commit all changes", e);
        }
    }

    /**
     * Stage all current changes and amend the HEAD commit with them, preserving the existing
     * commit message. Used by the deferred-push save flow (issue #352) so the remote only
     * ever sees the final with-response version of a save commit.
     *
     * <p>If HEAD has no parent commit yet (fresh repo with a single initial commit), JGit's
     * amend still works as long as a HEAD commit exists. The caller is responsible for
     * ensuring there is a commit to amend.
     */
    public RevCommit amendHead(File repoPath) {
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(repoPath)) {
            addAllFiles(git, repoPath.toPath());
            // setAmend(true) without setMessage(...) preserves the existing HEAD message.
            RevCommit call = git.commit().setAmend(true).call();
            log.debug("Amended HEAD commit: {}", call.name());
            return call;
        } catch (GitAPIException e) {
            throw new GitException("Failed to amend HEAD commit", e);
        } catch (IOException e) {
            throw new GitException("Failed to amend HEAD commit", e);
        }
    }

    public static void add(File repoPath, String filepattern) throws GitAPIException {
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(repoPath)) {
            add(git, filepattern);
        } catch (IOException e) {
            throw new GitException("Failed to open store " + repoPath, e);
        }
    }

    private static void add(org.eclipse.jgit.api.Git git, String filepattern) {
        try {
            git.add().addFilepattern(filepattern).call();
        } catch (GitAPIException e) {
            throw new GitException("Failed to add file pattern " + filepattern, e);
        }
    }

    /**
     * Stages all files, including entries under dot-directories (e.g. .github, .promptlm).
     * JGit path pattern "." can miss hidden top-level directories in some setups.
     */
    private static void addAllFiles(org.eclipse.jgit.api.Git git, Path repoRoot) {
        List<String> filePatterns;
        try (Stream<Path> files = Files.walk(repoRoot)) {
            filePatterns = files
                    .filter(Files::isRegularFile)
                    .map(repoRoot::relativize)
                    .map(path -> path.toString().replace(File.separatorChar, '/'))
                    .filter(path -> !path.startsWith(".git/"))
                    .toList();
        } catch (IOException e) {
            throw new GitException("Failed to collect files for staging in " + repoRoot, e);
        }

        if (filePatterns.isEmpty()) {
            return;
        }

        try {
            AddCommand addCommand = git.add();
            for (String filePattern : filePatterns) {
                addCommand.addFilepattern(filePattern);
            }
            addCommand.call();
        } catch (GitAPIException e) {
            throw new GitException("Failed to stage all files in " + repoRoot, e);
        }
    }

    public static void mergeSwitchAndRemove(File repoPath) {
        String curBranch = rebaseMain(repoPath);
        deleteBranch(repoPath, curBranch);
    }

    private static void deleteBranch(File repoPath, String curBranch) {
        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        try (Repository repository = builder.setGitDir(new File(repoPath, ".git"))
                .readEnvironment()
                .findGitDir()
                .build();
             org.eclipse.jgit.api.Git git = new org.eclipse.jgit.api.Git(repository)) {
            git.branchDelete().setBranchNames(curBranch).setForce(true).call();
            log.debug("Deleted branch: " + curBranch);
        } catch (Exception e) {
            throw new GitException("Failed to delete branch " + curBranch, e);
        }
    }

    public static String rebaseMain(File repoPath) {
        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        try (Repository repository = builder.setGitDir(new File(repoPath, ".git"))
                .readEnvironment()
                .findGitDir()
                .build();
             org.eclipse.jgit.api.Git git = new org.eclipse.jgit.api.Git(repository)) {

            String curBranch = repository.getBranch();
            log.debug("Currently checked-out branch: " + curBranch);

            if (curBranch.equals(MAIN_BRANCH)) {
                log.debug("Cannot rebase or delete the main branch!");
                return curBranch;
            }

            git.checkout().setName(MAIN_BRANCH).call();

            git.rebase()
                    .setContentMergeStrategy(ContentMergeStrategy.OURS)
                    .setUpstream(curBranch)
                    .call();

            log.debug("Rebased " + curBranch + " onto " + mainBranch);
            log.debug("Current branch: " + mainBranch);
            return curBranch;
        } catch (IOException | GitAPIException e) {
            throw new RuntimeException("Error during merge and cleanup", e);
        }
    }

    public static String createId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    public File cloneRepository(String repoUrl, File targetDir) {
        try {
            Path target = targetDir.toPath().toAbsolutePath().normalize();
            if (!Files.exists(target)) {
                try {
                    Files.createDirectories(target);
                } catch (IOException e) {
                    throw new GitException("Failed to create target directory " + target, e);
                }
            }
            org.eclipse.jgit.api.CloneCommand cloneCommand = org.eclipse.jgit.api.Git.cloneRepository()
                    .setURI(repoUrl)
                    .setDirectory(target.resolve(this.extractRepoName(repoUrl)).toFile());
            CredentialsProvider credentials = resolveCredentialsForRemote(repoUrl);
            if (credentials != null) {
                cloneCommand.setCredentialsProvider(credentials);
            }
            org.eclipse.jgit.api.Git repo = cloneCommand.call();
            Repository repository = repo.getRepository();
            File repoDir = repository.getDirectory();
            return repoDir;
        } catch (GitAPIException e) {
            throw new GitException("Failed to clone store from " + repoUrl, e);
        }
    }

    public void pushAll(File repo) {
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(repo)) {
            String branch = git.getRepository().getBranch();
            String remoteUrl = git.getRepository().getConfig().getString("remote", "origin", "url");
            log.debug("Pushing to remote: {}", remoteUrl);
            RefSpec refSpec = new RefSpec(branch + ":refs/heads/" + branch);
            PushCommand pushCommand = git.push()
                    .setRemote("origin")
                    .setRefSpecs(refSpec)
                    .setPushTags();
            CredentialsProvider credentials = resolveCredentialsForRemote(remoteUrl);
            if (credentials != null) {
                pushCommand.setCredentialsProvider(credentials);
            }

            Iterable<PushResult> pushResults = pushCommand.call();
            assertPushSucceeded(pushResults, remoteUrl);

        } catch (IOException | GitAPIException e) {
            throw new GitException("Failed to pushAll store", e);
        }
    }

    private static void assertPushSucceeded(Iterable<PushResult> pushResults, String remoteUrl) {
        for (PushResult pushResult : pushResults) {
            for (RemoteRefUpdate update : pushResult.getRemoteUpdates()) {
                RemoteRefUpdate.Status status = update.getStatus();
                if (status == RemoteRefUpdate.Status.OK || status == RemoteRefUpdate.Status.UP_TO_DATE) {
                    continue;
                }

                String ref = update.getRemoteName();
                if (status == RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD
                        || status == RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED) {
                    throw new GitException(
                            "Git push rejected (non-fast-forward) for %s to %s. Pull/rebase and retry.".formatted(ref, remoteUrl)
                    );
                }

                throw new GitException(
                        "Git push rejected for %s to %s (status=%s).".formatted(ref, remoteUrl, status)
                );
            }
        }
    }

    private void fetch(org.eclipse.jgit.api.Git git) throws GitAPIException {
        String remoteUrl = git.getRepository().getConfig().getString("remote", "origin", "url");
        var fetchCommand = git.fetch().setRemote("origin");
        CredentialsProvider credentials = resolveCredentialsForRemote(remoteUrl);
        if (credentials != null) {
            fetchCommand.setCredentialsProvider(credentials);
        }
        fetchCommand.call();
    }

    private CredentialsProvider resolveCredentialsForRemote(String remoteUrl) {
        if (!credentialsProvider.hasConfiguredCredentials()) {
            return null;
        }
        if (!trustedRemotePolicy.isTrustedForCredentialForwarding(remoteUrl)) {
            if (trustedRemotePolicy.isHttpOrHttpsRemote(remoteUrl)) {
                throw new GitException(
                        "Cannot push to " + remoteUrl + ": the remote host does not match the configured " +
                        "backend (promptlm.store.remote.base-url). " +
                        "Ensure REPO_REMOTE_URL points to the Git server hosting your repositories."
                );
            }
            return null;
        }
        return credentialsProvider.getCredentials();
    }

    public String getBranchName(File repo) {
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(repo)) {
            return git.getRepository().getBranch();
        } catch (IOException e) {
            throw new GitException("Failed to get branch name", e);
        }
    }

    public void checkoutOrCreateBranch(String newBranchName, File repo) {
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(repo)) {
            boolean branchExists = git.branchList().call().stream().anyMatch(b -> b.getName().equals("refs/heads/" + newBranchName));
            if (branchExists) {
                checkout(git, newBranchName);
                fetch(git);
            } else {
                createBranch(repo, newBranchName);
                checkout(git, newBranchName);
            }
        } catch (IOException e) {
            throw new GitException("Failed to open store", e);
        } catch (GitAPIException e) {
            throw new GitException("Failed to checkout or create branch " + newBranchName, e);
        }

    }

    public static String extractRepoName(String repoUrl) {
        String namePart;

        if (repoUrl.contains("://")) {
            // HTTPS or Git protocol
            namePart = repoUrl.substring(repoUrl.lastIndexOf('/') + 1);
        } else if (repoUrl.contains("@")) {
            // SSH, e.g. git@github.com:org/repo.git
            namePart = repoUrl.substring(repoUrl.lastIndexOf(':') + 1);
            namePart = namePart.substring(namePart.lastIndexOf('/') + 1);
        } else {
            throw new IllegalArgumentException("Unrecognized Git URL format: " + repoUrl);
        }

        if (namePart.endsWith(".git")) {
            namePart = namePart.substring(0, namePart.length() - 4);
        }

        return namePart;
    }

    public static String extractRepoOwner(String repoUrl) {
        String path;
        if (repoUrl.contains("://")) {
            path = repoUrl.replaceFirst("https?://[^/]+/", "");
        } else if (repoUrl.contains("@")) {
            path = repoUrl.substring(repoUrl.indexOf(':') + 1);
        } else {
            throw new IllegalArgumentException("Unrecognized Git URL format: " + repoUrl);
        }

        path = path.replaceAll("\\.git$", "");
        String[] parts = path.split("/");
        return (parts.length >= 2) ? parts[0] : null;
    }

    /**
     * get org/name of repo
     */
    public String getFullName(Path repoDir) {
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(repoDir.toFile())) {
            String remote = git.getRepository().getConfig().getString("remote", "origin", "url");
            String repoName = extractRepoName(remote);
            String owner = extractRepoOwner(remote);
            return owner + "/" + repoName;
        } catch (IOException e) {
            throw new GitException("Failed to determine store name", e);
        }
    }

    public String getRemote(Path repoDir) {
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(repoDir.toFile())) {
            String remote = git.getRepository().getConfig().getString("remote", "origin", "url");
            return remote;
        } catch (IOException e) {
            throw new GitException("Failed to read remote URL", e);
        }
    }

    public void checkoutBranch(String branchName, File repo) {
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(repo)) {
            boolean branchExists = git.branchList().call().stream().anyMatch(b -> b.getName().equals("refs/heads/" + branchName));
            if (branchExists) {
                checkout(git, branchName);
                fetch(git);
            } else {
                throw new GitException("Branch " + branchName + " does not exist");
            }
        } catch (IOException e) {
            throw new GitException("Failed to open store", e);
        } catch (GitAPIException e) {
            throw new GitException("Failed to checkout or create branch " + branchName, e);
        }

    }

    public void cherryPick(String devBranch, PromptSpec completed, File repo) {
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(repo)) {
            Repository repository = git.getRepository();
            // cherry-pick the last commit from development branch
            git.cherryPick().include(repository.parseCommit(repository.resolve("development"))).call();
        } catch (IOException | GitAPIException e) {
            throw new GitException("Failed to cherry-pick " + devBranch, e);
        }
    }

    public void tag(String tag, File repo) {
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(repo)) {
            git.tag().setName(tag).call();
        } catch (IOException | GitAPIException e) {
            throw new GitException("Failed to tag " + tag, e);
        }
    }

    public boolean tagExists(String tag, File repo) {
        String expectedRef = "refs/tags/" + tag;
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(repo)) {
            return git.tagList().call().stream()
                    .anyMatch(ref -> expectedRef.equals(ref.getName()));
        } catch (IOException | GitAPIException e) {
            throw new GitException("Failed to inspect tags in " + repo, e);
        }
    }

    public void tagIfMissing(String tag, File repo) {
        if (!tagExists(tag, repo)) {
            tag(tag, repo);
        }
    }
}
