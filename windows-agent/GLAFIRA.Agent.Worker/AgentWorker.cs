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
    BridgeV1AgentClient bridgeClient,
    AgentTaskHandlerRegistry taskHandlers,
    AgentTaskPollingCoordinator taskPollingCoordinator) : BackgroundService
{
    private static readonly TimeSpan HeartbeatInterval = TimeSpan.FromSeconds(30);
    private static readonly TimeSpan PollInterval = TimeSpan.FromSeconds(5);

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
            var nextHeartbeat = DateTimeOffset.UtcNow.Add(HeartbeatInterval);
            using var timer = new PeriodicTimer(PollInterval);
            while (await timer.WaitForNextTickAsync(stoppingToken).ConfigureAwait(false))
            {
                if (credential is null)
                {
                    logger.LogDebug("GLAFIRA Agent is {AgentStatus}; waiting for enrollment.", runtimeState.Status);
                    continue;
                }

                if (DateTimeOffset.UtcNow >= nextHeartbeat)
                {
                    await SendHeartbeatAsync(credential, stoppingToken).ConfigureAwait(false);
                    nextHeartbeat = DateTimeOffset.UtcNow.Add(HeartbeatInterval);
                }

                if (runtimeState.Status == AgentServiceStatus.Ready &&
                    taskHandlers.RegisteredTaskTypes.Count > 0)
                {
                    try
                    {
                        await taskPollingCoordinator.PollOnceAsync(credential, stoppingToken)
                            .ConfigureAwait(false);
                    }
                    catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
                    {
                        throw;
                    }
                    catch (Exception error) when (error is HttpRequestException or IOException or InvalidDataException or TaskCanceledException)
                    {
                        logger.LogWarning(error,
                            "GLAFIRA Agent {AgentId} task polling/execution did not complete; durable lease/outbox state will be reconciled on the next poll.",
                            credential.AgentId);
                    }
                }
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
                var canExecute = taskHandlers.RegisteredTaskTypes.Count > 0;
                var wasAvailable = runtimeState.Status is AgentServiceStatus.Connected or AgentServiceStatus.Ready;
                runtimeState.SetStatus(canExecute ? AgentServiceStatus.Ready : AgentServiceStatus.Connected);
                if (!wasAvailable)
                {
                    if (canExecute)
                    {
                        logger.LogInformation(
                            "GLAFIRA Agent {AgentId} is ready; {HandlerCount} typed task handlers are registered for {CapabilityCount} owner-approved capabilities.",
                            heartbeat.AgentId, taskHandlers.RegisteredTaskTypes.Count, heartbeat.Capabilities.Count);
                    }
                    else
                    {
                        logger.LogInformation(
                            "GLAFIRA Agent {AgentId} is connected to Desktop; no typed handlers are registered, so task claims remain disabled.",
                            heartbeat.AgentId);
                    }
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
