<!--
Copyright 2025 promptLM

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# Playwright Integration Tests for PromptLM

This directory contains UI integration tests using Microsoft's [Playwright](https://playwright.dev/java/) framework, a modern alternative to Selenium offering improved stability, speed, and developer experience.

## Advantages of Playwright over Selenium

- **Auto-waiting**: Playwright automatically waits for elements to be ready before performing actions
- **Cross-browser support**: Works with Chromium, Firefox, and WebKit with a single API
- **Modern web features**: Better handling of Shadow DOM, iframes, and modern web components
- **Powerful selectors**: CSS, XPath, and text-based selectors with built-in retry logic
- **Network interception**: Ability to mock API responses and monitor network traffic
- **Tracing and debugging**: Screenshots, videos, and detailed logs for test failures
- **Parallelization**: Built-in support for running tests in parallel
- **No WebDriver dependencies**: Playwright manages browser installations automatically

## Setup and Configuration

### Prerequisites

- Java 21 or later
- Maven 3.6 or later
- Docker (for Gitea/runner/Artifactory-backed tests)

### Maven Dependencies

The `pom.xml` in this directory configures:
1. Playwright Java SDK
2. JUnit Jupiter for test execution
3. AssertJ for fluent assertions
4. Automatic browser downloads via Maven plugins

## Running Tests

### From Command Line

The acceptance-tests module installs Playwright browsers during the Maven lifecycle via the `exec-maven-plugin` (`npx playwright install --with-deps` in `generate-test-resources`). Ensure Node.js and `npx` are available on your PATH.

Tests are tagged with `@IntegrationTest` (`@Tag("integration")`). The module defaults to:
- `promptlm.test.groups=integration`
- `promptlm.test.excludedGroups=`

To run the default integration suite:

```bash
# from repository root
./test.sh
```

or directly via Maven:

```bash
cd acceptance-tests
mvn clean test
```

To run a specific test class:

```bash
cd acceptance-tests
mvn test -Dtest=HappyPathUserJourneyTest -Dpromptlm.test.groups=integration
```

To skip long-running integration tests intentionally:

```bash
cd acceptance-tests
mvn test -Dpromptlm.test.excludedGroups=integration
```

### Run Native Binary Smoke Tests

Native smoke tests are tagged with `@NativeSmokeTest` (`@Tag("native-smoke")`). They verify the native deliverables (`promptlm-cli`, `promptlm-webapp`) can boot and execute core commands.

Build native binaries first:

```bash
# from repository root
mvn -B -Pnative -pl apps/promptlm-cli,apps/promptlm-webapp -am -DskipTests package
```

Then run the smoke group with explicit binary paths:

```bash
cd acceptance-tests
mvn test \
  -Dpromptlm.test.groups=native-smoke \
  -Dpromptlm.test.excludedGroups= \
  -Dpromptlm.test.cli.native.path=../apps/promptlm-cli/target/promptlm-cli \
  -Dpromptlm.test.webapp.native.path=../apps/promptlm-webapp/target/promptlm-webapp
```

The smoke suite fails fast if either native binary path is missing or not executable.

### Runtime Parameters

You can control Playwright and selected acceptance-test behavior via JVM system properties (or environment variables where supported).

#### Playwright browser behavior

- `-Dplaywright.headless=true|false`
  - Default: `true` (falls back to env var `PLAYWRIGHT_HEADLESS`, then `true`)
  - Use `false` to watch the browser during execution.
- `-Dplaywright.slowmo=<millis>`
  - Default: `0` (falls back to env var `PLAYWRIGHT_SLOWMO`)
  - Adds delay between Playwright actions for debugging.

Examples:

```bash
# Run visible browser
cd acceptance-tests
mvn test -Dtest=HappyPathUserJourneyTest -Dplaywright.headless=false
```

```bash
# Run visible browser with slow motion
cd acceptance-tests
mvn test -Dtest=HappyPathUserJourneyTest -Dplaywright.headless=false -Dplaywright.slowmo=1200
```

#### Workflow and integration-specific parameters

