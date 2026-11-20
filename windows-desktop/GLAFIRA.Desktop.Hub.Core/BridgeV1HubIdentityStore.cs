namespace GLAFIRA.Desktop.Hub.Core;

/// <summary>
/// Stores a stable, non-secret Hub identifier. Corrupt identity data fails closed rather
/// than silently generating a new identity that would invalidate every enrolled Agent pin.
/// </summary>
public sealed class BridgeV1HubIdentityStore
{
    private readonly string _identityPath;
    private readonly object _sync = new();

    public BridgeV1HubIdentityStore(string identityPath)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(identityPath);
        _identityPath = Path.GetFullPath(identityPath);
    }

    public string LoadOrCreate()
    {
        lock (_sync)
        {
            var directory = Path.GetDirectoryName(_identityPath)
                ?? throw new InvalidOperationException("Hub identity path has no parent directory.");
            Directory.CreateDirectory(directory);

            if (File.Exists(_identityPath))
            {
                if ((File.GetAttributes(_identityPath) & FileAttributes.ReparsePoint) != 0)
                    throw new InvalidDataException("Hub identity cannot be a reparse point.");
                var value = File.ReadAllText(_identityPath).Trim();
                if (!Guid.TryParseExact(value, "N", out _))
                    throw new InvalidDataException("Stored Hub identity is invalid; refusing to replace it.");
                return value;
            }

            var identity = Guid.NewGuid().ToString("N");
            var temporaryPath = _identityPath + "." + Guid.NewGuid().ToString("N") + ".tmp";
            try
            {
                using (var stream = new FileStream(
                    temporaryPath, FileMode.CreateNew, FileAccess.Write, FileShare.None,
                    256, FileOptions.WriteThrough))
                using (var writer = new StreamWriter(stream, new System.Text.UTF8Encoding(false)))
                {
                    writer.Write(identity);
                    writer.Flush();
                    stream.Flush(flushToDisk: true);
                }
                File.Move(temporaryPath, _identityPath, overwrite: false);
                return identity;
            }
            catch (IOException) when (File.Exists(_identityPath))
            {
                if ((File.GetAttributes(_identityPath) & FileAttributes.ReparsePoint) != 0)
                    throw new InvalidDataException("Hub identity cannot be a reparse point.");
                var existing = File.ReadAllText(_identityPath).Trim();
                if (!Guid.TryParseExact(existing, "N", out _))
                    throw new InvalidDataException("Stored Hub identity is invalid; refusing to replace it.");
                return existing;
            }
            finally
            {
                if (File.Exists(temporaryPath))
                    File.Delete(temporaryPath);
            }
        }
    }
}
