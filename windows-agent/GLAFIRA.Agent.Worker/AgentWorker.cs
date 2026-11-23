using System.Security.Cryptography;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace GLAFIRA.Agent.Worker;

/// <summary>
/// Validates machine-protected enrollment, maintains the authenticated TLS-pinned
/// Desktop heartbeat and polls only when at least one typed handler is registered.
/// The coordinator persists leases/results and executes ReadOnly handlers only.
/// </summary>
public sealed class AgentWorker(
    ILogger<AgentWorker> logger,
    AgentRuntimeState runtimeState,
    IAgentCredentialStore credentialStore,
    BridgeV1AgentClient bridgeClient,
    AgentTaskHandlerRegistry taskHandlers,
    AgentTaskPollingCoordinator taskPollingCoordinator,
    AgentResultArtifactOutbox artifactOutbox) : BackgroundService
{
    private static readonly TimeSpan HeartbeatInterval = TimeSpan.FromSeconds(30);
    private static readonly TimeSpan PollTick = TimeSpan.FromSeconds(1);

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
                if (await SendHeartbeatAsync(credential, stoppingToken).ConfigureAwait(false))
                    credential = null;
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
            var nextPoll = DateTimeOffset.UtcNow;
            var pollDelay = AgentPollingBackoff.MinimumDelay;
            var nextArtifactUpload = DateTimeOffset.UtcNow;
            var artifactUploadDelay = AgentPollingBackoff.MinimumDelay;
            var wasReady = false;
            using var timer = new PeriodicTimer(PollTick);
            while (await timer.WaitForNextTickAsync(stoppingToken).ConfigureAwait(false))
            {
                if (credential is null)
                {
                    logger.LogDebug("GLAFIRA Agent is {AgentStatus}; waiting for enrollment.", runtimeState.Status);
                    continue;
                }

                var now = DateTimeOffset.UtcNow;
                if (now >= nextHeartbeat)
                {
                    if (await SendHeartbeatAsync(credential, stoppingToken).ConfigureAwait(false))
                        credential = null;
                    nextHeartbeat = DateTimeOffset.UtcNow.Add(HeartbeatInterval);
                    if (credential is null) continue;
                }

                if (runtimeState.Status is AgentServiceStatus.Connected or AgentServiceStatus.Ready &&
                    now >= nextArtifactUpload)
                {
                    try
                    {
                        var uploaded = await artifactOutbox.FlushOneAsync(credential, stoppingToken)
                            .ConfigureAwait(false);
                        artifactUploadDelay = AgentPollingBackoff.NextDelay(
                            artifactUploadDelay, uploaded, failed: false);
                    }
                    catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
                    {
                        throw;
                    }
                    catch (Exception error) when (error is HttpRequestException or IOException or InvalidDataException or TaskCanceledException)
                    {
                        logger.LogWarning(error,
                            "GLAFIRA Agent {AgentId} artifact delivery failed; the validated local artifact remains in the durable outbox.",
                            credential.AgentId);
                        artifactUploadDelay = AgentPollingBackoff.NextDelay(
                            artifactUploadDelay, worked: false, failed: true);
                    }
                    nextArtifactUpload = DateTimeOffset.UtcNow.Add(artifactUploadDelay);
                }

                var ready = runtimeState.Status == AgentServiceStatus.Ready &&
                    taskHandlers.RegisteredTaskTypes.Count > 0;
                if (!ready)
                {
                    wasReady = false;
                    continue; // No claims while disconnected, revoked, paused or awaiting approval.
                }

                if (!wasReady)
                {
                    wasReady = true;
                    nextPoll = DateTimeOffset.UtcNow;
                    pollDelay = AgentPollingBackoff.MinimumDelay;
                }
                if (DateTimeOffset.UtcNow < nextPoll)
                    continue;

                try
                {
                    var worked = await taskPollingCoordinator.PollOnceAsync(credential, stoppingToken)
                        .ConfigureAwait(false);
                    pollDelay = AgentPollingBackoff.NextDelay(pollDelay, worked, failed: false);
                    nextPoll = DateTimeOffset.UtcNow.Add(pollDelay);
                }
                catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
                {
                    throw;
                }
                catch (Exception error) when (error is HttpRequestException or IOException or InvalidDataException or TaskCanceledException)
                {
                    logger.LogWarning(error,
                        "GLAFIRA Agent {AgentId} task polling/execution failed; durable lease/outbox state remains journaled and retry is backed off.",
                        credential.AgentId);
                    pollDelay = AgentPollingBackoff.NextDelay(pollDelay, worked: false, failed: true);
                    nextPoll = DateTimeOffset.UtcNow.Add(pollDelay);

                    if (error is HttpRequestException or IOException or TaskCanceledException or InvalidDataException)
                    {
                        runtimeState.ClearOwnerProfile();
                        runtimeState.SetStatus(AgentServiceStatus.Paused);
                        wasReady = false; // Heartbeat must re-establish owner-approved liveness before polling resumes.
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

    private async Task<bool> SendHeartbeatAsync(
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
                return false;
            }

            runtimeState.ClearOwnerProfile();
            if (await AgentCredentialRevocation.DeleteIfDefinitivelyRevokedAsync(
                    heartbeat.ErrorCode, credentialStore, cancellationToken).ConfigureAwait(false))
            {
                runtimeState.SetStatus(AgentServiceStatus.AwaitingOwnerEnrollment);
                logger.LogWarning(
                    "GLAFIRA Agent {AgentId} enrollment was revoked by its pinned Desktop Hub; the local machine credential was deleted.",
                    credential.AgentId);
                return true;
            }
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
            return false;
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
            return false;
        }
    }
}