- `-Dpromptlm.gitea.actions.workflow.file=<workflow-file>`
  - Default: `bundle-release.yml`
  - Controls which Gitea Actions workflow is observed during release verification.
- `-DOPENAI_API_KEY=<key>`
  - Optional override used when repository action variables are configured in tests.
  - Default fallback in test setup: `dummy-openai-key`.

Example:

```bash
cd acceptance-tests
mvn test \
  -Dtest=HappyPathUserJourneyTest \
  -Dplaywright.headless=false \
  -Dplaywright.slowmo=800 \
  -Dpromptlm.gitea.actions.workflow.file=bundle-release.yml \
  -DOPENAI_API_KEY=your-key
```

### From IDE

1. Make sure your IDE has JUnit 5 support
2. Right-click on a test class or method and select "Run"
3. Test results will appear in your IDE's test runner

## Test Structure

### Current Test Classes

1. **HappyPathUserJourneyTest.java** - end-to-end UI + backend + repo/release flow against `promptlm-cli serve`
2. **CiWorkflowHarnessTest.java** - template CI workflow harness against Gitea Actions and Artifactory

### Key Components

- `@BeforeAll` - Sets up the Playwright browser instance
- `@AfterAll` - Closes the browser and Playwright instance
- `@BeforeEach` - Creates a new browser context and page for each test
- `@AfterEach` - Closes the context after each test

## Writing New Tests

### Page Object Pattern

For larger test suites, consider implementing the Page Object pattern:

```java
// Example Page Object
public class PromptPage {
    private final Page page;
    
    public PromptPage(Page page) {
        this.page = page;
    }
    
    public void fillPromptName(String name) {
        page.fill("[data-testid='prompt-name-input']", name);
    }
    
    public void submitForm() {
        page.click("[data-testid='submit-button']");
    }
}
```

### Selectors Best Practices

1. Use data-testid attributes for most stable selectors:
   ```java
   page.click("[data-testid='submit-button']");
   ```

2. Text-based selectors for user-facing elements:
   ```java
   page.click("button:has-text('Add Prompt')");
   ```

3. Role-based selectors for accessibility:
   ```java
   page.click("role=button[name='Add Prompt']");
   ```

### Visual Testing

Playwright can capture screenshots for visual testing:

```java
page.screenshot(new Page.ScreenshotOptions()
    .setPath(Paths.get("target/screenshots/homepage.png"))
    .setFullPage(true));
```

## Debugging Tips

1. Set `headless` to `false` to see the browser during test execution
   ```java
   browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
       .setHeadless(false)
       .setSlowMo(100));
   ```

2. Use `slowMo` to slow down operations for easier visual debugging

3. Record videos of test runs
   ```java
   context = browser.newContext(new Browser.NewContextOptions()
       .setRecordVideoDir(Paths.get("target/videos/")));
   ```

4. Use Playwright's Trace Viewer for detailed session recording
   ```java
   context.tracing().start(new Tracing.StartOptions()
       .setScreenshots(true)
       .setSnapshots(true));
       
   // At the end of test
   context.tracing().stop(new Tracing.StopOptions()
       .setPath(Paths.get("trace.zip")));
   ```

## Continuous Integration

These tests can be integrated into your CI pipeline. For GitHub Actions:

```yaml
name: Playwright Tests
on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Set up JDK
        uses: actions/setup-java@v3
        with:
          distribution: 'temurin'
          java-version: '21'
      - name: Run integration tests
        run: mvn -f acceptance-tests/pom.xml -Dpromptlm.bootstrap.skip=true -Dpromptlm.test.groups=integration test
      - name: Upload test results
        if: always()
        uses: actions/upload-artifact@v3
        with:
          name: test-results
          path: acceptance-tests/target
```

## Further Resources

- [Playwright Java Documentation](https://playwright.dev/java/docs/intro)
- [Playwright API Reference](https://playwright.dev/java/docs/api/class-playwright)
- [JUnit 5 User Guide](https://junit.org/junit5/docs/current/user-guide/)
