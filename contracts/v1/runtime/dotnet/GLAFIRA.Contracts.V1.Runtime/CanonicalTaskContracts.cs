using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;

namespace GLAFIRA.Contracts.V1.Runtime;

public sealed record TaskResourceBudgetV1(
    int? MaxRuntimeSeconds = null,
    int? MaxMemoryMiB = null,
    int? MaxCpuPercent = null);

public sealed record TaskEnvelopeV1(
    int SchemaVersion,
    string TaskId,
    string ProjectId,
    string IdempotencyKey,
    string TaskType,
    string PayloadRef,
    string PayloadSha256,
    string IssuedByDeviceId,
    string? TargetAgentId,
    IReadOnlyList<string> RequiredCapabilities,
    IReadOnlyList<string> DependencyTaskIds,
    string RiskClass,
    string? ApprovalRef,
    DateTimeOffset CreatedAt,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.Never)]
    DateTimeOffset? DeadlineAt,
    TaskResourceBudgetV1? ResourceBudget = null);

public sealed record TaskLeaseV1(
    int SchemaVersion,
    string LeaseId,
    string TaskId,
    string AgentId,
    long FencingToken,
    DateTimeOffset IssuedAt,
    DateTimeOffset ExpiresAt,
    int HeartbeatIntervalSeconds);

public sealed record TaskResultEnvelopeV1(
    int SchemaVersion,
    string TaskId,
    string AgentId,
    string LeaseId,
    long FencingToken,
    int Attempt,
    string Outcome,
    DateTimeOffset CompletedAt,
    string? ResultRef,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.Never)]
    string? ResultSha256,
    string? ErrorCode,
    bool Retryable,
    bool RequiresReconciliation);

/// <summary>
/// Strict runtime codec for the canonical contracts/v1 task lifecycle DTOs.
/// The JSON schema remains the language-neutral source; this codec makes the
/// production .NET Hub and Agent reject unknown versions and malformed values.
/// </summary>
public static partial class CanonicalTaskContractJson
{
    public const int SchemaVersion = 1;

