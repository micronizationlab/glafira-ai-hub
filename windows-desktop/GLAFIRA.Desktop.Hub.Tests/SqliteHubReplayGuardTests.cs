using GLAFIRA.Desktop.Hub.Core;
using Microsoft.Data.Sqlite;
using Xunit;

namespace GLAFIRA.Desktop.Hub.Tests;

public sealed class SqliteHubReplayGuardTests : IDisposable
{
    private readonly string _databasePath = Path.Combine(
        Path.GetTempPath(), "glafira-replay-" + Guid.NewGuid().ToString("N") + ".db");
    private readonly MutableTimeProvider _clock =
        new(DateTimeOffset.FromUnixTimeSeconds(1_800_000_000));

    [Fact]
    public async Task SameDeviceAndNonceIsRejectedAcrossGuardInstances()
    {
        var first = new SqliteHubReplayGuard(_databasePath, _clock);
        var second = new SqliteHubReplayGuard(_databasePath, _clock);
        var expiry = _clock.GetUtcNow().AddMinutes(5);

        Assert.True(await first.TryClaimAsync("device-1", "0123456789abcdef", expiry, default));
        Assert.False(await second.TryClaimAsync("device-1", "0123456789abcdef", expiry, default));
    }

    [Fact]
    public async Task SameNonceIsAllowedForDifferentDevices()
    {
        var guard = new SqliteHubReplayGuard(_databasePath, _clock);
        var expiry = _clock.GetUtcNow().AddMinutes(5);

        Assert.True(await guard.TryClaimAsync("device-1", "0123456789abcdef", expiry, default));
        Assert.True(await guard.TryClaimAsync("device-2", "0123456789abcdef", expiry, default));
    }

    [Fact]
    public async Task ExpiredClaimCanBeReusedAfterItsWindowEnds()
    {
        var guard = new SqliteHubReplayGuard(_databasePath, _clock);
        var firstExpiry = _clock.GetUtcNow().AddMinutes(1);
        Assert.True(await guard.TryClaimAsync("device-1", "0123456789abcdef", firstExpiry, default));

        _clock.Advance(TimeSpan.FromMinutes(2));

        Assert.True(await guard.TryClaimAsync(
            "device-1", "0123456789abcdef", _clock.GetUtcNow().AddMinutes(5), default));
    }

    [Fact]
    public async Task AlreadyExpiredRequestIsNotClaimed()
    {
        var guard = new SqliteHubReplayGuard(_databasePath, _clock);

        Assert.False(await guard.TryClaimAsync(
            "device-1", "0123456789abcdef", _clock.GetUtcNow().AddSeconds(-1), default));
    }

    [Fact]
    public async Task ConcurrentClaimsHaveExactlyOneWinner()
    {
        var guards = Enumerable.Range(0, 8)
            .Select(_ => new SqliteHubReplayGuard(_databasePath, _clock))
            .ToArray();
        var expiry = _clock.GetUtcNow().AddMinutes(5);

        var results = await Task.WhenAll(guards.Select(guard =>
            guard.TryClaimAsync("device-1", "0123456789abcdef", expiry, default).AsTask()));

        Assert.Single(results, result => result);
    }

    public void Dispose()
    {
        SqliteConnection.ClearAllPools();
        foreach (var path in new[] { _databasePath, _databasePath + "-wal", _databasePath + "-shm" })
        {
            try { if (File.Exists(path)) File.Delete(path); }
            catch (IOException) { }
        }
    }

    private sealed class MutableTimeProvider(DateTimeOffset initial) : TimeProvider
    {
        private DateTimeOffset _now = initial;
        public override DateTimeOffset GetUtcNow() => _now;
        public void Advance(TimeSpan duration) => _now = _now.Add(duration);
    }
}
