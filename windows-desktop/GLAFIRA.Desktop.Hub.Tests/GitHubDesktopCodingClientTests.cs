using GLAFIRA.Desktop.Hub.Core;
using Xunit;

namespace GLAFIRA.Desktop.Hub.Tests;

public sealed class GitHubDesktopCodingClientTests
{
    [Fact]
    public void AllowsFeatureBranches()
    {
        GitHubDesktopCodingClient.RequireWritableBranch("feat/github-write-api");
        GitHubDesktopCodingClient.RequireWritableBranch("fix/voice-crash-2026");
    }

    [Theory]
    [InlineData("main")]
    [InlineData("master")]
    [InlineData("main/hotfix")]
    [InlineData("master/hotfix")]
    [InlineData("../main")]
    [InlineData("feature//unsafe")]
    [InlineData("feature/")]
    [InlineData("feature with spaces")]
    [InlineData("feature/.hidden")]
    [InlineData("feature/release.lock")]
    public void RejectsProtectedOrMalformedBranches(string branch)
    {
        Assert.Throws<ArgumentException>(() =>
            GitHubDesktopCodingClient.RequireWritableBranch(branch));
    }

    [Fact]
    public void AcceptsRepositoryRelativePaths()
    {
        GitHubDesktopCodingClient.RequireSafePath("windows-desktop/src/Example.cs");
    }

    [Fact]
    public void AllowsOnlyApprovedSourceAndDocumentationFiles()
    {
        GitHubDesktopCodingClient.RequireWritablePath("windows-desktop/GLAFIRA.Desktop.App/Example.cs");
        GitHubDesktopCodingClient.RequireWritablePath("windows-desktop/GLAFIRA.Desktop.App/MainWindow.xaml");
        GitHubDesktopCodingClient.RequireWritablePath("app/src/main/Example.kt");
        GitHubDesktopCodingClient.RequireWritablePath("ios/GlafiraBridgeKit/Sources/Example.swift");
        GitHubDesktopCodingClient.RequireWritablePath("docs/architecture.md");
        GitHubDesktopCodingClient.RequireWritablePath("README.md");
    }

    [Theory]
    [InlineData("app/build.gradle.kts")]
    [InlineData("app/src/main/AndroidManifest.xml")]
    [InlineData(".github/workflows/ci.yml")]
    [InlineData("windows-desktop/scripts/update-desktop.ps1")]
    [InlineData("windows-desktop/GLAFIRA.Desktop.App/unknown.bin")]
    [InlineData("windows-desktop/GLAFIRA.Desktop.App/settings.json")]
    [InlineData("windows-desktop/GLAFIRA.Desktop.App/Project.csproj")]
    [InlineData("outside/Example.cs")]
    [InlineData(".github/README.md")]
    public void RejectsUnknownProtectedAndOutOfScopeWrites(string path)
    {
        Assert.Throws<InvalidOperationException>(() =>
            GitHubDesktopCodingClient.RequireWritablePath(path));
    }

    [Theory]
    [InlineData(".github/workflows/ci.yml")]
    [InlineData(".gitlab-ci.yml")]
    [InlineData("GLAFIRA.Desktop.Hub.csproj")]
    [InlineData("windows-desktop/GLAFIRA.Desktop.Hub.sln")]
    [InlineData("Directory.Build.props")]
    [InlineData("windows-desktop/Directory.Build.targets")]
    [InlineData("global.json")]
    [InlineData("NuGet.Config")]
    [InlineData("packages.lock.json")]
    [InlineData(".gitmodules")]
    [InlineData("Dockerfile")]
    public void RejectsCiAndBuildControlWrites(string path)
    {
        Assert.Throws<InvalidOperationException>(() =>
            GitHubDesktopCodingClient.RequireWritablePath(path));
    }

    [Theory]
    [InlineData("")]
    [InlineData("/absolute/path")]
    [InlineData("../outside")]
    [InlineData("app/../secret")]
    [InlineData("folder/")]
    [InlineData("folder\\secret")]
    public void RejectsUnsafePaths(string path)
    {
        Assert.Throws<ArgumentException>(() =>
            GitHubDesktopCodingClient.RequireSafePath(path));
    }
}