    private static readonly JsonSerializerOptions Options = new(JsonSerializerDefaults.Web)
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        PropertyNameCaseInsensitive = false,
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow,
        MaxDepth = 8
    };

    public static string Serialize(TaskEnvelopeV1 value)
    {
        Validate(value);
        return JsonSerializer.Serialize(value, Options);
    }

    public static string Serialize(TaskLeaseV1 value)
    {
        Validate(value);
        return JsonSerializer.Serialize(value, Options);
    }

    public static string Serialize(TaskResultEnvelopeV1 value)
    {
        Validate(value);
        return JsonSerializer.Serialize(value, Options);
    }

    public static TaskEnvelopeV1 ParseTaskEnvelope(string json) =>
        Parse<TaskEnvelopeV1>(json, value => Validate(value));

    public static TaskLeaseV1 ParseTaskLease(string json) =>
        Parse<TaskLeaseV1>(json, value => Validate(value));

    public static TaskResultEnvelopeV1 ParseTaskResult(string json) =>
        Parse<TaskResultEnvelopeV1>(json, value => Validate(value));

    public static void Validate(TaskEnvelopeV1 value)
    {
        ArgumentNullException.ThrowIfNull(value);
        RequireVersion(value.SchemaVersion);
        RequireTaskIdentifier(value.TaskId, nameof(value.TaskId));
        RequireProjectIdentifier(value.ProjectId);
        RequireIdentifier(value.IdempotencyKey, nameof(value.IdempotencyKey));
        RequireCapability(value.TaskType, nameof(value.TaskType));
        RequireBounded(value.PayloadRef, 1, 512, nameof(value.PayloadRef));
        RequireSha256(value.PayloadSha256, nameof(value.PayloadSha256));
        RequireIdentifier(value.IssuedByDeviceId, nameof(value.IssuedByDeviceId));
        if (value.TargetAgentId is not null) RequireIdentifier(value.TargetAgentId, nameof(value.TargetAgentId));
        RequireUniqueCapabilities(value.RequiredCapabilities, nameof(value.RequiredCapabilities), 64);
        RequireUniqueTaskIdentifiers(value.DependencyTaskIds, nameof(value.DependencyTaskIds), 256);
        if (value.RiskClass is not ("READ_ONLY" or "LOCAL_WRITE" or "EXTERNAL_SIDE_EFFECT" or "PRIVILEGED"))
            throw new InvalidDataException("TASK_CONTRACT_RISK_INVALID");
        if (value.ApprovalRef is not null) RequireIdentifier(value.ApprovalRef, nameof(value.ApprovalRef));
        if ((value.RiskClass is "EXTERNAL_SIDE_EFFECT" or "PRIVILEGED") && value.ApprovalRef is null)
            throw new InvalidDataException("TASK_CONTRACT_APPROVAL_REQUIRED");
        if (value.CreatedAt == default || value.DeadlineAt is { } deadline && deadline <= value.CreatedAt)
            throw new InvalidDataException("TASK_CONTRACT_TIME_INVALID");
        if (value.ResourceBudget is { } budget &&
            (budget.MaxRuntimeSeconds is { } runtime && runtime is < 1 or > 86_400 ||
             budget.MaxMemoryMiB is { } memory && memory is < 64 or > 65_536 ||
             budget.MaxCpuPercent is { } cpu && cpu is < 1 or > 100))
            throw new InvalidDataException("TASK_CONTRACT_BUDGET_INVALID");
    }

    public static void Validate(TaskLeaseV1 value)
    {
        ArgumentNullException.ThrowIfNull(value);
        RequireVersion(value.SchemaVersion);
        RequireIdentifier(value.LeaseId, nameof(value.LeaseId));
        RequireTaskIdentifier(value.TaskId, nameof(value.TaskId));
        RequireIdentifier(value.AgentId, nameof(value.AgentId));
        if (value.FencingToken <= 0 || value.IssuedAt == default || value.ExpiresAt <= value.IssuedAt ||
            value.HeartbeatIntervalSeconds is < 5 or > 300)
            throw new InvalidDataException("TASK_LEASE_CONTRACT_INVALID");
    }

    public static void Validate(TaskResultEnvelopeV1 value)
    {
        ArgumentNullException.ThrowIfNull(value);
        RequireVersion(value.SchemaVersion);
        RequireTaskIdentifier(value.TaskId, nameof(value.TaskId));
        RequireIdentifier(value.AgentId, nameof(value.AgentId));
        RequireIdentifier(value.LeaseId, nameof(value.LeaseId));
        if (value.FencingToken <= 0 || value.Attempt is < 1 or > 100 || value.CompletedAt == default)
            throw new InvalidDataException("TASK_RESULT_CONTRACT_INVALID");
        if (value.Outcome is not ("SUCCEEDED" or "FAILED" or "CANCELLED" or "WAITING_FOR_OWNER" or "OUTCOME_UNKNOWN"))
            throw new InvalidDataException("TASK_RESULT_OUTCOME_INVALID");
        if (value.ResultRef is not null) RequireBounded(value.ResultRef, 1, 512, nameof(value.ResultRef));
        if (value.ResultSha256 is not null) RequireSha256(value.ResultSha256, nameof(value.ResultSha256));
        if (value.ErrorCode is not null && !ErrorCodePattern().IsMatch(value.ErrorCode))
            throw new InvalidDataException("TASK_RESULT_ERROR_INVALID");
        if (value.Outcome == "SUCCEEDED" && (value.ResultRef is null || value.ResultSha256 is null))
            throw new InvalidDataException("TASK_RESULT_REFERENCE_REQUIRED");
        if (value.Outcome == "OUTCOME_UNKNOWN" && (!value.RequiresReconciliation || value.Retryable))
            throw new InvalidDataException("TASK_RESULT_RECONCILIATION_REQUIRED");
    }

    private static T Parse<T>(string json, Action<T> validate)
    {
        if (string.IsNullOrWhiteSpace(json) || json.Length > 16_384)
            throw new InvalidDataException("TASK_CONTRACT_JSON_INVALID");
        T value;
        try
        {
            value = JsonSerializer.Deserialize<T>(json, Options) ??
                throw new InvalidDataException("TASK_CONTRACT_JSON_INVALID");
        }
        catch (JsonException error)
        {
            throw new InvalidDataException("TASK_CONTRACT_JSON_INVALID", error);
        }
        validate(value);
        return value;
    }

    private static void RequireVersion(int version)
    {
        if (version != SchemaVersion) throw new InvalidDataException("TASK_CONTRACT_VERSION_UNSUPPORTED");
    }

    private static void RequireIdentifier(string value, string field) =>
        RequirePattern(value, 8, 128, IdentifierPattern(), field);

    private static void RequireProjectIdentifier(string value) =>
        RequirePattern(value, 1, 128, ProjectIdentifierPattern(), "ProjectId");

    private static void RequireTaskIdentifier(string value, string field) =>
        RequirePattern(value, 1, 64, IdentifierPattern(), field);

    private static void RequireCapability(string value, string field) =>
        RequirePattern(value, 1, 96, CapabilityPattern(), field);

    private static void RequireSha256(string value, string field) =>
        RequirePattern(value, 64, 64, Sha256Pattern(), field);

    private static void RequirePattern(string value, int minimum, int maximum, Regex pattern, string field)
    {
        RequireBounded(value, minimum, maximum, field);
        if (!pattern.IsMatch(value)) throw new InvalidDataException($"TASK_CONTRACT_FIELD_INVALID:{field}");
    }

    private static void RequireBounded(string value, int minimum, int maximum, string field)
    {
        if (value is null || value.Length < minimum || value.Length > maximum)
            throw new InvalidDataException($"TASK_CONTRACT_FIELD_INVALID:{field}");
    }

    private static void RequireUniqueCapabilities(IReadOnlyList<string> values, string field, int maximum)
    {
        if (values is null || values.Count > maximum || values.Distinct(StringComparer.Ordinal).Count() != values.Count)
            throw new InvalidDataException($"TASK_CONTRACT_FIELD_INVALID:{field}");
        foreach (var value in values) RequireCapability(value, field);
    }

    private static void RequireUniqueTaskIdentifiers(IReadOnlyList<string> values, string field, int maximum)
    {
        if (values is null || values.Count > maximum || values.Distinct(StringComparer.Ordinal).Count() != values.Count)
            throw new InvalidDataException($"TASK_CONTRACT_FIELD_INVALID:{field}");
        foreach (var value in values) RequireTaskIdentifier(value, field);
    }

    [GeneratedRegex("^[A-Za-z0-9._-]+$", RegexOptions.CultureInvariant)]
    private static partial Regex IdentifierPattern();

    [GeneratedRegex("^[A-Za-z0-9._-]+$", RegexOptions.CultureInvariant)]
    private static partial Regex ProjectIdentifierPattern();

    [GeneratedRegex("^[a-z][a-z0-9._-]*$", RegexOptions.CultureInvariant)]
    private static partial Regex CapabilityPattern();

    [GeneratedRegex("^[a-f0-9]{64}$", RegexOptions.CultureInvariant)]
    private static partial Regex Sha256Pattern();

    [GeneratedRegex("^[A-Z0-9_]{1,64}$", RegexOptions.CultureInvariant)]
    private static partial Regex ErrorCodePattern();

}
