using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Net.Security;
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
        var loaded = await store.LoadAsync(default);
        Assert.NotNull(loaded);
        Assert.Equal(credential.AgentId, loaded.AgentId);
        Assert.Equal(credential.DesktopHubId, loaded.DesktopHubId);
        Assert.Equal(credential.Endpoint, loaded.Endpoint);
        Assert.Equal(credential.TlsCertificateSha256, loaded.TlsCertificateSha256);
        Assert.Equal(credential.SharedSecretBase64, loaded.SharedSecretBase64);
        Assert.True(credential.Scopes.SetEquals(loaded.Scopes));
        Assert.Equal(credential.EnrolledAt, loaded.EnrolledAt);
        Assert.Equal(credential.ExpiresAt, loaded.ExpiresAt);
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
    public void EnrollmentRejectsSecretLongerThan256Bits()
    {
        var credential = CreateCredential() with
        {
            SharedSecretBase64 = Convert.ToBase64String(RandomNumberGenerator.GetBytes(64))
        };

        Assert.Throws<ArgumentException>(() =>
            AgentEnrollmentCredentialPolicy.Validate(credential, _now));
    }

    [Theory]
    [InlineData("")]
    [InlineData("abc")]
    [InlineData("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")]
    [InlineData("gggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggg")]
    public void EnrollmentRejectsMissingOrNonCanonicalTlsPin(string pin)
    {
        var credential = CreateCredential() with { TlsCertificateSha256 = pin };

        Assert.Throws<ArgumentException>(() =>
            AgentEnrollmentCredentialPolicy.Validate(credential, _now));
    }

    [Fact]
    public void TlsPinAcceptsOnlyTheEnrolledCertificate()
    {
        using var enrolled = CreateCertificate("desktop.local");
        using var different = CreateCertificate("desktop.local");
        var credential = CreateCredential() with
        {
            TlsCertificateSha256 = Convert.ToHexString(SHA256.HashData(enrolled.RawData)).ToLowerInvariant()
        };

        Assert.True(AgentTlsCertificatePinPolicy.Validate(
            credential, enrolled, SslPolicyErrors.RemoteCertificateChainErrors));
        Assert.False(AgentTlsCertificatePinPolicy.Validate(
            credential, different, SslPolicyErrors.RemoteCertificateChainErrors));
        Assert.False(AgentTlsCertificatePinPolicy.Validate(
            credential, enrolled, SslPolicyErrors.RemoteCertificateNameMismatch));
        Assert.False(AgentTlsCertificatePinPolicy.Validate(
            credential, null, SslPolicyErrors.RemoteCertificateNotAvailable));
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
        new string('a', 64),
        Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)),
        new HashSet<string>(StringComparer.Ordinal) { "task.claim", "task.result" },
        _now.AddMinutes(-1),
        _now.AddDays(30));

    private static X509Certificate2 CreateCertificate(string dnsName)
    {
        using var key = RSA.Create(2048);
        var request = new CertificateRequest(
            $"CN={dnsName}", key, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        var san = new SubjectAlternativeNameBuilder();
        san.AddDnsName(dnsName);
        request.CertificateExtensions.Add(san.Build());
        return request.CreateSelfSigned(DateTimeOffset.UtcNow.AddMinutes(-1), DateTimeOffset.UtcNow.AddDays(1));
    }

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
