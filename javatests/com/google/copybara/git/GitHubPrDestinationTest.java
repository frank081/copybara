/*
 * Copyright (C) 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.copybara.git;

import static com.google.common.truth.Truth.assertThat;
import static com.google.copybara.testing.git.GitTestUtil.getGitEnv;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doThrow;

import com.google.api.client.http.HttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.copybara.Destination.DestinationStatus;
import com.google.copybara.Destination.Writer;
import com.google.copybara.Revision;
import com.google.copybara.WriterContext;
import com.google.copybara.exception.RepoException;
import com.google.copybara.exception.ValidationException;
import com.google.copybara.git.GitRepository.GitLogEntry;
import com.google.copybara.git.github.api.GitHubApi;
import com.google.copybara.testing.DummyRevision;
import com.google.copybara.testing.OptionsBuilder;
import com.google.copybara.testing.SkylarkTestExecutor;
import com.google.copybara.testing.TransformResults;
import com.google.copybara.testing.git.GitApiMockHttpTransport;
import com.google.copybara.testing.git.GitTestUtil;
import com.google.copybara.testing.git.GitTestUtil.TestGitOptions;
import com.google.copybara.util.Glob;
import com.google.copybara.util.console.Message.MessageType;
import com.google.copybara.util.console.testing.TestingConsole;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Map.Entry;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class GitHubPrDestinationTest {

  private Path repoGitDir;
  private OptionsBuilder options;
  private TestingConsole console;
  private SkylarkTestExecutor skylark;

  @Rule
  public final ExpectedException thrown = ExpectedException.none();
  private Path workdir;
  private Path localHub;
  private final String expectedProject = "foo";
  private GitApiMockHttpTransport gitApiMockHttpTransport;

  @Before
  public void setup() throws Exception {
    repoGitDir = Files.createTempDirectory("GitHubPrDestinationTest-repoGitDir");
    workdir = Files.createTempDirectory("workdir");
    localHub = Files.createTempDirectory("localHub");

    git("init", "--bare", repoGitDir.toString());
    console = new TestingConsole();
    options = new OptionsBuilder()
        .setConsole(console)
        .setOutputRootToTmpDir();
    options.git = new TestGitOptions(localHub, GitHubPrDestinationTest.this.options.general);

    options.github = new GitHubOptions(options.general, options.git) {
      @Override
      public GitHubApi newGitHubApi(String project) throws RepoException {
        assertThat(project).isEqualTo(expectedProject);
        return super.newGitHubApi(project);
      }

      @Override
      protected HttpTransport newHttpTransport() {
        return gitApiMockHttpTransport;
      }
    };
    Path credentialsFile = Files.createTempFile("credentials", "test");
    Files.write(credentialsFile, "https://user:SECRET@github.com".getBytes(UTF_8));
    options.git.credentialHelperStorePath = credentialsFile.toString();

    options.gitDestination = new GitDestinationOptions(options.general, options.git);
    options.gitDestination.committerEmail = "commiter@email";
    options.gitDestination.committerName = "Bara Kopi";
    skylark = new SkylarkTestExecutor(options);
  }

  @Test
  public void testWrite_noContextReference()
      throws ValidationException, IOException, RepoException {
    WriterContext<GitRevision> writerContext =
        new WriterContext<>("piper_to_github_pr","TEST", Glob.ALL_FILES,
            false,  new DummyRevision("feature", null),null);
    GitHubPrDestination d = skylark.eval(
        "r", "r = git.github_pr_destination(" + "    url = 'https://github.com/foo'" + ")");
    thrown.expect(ValidationException.class);
    thrown.expectMessage("git.github_pr_destination is incompatible with the current origin. Origin has to be"
        + " able to provide the contextReference or use '--github-destination-pr-branch' flag");
    d.newWriter(writerContext);
  }

  @Test
  public void testCustomTitleAndBody()
      throws ValidationException, IOException, RepoException {
    options.githubDestination.destinationPrBranch = "feature";
    gitApiMockHttpTransport =
        new GitApiMockHttpTransport() {
          @Override
          public String getContent(String method, String url, MockLowLevelHttpRequest request)
              throws IOException {
            boolean isPulls = "https://api.github.com/repos/foo/pulls".equals(url);
            if ("GET".equals(method) && isPulls) {
              return "[]";
            } else if ("POST".equals(method) && isPulls) {
              assertThat(request.getContentAsString())
                  .isEqualTo(
                      "{\"base\":\"master\","
                          + "\"body\":\"custom body\","
                          + "\"head\":\"feature\","
                          + "\"title\":\"custom title\"}");
              return ("{\n"
                  + "  \"id\": 1,\n"
                  + "  \"number\": 12345,\n"
                  + "  \"state\": \"open\",\n"
                  + "  \"title\": \"custom title\",\n"
                  + "  \"body\": \"custom body\""
                  + "}");
            }
            fail(method + " " + url);
            throw new IllegalStateException();
          }
        };
    GitHubPrDestination d = skylark.eval("r", "r = git.github_pr_destination("
        + "    url = 'https://github.com/foo', \n"
        + "    title = 'custom title',\n"
        + "    body = 'custom body',\n"
        + ")");
    WriterContext<GitRevision> writerContext =
        new WriterContext<>(
            /*workflowName=*/"piper_to_github",
            /*workflowIdentityUser=*/"TEST",
            Glob.ALL_FILES,
            /*dryRun=*/false,
            new DummyRevision("feature", "feature"),
            /*oldWriter=*/null);
    Writer<GitRevision> writer = d.newWriter(writerContext);
    GitRepository remote = localHubRepo("foo");
    addFiles(remote, null, "first change", ImmutableMap.<String, String>builder()
        .put("foo.txt", "").build());

    Files.write(this.workdir.resolve("test.txt"), "some content".getBytes());
    writer.write(TransformResults.of(this.workdir, new DummyRevision("one")), console);
  }

  @Test
  public void testWrite_destinationPrBranchFlag()
      throws ValidationException, IOException, RepoException {
    options.githubDestination.destinationPrBranch = "feature";
    checkWrite(new DummyRevision("dummyReference"));
  }

  @Test
  public void testTrimMessageForPrTitle()
      throws ValidationException, IOException, RepoException {
    options.githubDestination.destinationPrBranch = "feature";
    gitApiMockHttpTransport =
        new GitApiMockHttpTransport() {
          @Override
          public String getContent(String method, String url, MockLowLevelHttpRequest request)
              throws IOException {
            boolean isPulls = "https://api.github.com/repos/foo/pulls".equals(url);
            if ("GET".equals(method) && isPulls) {
              return "[]";
            } else if ("POST".equals(method) && isPulls) {
              assertThat(request.getContentAsString())
                  .isEqualTo(
                      "{\"base\":\"master\",\"body\":\"Internal change.\",\"head\":\"feature\","
                          + "\"title\":\"Internal change.\"}");
              return ("{\n"
                  + "  \"id\": 1,\n"
                  + "  \"number\": 12345,\n"
                  + "  \"state\": \"open\",\n"
                  + "  \"title\": \"test summary\",\n"
                  + "  \"body\": \"test summary\""
                  + "}");
            }
            fail(method + " " + url);
            throw new IllegalStateException();
          }
        };
    GitHubPrDestination d = skylark.eval("r", "r = git.github_pr_destination("
        + "    url = 'https://github.com/foo'"
        + ")");

    WriterContext<GitRevision> writerContext =
        new WriterContext<>(
            "piper_to_github",
            "test",
            Glob.ALL_FILES,
            /*dryRun=*/false,
            new DummyRevision("feature", "feature"),
            /*oldWriter=*/null);

    Writer<GitRevision> writer = d.newWriter(writerContext);

    GitRepository remote = localHubRepo("foo");
    addFiles(remote, null, "first change", ImmutableMap.<String, String>builder()
        .put("foo.txt", "").build());

    Files.write(this.workdir.resolve("test.txt"), "some content".getBytes());
    writer.write(TransformResults.of(this.workdir, new DummyRevision("one"))
        .withSummary("\n\n\n\n\nInternal change."), console);
  }

  @Test
  public void testHttpUrl() throws Exception {
    GitHubPrDestination d = skylark.eval("r", "r = git.github_pr_destination("
        + "    url = 'http://github.com/foo', \n"
        + "    title = 'custom title',\n"
        + "    body = 'custom body',\n"
        + ")");
    assertThat(d.describe(Glob.ALL_FILES).get("name")).contains("https://github.com/foo");
  }

  private void checkWrite(Revision revision)
      throws ValidationException, RepoException, IOException {
    gitApiMockHttpTransport =
        new GitApiMockHttpTransport() {
          @Override
          public String getContent(String method, String url, MockLowLevelHttpRequest request)
              throws IOException {
            boolean isPulls = "https://api.github.com/repos/foo/pulls".equals(url);
            if ("GET".equals(method) && isPulls) {
              return "[]";
            } else if ("POST".equals(method) && isPulls) {
              assertThat(request.getContentAsString())
                  .isEqualTo(
                      "{\"base\":\"master\",\"body\":\"test summary\",\"head\":\""
                          + "feature"
                          + "\",\"title\":\"test summary\"}");
              return ("{\n"
                  + "  \"id\": 1,\n"
                  + "  \"number\": 12345,\n"
                  + "  \"state\": \"open\",\n"
                  + "  \"title\": \"test summary\",\n"
                  + "  \"body\": \"test summary\""
                  + "}");
            }
            fail(method + " " + url);
            throw new IllegalStateException();
          }
        };
    GitHubPrDestination d =
        skylark.eval(
            "r", "r = git.github_pr_destination(" + "    url = 'https://github.com/foo'" + ")");
    WriterContext<GitRevision> writerContext =
        new WriterContext<>(/*workflowName=*/"piper_to_github_pr", /*workflowIdentityUser=*/"TEST", Glob.ALL_FILES, /*dryRun=*/false, revision, /*oldWriter=*/null);
    Writer<GitRevision> writer = d.newWriter(writerContext);

    GitRepository remote = localHubRepo("foo");
    addFiles(remote, null, "first change", ImmutableMap.<String, String>builder()
        .put("foo.txt", "").build());

    Files.write(this.workdir.resolve("test.txt"), "some content".getBytes());
    writer.write(TransformResults.of(this.workdir, new DummyRevision("one")), console);
    Files.write(this.workdir.resolve("test.txt"), "other content".getBytes());
    writer.write(TransformResults.of(this.workdir, new DummyRevision("two")), console);

    // Use a new writer that shares the old state
    writerContext = new WriterContext<>(/*workflowName=*/"piper_to_github_pr", /*workflowIdentityUser=*/"TEST",
        Glob.ALL_FILES, /*dryRun=*/false, revision, writer);
    writer = d.newWriter(writerContext);

    Files.write(this.workdir.resolve("test.txt"), "and content".getBytes());
    writer.write(TransformResults.of(this.workdir, new DummyRevision("three")), console);

    console.assertThat().timesInLog(1, MessageType.INFO,
        "Pull Request https://github.com/foo/pull/12345 created using branch 'feature'.");

    assertThat(remote.refExists("feature")).isTrue();
    assertThat(Iterables.transform(remote.log("feature").run(), GitLogEntry::getBody))
        .containsExactly("first change\n",
            "test summary\n"
                + "\n"
                + "DummyOrigin-RevId: one\n",
            "test summary\n"
                + "\n"
                + "DummyOrigin-RevId: two\n",
            "test summary\n"
                + "\n"
                + "DummyOrigin-RevId: three\n");

    // If we don't keep writer state (same as a new migration). We do a rebase of
    // all the changes.
    writerContext = new WriterContext<>( /*workflowName=*/"piper_to_github_pr",
        /*workflowIdentityUser=*/"TEST", Glob.ALL_FILES, false, revision,  /*oldWriter=*/null);
    writer = d.newWriter(writerContext);

    Files.write(this.workdir.resolve("test.txt"), "and content".getBytes());
    writer.write(TransformResults.of(this.workdir, new DummyRevision("four")), console);

    assertThat(Iterables.transform(remote.log("feature").run(), GitLogEntry::getBody))
        .containsExactly("first change\n", "test summary\n" + "\n" + "DummyOrigin-RevId: four\n");
  }

  @Test
  public void testFindProject() throws ValidationException {
    checkFindProject("https://github.com/foo", "foo");
    checkFindProject("https://github.com/foo/bar", "foo/bar");
    checkFindProject("https://github.com/foo.git", "foo");
    checkFindProject("https://github.com/foo/", "foo");
    checkFindProject("git+https://github.com/foo", "foo");
    checkFindProject("git@github.com/foo", "foo");
    checkFindProject("git@github.com:org/internal_repo_name.git", "org/internal_repo_name");
    try {
      checkFindProject("https://github.com", "foo");
      fail();
    } catch (ValidationException e) {
      console.assertThat().onceInLog(MessageType.ERROR,
          ".*'https://github.com' is not a valid GitHub url.*");
    }
  }

  @Test
  public void testIntegratesCanBeRemoved() throws Exception {
    GitHubPrDestination d = skylark.eval("r", "r = git.github_pr_destination("
        + "    url = '" + "https://github.com/foo" + "',"
        + "    destination_ref = 'other',"
        + ")");

    assertThat(ImmutableList.copyOf(d.getIntegrates())).isEqualTo(GitModule.DEFAULT_GIT_INTEGRATES);

    d = skylark.eval("r", "r = git.github_pr_destination("
        + "    url = '" + "https://github.com/foo" + "',"
        + "    destination_ref = 'other',"
        + "    integrates = [],"
        + ")");

    assertThat(d.getIntegrates()).isEmpty();
  }

  private void checkFindProject(String url, String project) throws ValidationException {
    GitHubPrDestination d = skylark.eval("r", "r = git.github_pr_destination("
        + "    url = '" + url + "',"
        + "    destination_ref = 'other',"
        + ")");

    assertThat(d.getProjectName()).isEqualTo(project);
  }

  @Test
  public void testWriteNoMaster() throws ValidationException, IOException, RepoException {
    GitHubPrDestination d = skylark.eval("r", "r = git.github_pr_destination("
        + "    url = 'https://github.com/foo',"
        + "    destination_ref = 'other',"
        + ")");
    DummyRevision dummyRevision = new DummyRevision("dummyReference", "feature");
    String branchName = d.branchFromContextReference(dummyRevision, "piper_to_github_pr", "TEST");
    gitApiMockHttpTransport =
        new GitApiMockHttpTransport() {
          @Override
          public String getContent(String method, String url, MockLowLevelHttpRequest request)
              throws IOException {
            boolean isPulls = "https://api.github.com/repos/foo/pulls".equals(url);
            if ("GET".equals(method) && isPulls) {
              return "[]";
            } else if ("POST".equals(method) && isPulls) {
               assertThat(request.getContentAsString())
                  .isEqualTo( "{\"base\":\"other\",\"body\":\"test summary\",\"head\":\""
                      + branchName + "\",\"title\":\"test summary\"}");
              return ("{\n"
                  + "  \"id\": 1,\n"
                  + "  \"number\": 12345,\n"
                  + "  \"state\": \"open\",\n"
                  + "  \"title\": \"test summary\",\n"
                  + "  \"body\": \"test summary\""
                  + "}");
            }
            fail(method + " " + url);
            throw new IllegalStateException();
          }
        };
    WriterContext<GitRevision> writerContext =
        new WriterContext<>( /*workflowName=*/"piper_to_github_pr", /*workflowIdentityUser=*/"TEST",
            Glob.ALL_FILES, /*dryRun=*/false, dummyRevision,  /*oldWriter=*/null);
    Writer<GitRevision> writer = d.newWriter(writerContext);
    GitRepository remote = localHubRepo("foo");
    addFiles(remote, "master", "first change", ImmutableMap.<String, String>builder()
        .put("foo.txt", "").build());

    addFiles(remote, "other", "second change", ImmutableMap.<String, String>builder()
        .put("foo.txt", "test").build());

    Files.write(this.workdir.resolve("test.txt"), "some content".getBytes());
    writer.write(TransformResults.of(this.workdir, new DummyRevision("one")), console);

    assertThat(remote.refExists(branchName)).isTrue();
    assertThat(Iterables.transform(remote.log(branchName).run(), GitLogEntry::getBody))
        .containsExactly("first change\n", "second change\n",
        "test summary\n"
            + "\n"
            + "DummyOrigin-RevId: one\n");
  }

  @Test
  public void testDestinationStatus() throws ValidationException, IOException, RepoException {
    options.githubDestination.createPullRequest = false;
    gitApiMockHttpTransport = GitTestUtil.NO_GITHUB_API_CALLS;
    GitHubPrDestination d = skylark.eval("r", "r = git.github_pr_destination("
        + "    url = 'https://github.com/foo'"
        + ")");
    WriterContext<GitRevision> writerContext =
        new WriterContext<>("piper_to_github", "TEST", Glob.ALL_FILES,
            /*dryRun=*/false, new DummyRevision("feature", "feature"),  /*oldWriter=*/null);
    Writer<GitRevision> writer = d.newWriter(writerContext);

    GitRepository remote = localHubRepo("foo");
    addFiles(remote, "master", "first change\n\nDummyOrigin-RevId: baseline",
        ImmutableMap.<String, String>builder()
            .put("foo.txt", "").build());

    DestinationStatus status = writer.getDestinationStatus("DummyOrigin-RevId");

    assertThat(status.getBaseline()).isEqualTo("baseline");
    assertThat(status.getPendingChanges()).isEmpty();

    Files.write(this.workdir.resolve("test.txt"), "some content".getBytes());
    writer.write(TransformResults.of(this.workdir, new DummyRevision("one")), console);

    // New writer since after changes it keeps state internally for ITERATIVE mode
    status = d.newWriter(writerContext).getDestinationStatus("DummyOrigin-RevId");
    assertThat(status.getBaseline()).isEqualTo("baseline");
    // Not supported for now as we rewrite the whole branch history.
    assertThat(status.getPendingChanges()).isEmpty();
  }

  private void addFiles(GitRepository remote, String branch, String msg, Map<String, String> files)
      throws IOException, RepoException {
    Path temp = Files.createTempDirectory("temp");
    GitRepository tmpRepo = remote.withWorkTree(temp);
    if (branch != null) {
      if (tmpRepo.refExists(branch)) {
        tmpRepo.simpleCommand("checkout", branch);
      } else if (!branch.equals("master")) {
        tmpRepo.simpleCommand("branch", branch);
        tmpRepo.simpleCommand("checkout", branch);
      }
    }

    for (Entry<String, String> entry : files.entrySet()) {
      Path file = temp.resolve(entry.getKey());
      Files.createDirectories(file.getParent());
      Files.write(file, entry.getValue().getBytes(UTF_8));
    }

    tmpRepo.add().all().run();
    tmpRepo.simpleCommand("commit", "-m", msg);
  }

  private GitRepository localHubRepo(String name) throws RepoException {
    GitRepository repo = GitRepository.newBareRepo(localHub.resolve("github.com/" + name),
        getGitEnv(),
        options.general.isVerbose());
    repo.init();
    return repo;
  }

  private String git(String... argv) throws RepoException {
    return repo()
        .git(repoGitDir, argv)
        .getStdout();
  }

  private GitRepository repo() {
    return repoForPath(repoGitDir);
  }

  private GitRepository repoForPath(Path path) {
    return GitRepository.newBareRepo(path, getGitEnv(),  /*verbose=*/true);
  }
}
