using System.Text;
using GLAFIRA.Agent.Worker;
using Xunit;

namespace GLAFIRA.Agent.Worker.Tests;

public sealed class AgentResultArtifactStoreTests : IDisposable
{
    private readonly string _directory = Path.Combine(
        Path.GetTempPath(), "glafira-agent-artifacts-" + Guid.NewGuid().ToString("N"));

    [Fact]
    public async Task RedactedArtifactIsPersistedAtomicallyAndReloaded()
    {
        var payload = Encoding.UTF8.GetBytes("{\"os\":\"windows\",\"status\":\"healthy\"}");
        var metadata = Metadata(payload);
        var store = new FileAgentResultArtifactStore(_directory);

        await store.WriteAsync(metadata, payload, CancellationToken.None);
        await store.WriteAsync(metadata, payload, CancellationToken.None);

        var restored = Assert.IsType<AgentResultArtifact>(
            await new FileAgentResultArtifactStore(_directory)
                .ReadAsync(metadata.ArtifactId, CancellationToken.None));
        Assert.Equal(metadata, restored.Metadata);
        Assert.Equal(payload, restored.Payload);
        Assert.Single(Directory.GetFiles(_directory, "*.artifact"));
        Assert.Empty(Directory.GetFiles(_directory, "*.tmp"));
    }

    [Fact]
    public async Task UnknownSchemaAndContentTypeAreRejected()
    {
        var payload = Encoding.UTF8.GetBytes("{\"status\":\"healthy\"}");
        var store = new FileAgentResultArtifactStore(_directory);

        await Assert.ThrowsAsync<InvalidDataException>(() => store.WriteAsync(
            Metadata(payload) with { SchemaId = "unknown.schema" }, payload, CancellationToken.None));
        await Assert.ThrowsAsync<InvalidDataException>(() => store.WriteAsync(
            Metadata(payload) with { ContentType = "text/plain" }, payload, CancellationToken.None));
        Assert.False(Directory.Exists(_directory));
    }

    [Fact]
    public async Task OversizeDigestMismatchAndUnredactedPayloadAreRejected()
    {
        var store = new FileAgentResultArtifactStore(_directory);
        var oversized = new byte[AgentResultArtifactPolicy.MaximumArtifactBytes + 1];
        Array.Fill<byte>(oversized, (byte)'x');
        await Assert.ThrowsAsync<InvalidDataException>(() => store.WriteAsync(
            Metadata(oversized), oversized, CancellationToken.None));

        var payload = Encoding.UTF8.GetBytes("{\"status\":\"healthy\"}");
        await Assert.ThrowsAsync<InvalidDataException>(() => store.WriteAsync(
            Metadata(payload) with { Sha256 = new string('0', 64) }, payload, CancellationToken.None));
        await Assert.ThrowsAsync<InvalidDataException>(() => store.WriteAsync(
            Metadata(payload) with { RedactionStatus = "RAW" }, payload, CancellationToken.None));
    }

    [Fact]
    public async Task MalformedUtf8JsonAndNonObjectJsonAreRejected()
    {
        var store = new FileAgentResultArtifactStore(_directory);
        var malformedUtf8 = new byte[] { 0xff, 0xfe };
        await Assert.ThrowsAsync<InvalidDataException>(() => store.WriteAsync(
            Metadata(malformedUtf8), malformedUtf8, CancellationToken.None));

        var malformedJson = Encoding.UTF8.GetBytes("{not-json}");
        await Assert.ThrowsAsync<InvalidDataException>(() => store.WriteAsync(
            Metadata(malformedJson), malformedJson, CancellationToken.None));

        var arrayJson = Encoding.UTF8.GetBytes("[]");
        await Assert.ThrowsAsync<InvalidDataException>(() => store.WriteAsync(
            Metadata(arrayJson), arrayJson, CancellationToken.None));
    }

    [Fact]
    public async Task ExistingArtifactIdentityCannotBeOverwritten()
    {
        var store = new FileAgentResultArtifactStore(_directory);
        var firstPayload = Encoding.UTF8.GetBytes("{\"status\":\"healthy\"}");
        var first = Metadata(firstPayload);
        await store.WriteAsync(first, firstPayload, CancellationToken.None);

        var conflictingPayload = Encoding.UTF8.GetBytes("{\"status\":\"degraded\"}");
        var conflicting = Metadata(conflictingPayload) with { ArtifactId = first.ArtifactId };
        await Assert.ThrowsAsync<InvalidDataException>(() => store.WriteAsync(
            conflicting, conflictingPayload, CancellationToken.None));

        var restored = Assert.IsType<AgentResultArtifact>(
            await store.ReadAsync(first.ArtifactId, CancellationToken.None));
        Assert.Equal(firstPayload, restored.Payload);
    }

    [Fact]
    public async Task InvalidArtifactIdCannotEscapeStoreDirectory()
    {
        var store = new FileAgentResultArtifactStore(_directory);
        await Assert.ThrowsAsync<InvalidDataException>(() =>
            store.ReadAsync("../outside", CancellationToken.None));
        await Assert.ThrowsAsync<InvalidDataException>(() =>
            store.DeleteAsync("nested/path", CancellationToken.None));
        Assert.False(Directory.Exists(_directory));
    }

    [Fact]
    public async Task DeleteIsExplicitAndIdempotent()
    {
        var payload = Encoding.UTF8.GetBytes("{\"status\":\"healthy\"}");
        var metadata = Metadata(payload);
        var store = new FileAgentResultArtifactStore(_directory);
        await store.WriteAsync(metadata, payload, CancellationToken.None);

        Assert.True(await store.DeleteAsync(metadata.ArtifactId, CancellationToken.None));
        Assert.False(await store.DeleteAsync(metadata.ArtifactId, CancellationToken.None));
        Assert.Null(await store.ReadAsync(metadata.ArtifactId, CancellationToken.None));
    }

    private static AgentResultArtifactMetadata Metadata(byte[] payload) => new(
        AgentResultArtifactPolicy.CurrentContractVersion,
        "artifact-0001",
        "task-0001",
        "windows-core-0001",
        3,
        "lease-0001",
        42,
        AgentResultArtifactPolicy.DiagnosticsSchemaId,
        AgentResultArtifactPolicy.DiagnosticsSchemaVersion,
        AgentResultArtifactPolicy.JsonContentType,
        payload.Length,
        AgentResultArtifactPolicy.ComputeSha256(payload),
        AgentResultArtifactPolicy.Redacted,
        DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());

    public void Dispose()
    {
        if (Directory.Exists(_directory))
            Directory.Delete(_directory, recursive: true);
    }
}
