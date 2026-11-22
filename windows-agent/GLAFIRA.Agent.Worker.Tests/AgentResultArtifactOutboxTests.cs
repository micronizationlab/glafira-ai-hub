using System.Text;
using GLAFIRA.Agent.Worker;
using Xunit;

namespace GLAFIRA.Agent.Worker.Tests;

public sealed class AgentResultArtifactOutboxTests : IDisposable
{
    private readonly string _directory = Path.Combine(
        Path.GetTempPath(), "glafira-agent-artifact-outbox-" + Guid.NewGuid().ToString("N"));

    [Fact]
    public async Task RestartedOutboxDeletesOnlyAfterExactAcknowledgement()
    {
        var artifact = Artifact();
        await new FileAgentResultArtifactStore(_directory).WriteAsync(
            artifact.Metadata, artifact.Payload, CancellationToken.None);
        var transport = new FakeTransport(ExactAcknowledgement(artifact.Metadata, duplicate: false));
        var restarted = new AgentResultArtifactOutbox(
            new FileAgentResultArtifactStore(_directory), transport);

        Assert.True(await restarted.FlushOneAsync(Credential(), CancellationToken.None));
        Assert.Equal(1, transport.Attempts);
        Assert.Empty(await new FileAgentResultArtifactStore(_directory)
            .ListPendingArtifactIdsAsync(CancellationToken.None));
    }

    [Fact]
    public async Task LostNetworkAndMismatchedAckPreserveArtifactForRetry()
    {
        var artifact = Artifact();
        var store = new FileAgentResultArtifactStore(_directory);
        await store.WriteAsync(artifact.Metadata, artifact.Payload, CancellationToken.None);
        var transport = new FakeTransport(ExactAcknowledgement(artifact.Metadata, duplicate: false))
        {
            Failure = new HttpRequestException("network unavailable")
        };
        var outbox = new AgentResultArtifactOutbox(store, transport);

        await Assert.ThrowsAsync<HttpRequestException>(() =>
            outbox.FlushOneAsync(Credential(), CancellationToken.None));
        Assert.Equal(new[] { artifact.Metadata.ArtifactId },
            await store.ListPendingArtifactIdsAsync(CancellationToken.None));

        transport.Failure = null;
        transport.Acknowledgement = transport.Acknowledgement with { FencingToken = 43 };
        await Assert.ThrowsAsync<InvalidDataException>(() =>
            outbox.FlushOneAsync(Credential(), CancellationToken.None));
        Assert.Equal(new[] { artifact.Metadata.ArtifactId },
            await store.ListPendingArtifactIdsAsync(CancellationToken.None));
    }

    [Fact]
    public async Task DuplicateAcknowledgementClosesLostAckWindow()
    {
        var artifact = Artifact();
        var store = new FileAgentResultArtifactStore(_directory);
        await store.WriteAsync(artifact.Metadata, artifact.Payload, CancellationToken.None);
        var outbox = new AgentResultArtifactOutbox(
            store, new FakeTransport(ExactAcknowledgement(artifact.Metadata, duplicate: true)));

        Assert.True(await outbox.FlushOneAsync(Credential(), CancellationToken.None));
        Assert.Null(await store.ReadAsync(artifact.Metadata.ArtifactId, CancellationToken.None));
    }

    [Fact]
    public async Task StaleLeaseRejectionPreservesLocalArtifact()
    {
        var artifact = Artifact();
        var store = new FileAgentResultArtifactStore(_directory);
        await store.WriteAsync(artifact.Metadata, artifact.Payload, CancellationToken.None);
        var transport = new FakeTransport(ExactAcknowledgement(artifact.Metadata, duplicate: false))
        {
            Failure = new InvalidDataException("LEASE_FENCING_REJECTED")
        };

        await Assert.ThrowsAsync<InvalidDataException>(() =>
            new AgentResultArtifactOutbox(store, transport)
                .FlushOneAsync(Credential(), CancellationToken.None));
        Assert.NotNull(await store.ReadAsync(artifact.Metadata.ArtifactId, CancellationToken.None));
    }

    private static AgentResultArtifact Artifact()
    {
        var payload = Encoding.UTF8.GetBytes("{\"status\":\"healthy\"}");
        return new AgentResultArtifact(new AgentResultArtifactMetadata(
            1, "artifact-0001", "task-0001", "project-0001", "windows-core-0001", 3,
            "lease-0001", 42, AgentResultArtifactPolicy.DiagnosticsSchemaId,
            AgentResultArtifactPolicy.DiagnosticsSchemaVersion, AgentResultArtifactPolicy.JsonContentType,
            payload.Length, AgentResultArtifactPolicy.ComputeSha256(payload),
            AgentResultArtifactPolicy.Redacted, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()), payload);
    }

    private static AgentTaskArtifactAck ExactAcknowledgement(
        AgentResultArtifactMetadata metadata,
        bool duplicate) => new(
            metadata.ArtifactId, metadata.TaskId, metadata.OwnerCoreId, metadata.OwnerGeneration,
            metadata.LeaseId, metadata.FencingToken, metadata.SchemaId, metadata.SchemaVersion,
            metadata.ByteLength, metadata.Sha256, duplicate);

    private static AgentEnrollmentCredential Credential()
    {
        var now = DateTimeOffset.UtcNow;
        return new AgentEnrollmentCredential(
            "agent-001", "desktop-001", new Uri("https://127.0.0.1:8443"), new string('a', 64),
            Convert.ToBase64String(new byte[32]), new HashSet<string> { "desktop.task.result" },
            now.AddMinutes(-1), now.AddHours(1));
    }

    public void Dispose()
    {
        if (Directory.Exists(_directory)) Directory.Delete(_directory, recursive: true);
    }

    private sealed class FakeTransport(AgentTaskArtifactAck acknowledgement) : IAgentResultArtifactTransport
    {
        public AgentTaskArtifactAck Acknowledgement { get; set; } = acknowledgement;
        public Exception? Failure { get; set; }
        public int Attempts { get; private set; }

        public Task<AgentTaskArtifactAck> UploadTaskArtifactAsync(
            AgentEnrollmentCredential credential,
            AgentResultArtifact artifact,
            CancellationToken cancellationToken = default)
        {
            Attempts++;
            return Failure is null
                ? Task.FromResult(Acknowledgement)
                : Task.FromException<AgentTaskArtifactAck>(Failure);
        }
    }
}
