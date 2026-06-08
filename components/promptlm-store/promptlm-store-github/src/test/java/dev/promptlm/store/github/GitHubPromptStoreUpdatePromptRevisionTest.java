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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import dev.promptlm.domain.AppContext;
import dev.promptlm.domain.BasicAppContext;
import dev.promptlm.domain.ObjectMapperFactory;
import dev.promptlm.domain.projectspec.ProjectSpec;
import dev.promptlm.domain.promptspec.ChatCompletionRequest;
import dev.promptlm.domain.promptspec.PromptSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Regression coverage for {@link GitHubPromptStore#updatePrompt(String, PromptSpec)} —
 * the returned revision must match the revision actually persisted to the YAML on
 * disk, and successive updates must monotonically increase it. See #355.
 */
class GitHubPromptStoreUpdatePromptRevisionTest {

    @Test
    void updatePromptIncrementsRevisionAndPersistsItToYaml(@TempDir Path repoDir) throws Exception {
        GitFileNameStrategy fileNameStrategy = new GitFileNameStrategy();
        ObjectMapper yamlMapper = ObjectMapperFactory.createYamlMapper();

        ProjectSpec projectSpec = ProjectSpec.fromRepo(repoDir);
        AppContext appContext = new BasicAppContext();
        appContext.setActiveProject(projectSpec);

        Git git = mock(Git.class);
        GitRepositoryMetadata repositoryMetadata = mock(GitRepositoryMetadata.class);

        GitHubPromptStore store = new GitHubPromptStore(yamlMapper,
                fileNameStrategy,
                git,
                appContext,
                new IntVersioningStrategy(),
                repositoryMetadata);

        PromptSpec initial = PromptSpec.builder()
                .withGroup("support")
                .withName("welcome")
                .withVersion("1")
                .withRevision(0)
                .withDescription("d")
                .withRequest(ChatCompletionRequest.builder()
                        .withVendor("v")
                        .withUrl("u")
                        .withModel("m")
                        .withMessages(List.of())
                        .build())
                .build()
                .withId("support/welcome");

        // Seed the repo with rev=0 so updatePrompt takes the "update" branch.
        Path specPath = repoDir.resolve(fileNameStrategy.buildPromptPath("support", "welcome"));
        Files.createDirectories(specPath.getParent());
        Files.writeString(specPath, yamlMapper.writeValueAsString(initial));

        // 0 -> 1
        int firstReturned = store.updatePrompt("support/welcome", initial);
        assertThat(firstReturned).isEqualTo(1);
        PromptSpec afterFirst = yamlMapper.readValue(specPath.toFile(), PromptSpec.class);
        assertThat(afterFirst.getRevision())
                .as("revision committed to YAML must match returned value")
                .isEqualTo(firstReturned);

        // 1 -> 2 (drive the next update from what is now on disk)
        int secondReturned = store.updatePrompt("support/welcome", afterFirst);
        assertThat(secondReturned).isEqualTo(2);
        PromptSpec afterSecond = yamlMapper.readValue(specPath.toFile(), PromptSpec.class);
        assertThat(afterSecond.getRevision()).isEqualTo(secondReturned);
    }
}
