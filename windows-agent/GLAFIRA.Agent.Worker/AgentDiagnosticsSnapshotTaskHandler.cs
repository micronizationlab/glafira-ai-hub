using System.Buffers;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace GLAFIRA.Agent.Worker;

internal enum AgentDiagnosticsPlatform
{
    Unsupported,
    Windows
}

internal sealed record AgentSafeRuntimeDiagnostics(
    AgentDiagnosticsPlatform Platform,
    Architecture ProcessArchitecture,
    Architecture OsArchitecture,
    Version RuntimeVersion);

internal interface IAgentSafeDiagnosticsSource
{
    ValueTask<AgentSafeRuntimeDiagnostics> CollectAsync(
        CancellationToken cancellationToken);
}

/// <summary>
/// Reads only a fixed set of non-identifying runtime APIs. Host names, users, paths,
/// environment variables, network state, registry values and installed software are
/// deliberately outside this source contract.
/// </summary>
internal sealed class AgentSafeRuntimeDiagnosticsSource : IAgentSafeDiagnosticsSource
{
    public ValueTask<AgentSafeRuntimeDiagnostics> CollectAsync(
        CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        return ValueTask.FromResult(new AgentSafeRuntimeDiagnostics(
            OperatingSystem.IsWindows()
                ? AgentDiagnosticsPlatform.Windows
                : AgentDiagnosticsPlatform.Unsupported,
            RuntimeInformation.ProcessArchitecture,
            RuntimeInformation.OSArchitecture,
            Environment.Version));
    }
}

/// <summary>
/// The Agent's first executable task: one bounded, read-only, locally sourced and
/// redacted diagnostics snapshot. The request is a pinned empty-input contract; no
/// arbitrary path, command, process, registry, network or caller-provided field is read.
/// </summary>
internal sealed class AgentDiagnosticsSnapshotTaskHandler : IAgentTaskHandler
{
    public const string SupportedTaskType = "diagnostics.snapshot";
    public const string RequiredCapability = "agent.diagnostics.read";
    // The Hub payload store is content-addressed. Keeping the exact canonical digest as
    // both reference and digest lets the production resolver verify the request bytes
    // before a lease is issued, while the Agent independently pins the same contract.
    public const string RequestPayloadReference =
        "b8bed4167621e0151c9ad655844d0574be18d9a7cae05f998b035c7634efbae4";
    public const string RequestPayloadSha256 = RequestPayloadReference;
    public const string ArtifactResultReferencePrefix = "agent-artifact:";
    public const string CanonicalRequestJson =
        "{\"schemaVersion\":1,\"source\":\"local-safe-runtime\"}";

    private static readonly TimeSpan DefaultCollectionTimeout = TimeSpan.FromSeconds(5);
    private static readonly HashSet<Architecture> SupportedArchitectures =
    [
        Architecture.X86,
        Architecture.X64,
        Architecture.Arm,
        Architecture.Arm64,
        Architecture.Wasm,
        Architecture.S390x,
        Architecture.LoongArch64
    ];

    private readonly IAgentResultArtifactStore _artifactStore;
    private readonly IAgentSafeDiagnosticsSource _source;
    private readonly TimeSpan _collectionTimeout;

    public AgentDiagnosticsSnapshotTaskHandler(
        IAgentResultArtifactStore artifactStore,
        IAgentSafeDiagnosticsSource source)
        : this(artifactStore, source, DefaultCollectionTimeout)
    {
    }

    internal AgentDiagnosticsSnapshotTaskHandler(
        IAgentResultArtifactStore artifactStore,
        IAgentSafeDiagnosticsSource source,
        TimeSpan collectionTimeout)
    {
        ArgumentNullException.ThrowIfNull(artifactStore);
        ArgumentNullException.ThrowIfNull(source);
        if (collectionTimeout <= TimeSpan.Zero || collectionTimeout > TimeSpan.FromSeconds(30))
            throw new ArgumentOutOfRangeException(nameof(collectionTimeout));

        _artifactStore = artifactStore;
        _source = source;
        _collectionTimeout = collectionTimeout;
    }

    public string TaskType => SupportedTaskType;

