using System.Security.Cryptography;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace GLAFIRA.Agent.Worker;

/// <summary>
/// Validates the machine-protected enrollment and maintains an authenticated,
/// TLS-pinned heartbeat with Desktop. Task claims remain disabled until typed
/// task handlers and durable result delivery are integrated.
/// </summary>
public sealed class AgentWorker(
    ILogger<AgentWorker> logger,
    AgentRuntimeState runtimeState,
    IAgentCredentialStore credentialStore,
    BridgeV1AgentClient bridgeClient) : BackgroundService
{
    private static readonly TimeSpan HeartbeatInterval = TimeSpan.FromSeconds(30);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        AgentEnrollmentCredential? credential;
        try
        {
            credential = await credentialStore.LoadAsync(stoppingToken).ConfigureAwait(false);
            if (credential is null)
            {
                runtimeState.ClearOwnerProfile();
                runtimeState.SetStatus(AgentServiceStatus.AwaitingOwnerEnrollment);
                logger.LogInformation(
                    "GLAFIRA Agent service started; no machine-protected enrollment is present.");
            }
            else
            {
                runtimeState.SetStatus(AgentServiceStatus.Enrolled);
                logger.LogInformation(
                    "GLAFIRA Agent {AgentId} loaded its machine-protected enrollment for Hub {HubId}.",
                    credential.AgentId, credential.DesktopHubId);
                await SendHeartbeatAsync(credential, stoppingToken).ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
        {
            return;
        }
        catch (Exception error) when (error is InvalidDataException or CryptographicException or IOException)
        {
            runtimeState.SetStatus(AgentServiceStatus.Faulted);
            logger.LogError(error, "GLAFIRA Agent enrollment could not be loaded; failing closed.");
            return;
        }

        try
        {
            using var timer = new PeriodicTimer(HeartbeatInterval);
            while (await timer.WaitForNextTickAsync(stoppingToken).ConfigureAwait(false))
            {
                if (credential is not null)
                    await SendHeartbeatAsync(credential, stoppingToken).ConfigureAwait(false);
                else
                    logger.LogDebug("GLAFIRA Agent is {AgentStatus}; waiting for enrollment.", runtimeState.Status);
            }
        }
        catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
        {
            // Normal Windows service shutdown.
        }
        finally
        {
            runtimeState.SetStatus(AgentServiceStatus.Stopped);
            logger.LogInformation("GLAFIRA Agent service stopped.");
        }
    }

    private async Task SendHeartbeatAsync(
        AgentEnrollmentCredential credential,
        CancellationToken cancellationToken)
    {
        try
        {
            var heartbeat = await bridgeClient.SendHeartbeatAsync(credential, cancellationToken)
                .ConfigureAwait(false);
            if (heartbeat.Accepted)
            {
                runtimeState.SetOwnerProfile(heartbeat.Capabilities, heartbeat.MaxParallelTasks);
                var wasConnected = runtimeState.Status == AgentServiceStatus.Connected;
                runtimeState.SetStatus(AgentServiceStatus.Connected);
                if (!wasConnected)
                {
                    logger.LogInformation(
                        "GLAFIRA Agent {AgentId} is connected to Desktop; owner approved {CapabilityCount} capabilities with parallelism {MaxParallel}. Task polling remains disabled.",
                        heartbeat.AgentId, heartbeat.Capabilities.Count, heartbeat.MaxParallelTasks);
                }
                else
                {
                    logger.LogDebug(
                        "GLAFIRA Agent {AgentId} heartbeat accepted.",
                        heartbeat.AgentId);
                }
                return;
            }

            runtimeState.ClearOwnerProfile();
            if (heartbeat.ErrorCode == "AGENT_NOT_OWNER_APPROVED")
            {
                if (runtimeState.Status != AgentServiceStatus.AwaitingOwnerApproval)
                    logger.LogInformation(
                        "GLAFIRA Agent {AgentId} is paired and waiting for owner approval in Desktop.",
                        credential.AgentId);
                runtimeState.SetStatus(AgentServiceStatus.AwaitingOwnerApproval);
            }
            else
            {
                if (runtimeState.Status != AgentServiceStatus.Paused)
                    logger.LogWarning(
                        "GLAFIRA Agent {AgentId} heartbeat was rejected ({ErrorCode}); no tasks will be claimed.",
                        credential.AgentId, heartbeat.ErrorCode);
                runtimeState.SetStatus(AgentServiceStatus.Paused);
            }
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception error) when (error is HttpRequestException or IOException or InvalidDataException or TaskCanceledException)
        {
            if (runtimeState.Status != AgentServiceStatus.Paused)
                logger.LogWarning(error,
                    "GLAFIRA Agent {AgentId} cannot reach Desktop; heartbeat will retry after {RetrySeconds}s.",
                    credential.AgentId, HeartbeatInterval.TotalSeconds);
            runtimeState.SetStatus(AgentServiceStatus.Paused);
        }
    }
}
