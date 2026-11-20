using System.Security.Cryptography;
using GLAFIRA.Agent.Worker;
using Xunit;

namespace GLAFIRA.Agent.Worker.Tests;

public sealed class DpapiAgentCredentialStoreTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "glafira-agent-dpapi-" + Guid.NewGuid().ToString("N"));
    private readonly string _path;
    private readonly DateTimeOffset _now = DateTimeOffset.FromUnixTimeSeconds(1_800_000_000);

    public DpapiAgentCredentialStoreTests()
    {
        _path = Path.Combine(_root, "enrollment.dpapi");
    }

    [Fact]
    public async Task SaveAndLoadRoundTripsCredentialWithoutPlaintextSecretOnDisk()
    {
        var store = new DpapiAgentCredentialStore(_path, new FixedTimeProvider(_now));
        var credential = CreateCredential();

        await store.SaveAsync(credential, default);
        var diskBytes = await File.ReadAllBytesAsync(_path);

        Assert.False(ContainsSequence(diskBytes, Convert.FromBase64String(credential.SharedSecretBase64)));
        Assert.Equal(credential, await store.LoadAsync(default));
    }

    [Fact]
    public async Task DeleteRemovesThePersistedEnrollment()
    {
        var store = new DpapiAgentCredentialStore(_path, new FixedTimeProvider(_now));
        await store.SaveAsync(CreateCredential(), default);

        await store.DeleteAsync(default);

        Assert.False(File.Exists(_path));
        Assert.Null(await store.LoadAsync(default));
    }

    [Fact]
    public async Task CorruptedOrForeignUserBlobFailsClosed()
    {
        Directory.CreateDirectory(_root);
        await File.WriteAllBytesAsync(_path, RandomNumberGenerator.GetBytes(128));
        var store = new DpapiAgentCredentialStore(_path, new FixedTimeProvider(_now));

        await Assert.ThrowsAsync<InvalidDataException>(() => store.LoadAsync(default));
    }

    [Fact]
    public void EnrollmentRejectsPlainHttpEndpoint()
    {
        var credential = CreateCredential() with { Endpoint = new Uri("http://desktop.local:8443") };

        Assert.Throws<ArgumentException>(() =>
            AgentEnrollmentCredentialPolicy.Validate(credential, _now));
    }

    [Fact]
    public void EnrollmentRejectsSecretShorterThan256Bits()
    {
        var credential = CreateCredential() with
        {
            SharedSecretBase64 = Convert.ToBase64String(RandomNumberGenerator.GetBytes(16))
        };

        Assert.Throws<ArgumentException>(() =>
            AgentEnrollmentCredentialPolicy.Validate(credential, _now));
    }

    [Fact]
    public void EnrollmentRejectsExpiredCredential()
    {
        var credential = CreateCredential() with { ExpiresAt = _now.AddSeconds(-1) };

        Assert.Throws<ArgumentException>(() =>
            AgentEnrollmentCredentialPolicy.Validate(credential, _now));
    }

    private static bool ContainsSequence(byte[] haystack, byte[] needle) =>
        needle.Length > 0 && Enumerable.Range(0, Math.Max(0, haystack.Length - needle.Length + 1))
            .Any(offset => haystack.AsSpan(offset, needle.Length).SequenceEqual(needle));

    private AgentEnrollmentCredential CreateCredential() => new(
        "agent-01",
        "desktop-hub-01",
        new Uri("https://desktop.local:8443"),
        Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)),
        new HashSet<string>(StringComparer.Ordinal) { "task.claim", "task.result" },
        _now.AddMinutes(-1),
        _now.AddDays(30));

    public void Dispose()
    {
        if (Directory.Exists(_root))
            Directory.Delete(_root, recursive: true);
    }

    private sealed class FixedTimeProvider(DateTimeOffset now) : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => now;
    }
}