    public async Task<AgentTaskResult> ExecuteAsync(
        AgentTaskEnvelope task,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(task);
        if (!StringComparer.Ordinal.Equals(task.TaskType, SupportedTaskType))
            return Failed(task, "DIAGNOSTICS_TASK_TYPE_INVALID");
        if (!StringComparer.Ordinal.Equals(task.EffectRisk, "ReadOnly"))
            return Failed(task, "NON_READ_ONLY_TASK_DISABLED");
        if (task.RequiredCapabilities.Count != 1 ||
            !task.RequiredCapabilities.Contains(RequiredCapability))
            return Failed(task, "DIAGNOSTICS_CAPABILITY_SCOPE_INVALID");
        if (!StringComparer.Ordinal.Equals(task.PayloadRef, RequestPayloadReference) ||
            !StringComparer.Ordinal.Equals(task.PayloadSha256, RequestPayloadSha256))
            return Failed(task, "DIAGNOSTICS_REQUEST_UNSUPPORTED");
        if (task.CreatedAtEpochMs <= 0 || string.IsNullOrWhiteSpace(task.LeaseId) ||
            task.FencingToken <= 0)
            return Failed(task, "DIAGNOSTICS_AUTHORITY_INVALID");

        AgentSafeRuntimeDiagnostics diagnostics;
        using (var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken))
        {
            timeout.CancelAfter(_collectionTimeout);
            try
            {
                diagnostics = await _source.CollectAsync(timeout.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                throw;
            }
            catch (OperationCanceledException)
            {
                return Failed(task, "DIAGNOSTICS_TIMEOUT");
            }
        }

        if (!IsSupported(diagnostics))
            return Failed(task, "DIAGNOSTICS_SOURCE_UNSUPPORTED");

        var payload = Serialize(diagnostics);
        if (payload.Length == 0 || payload.Length > AgentResultArtifactPolicy.MaximumArtifactBytes)
            return Failed(task, "DIAGNOSTICS_RESULT_SIZE_INVALID");

        var artifactId = CreateArtifactId(task);
        var payloadSha256 = AgentResultArtifactPolicy.ComputeSha256(payload);
        var metadata = new AgentResultArtifactMetadata(
            AgentResultArtifactPolicy.CurrentContractVersion,
            artifactId,
            task.TaskId,
            task.ProjectId,
            task.OwnerCoreId,
            task.OwnerGeneration,
            task.LeaseId,
            task.FencingToken,
            AgentResultArtifactPolicy.DiagnosticsSchemaId,
            AgentResultArtifactPolicy.DiagnosticsSchemaVersion,
            AgentResultArtifactPolicy.JsonContentType,
            payload.Length,
            payloadSha256,
            AgentResultArtifactPolicy.Redacted,
            task.CreatedAtEpochMs);

        await _artifactStore.WriteAsync(metadata, payload, cancellationToken).ConfigureAwait(false);
        return new AgentTaskResult(
            task.TaskId, "SUCCEEDED", ArtifactResultReferencePrefix + artifactId, payloadSha256);
    }

    private static bool IsSupported(AgentSafeRuntimeDiagnostics diagnostics) =>
        diagnostics.Platform == AgentDiagnosticsPlatform.Windows &&
        SupportedArchitectures.Contains(diagnostics.ProcessArchitecture) &&
        SupportedArchitectures.Contains(diagnostics.OsArchitecture) &&
        diagnostics.RuntimeVersion.Major is >= 1 and <= 999 &&
        diagnostics.RuntimeVersion.Minor is >= 0 and <= 99999 &&
        diagnostics.RuntimeVersion.Build is >= 0 and <= 99999 &&
        diagnostics.RuntimeVersion.Revision is >= -1 and <= 99999;

    private static byte[] Serialize(AgentSafeRuntimeDiagnostics diagnostics)
    {
        var output = new ArrayBufferWriter<byte>();
        using (var writer = new Utf8JsonWriter(output))
        {
            writer.WriteStartObject();
            writer.WriteNumber("contractVersion", AgentResultArtifactPolicy.CurrentContractVersion);
            writer.WriteString("schemaId", AgentResultArtifactPolicy.DiagnosticsSchemaId);
            writer.WriteNumber("schemaVersion", AgentResultArtifactPolicy.DiagnosticsSchemaVersion);
            writer.WriteString("redactionStatus", AgentResultArtifactPolicy.Redacted);
            writer.WriteString("source", "local-safe-runtime");
            writer.WriteString("platform", "windows");
            writer.WriteString("processArchitecture", ArchitectureName(diagnostics.ProcessArchitecture));
            writer.WriteString("osArchitecture", ArchitectureName(diagnostics.OsArchitecture));
            writer.WriteString("runtimeFamily", "dotnet");
            writer.WriteString("runtimeVersion", diagnostics.RuntimeVersion.ToString());
            writer.WriteEndObject();
        }
        return output.WrittenSpan.ToArray();
    }

    private static string ArchitectureName(Architecture architecture) => architecture switch
    {
        Architecture.X86 => "x86",
        Architecture.X64 => "x64",
        Architecture.Arm => "arm",
        Architecture.Arm64 => "arm64",
        Architecture.Wasm => "wasm",
        Architecture.S390x => "s390x",
        Architecture.LoongArch64 => "loongarch64",
        _ => throw new InvalidOperationException("Unsupported architecture value.")
    };

    private static string CreateArtifactId(AgentTaskEnvelope task)
    {
        var identity = new ArrayBufferWriter<byte>();
        using (var writer = new Utf8JsonWriter(identity))
        {
            writer.WriteStartArray();
            writer.WriteStringValue(task.ProjectId);
            writer.WriteStringValue(task.TaskId);
            writer.WriteStringValue(task.OwnerCoreId);
            writer.WriteNumberValue(task.OwnerGeneration);
            writer.WriteStringValue(task.LeaseId);
            writer.WriteNumberValue(task.FencingToken);
            writer.WriteEndArray();
        }
        return "diag-" + Convert.ToHexString(SHA256.HashData(identity.WrittenSpan)).ToLowerInvariant();
    }

    private static AgentTaskResult Failed(AgentTaskEnvelope task, string errorCode) =>
        new(task.TaskId, "FAILED", ErrorCode: errorCode);
}
