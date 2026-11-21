using System.Collections.ObjectModel;
using System.ComponentModel;
using System.IO;
using System.Net;
using System.Runtime.CompilerServices;
using System.Runtime.Versioning;
using System.Text.Json;
using GLAFIRA.Desktop.Hub.Core;

namespace GLAFIRA.Desktop.App;

[SupportedOSPlatform("windows")]
public sealed class DesktopHubHostController : INotifyPropertyChanged, IAsyncDisposable
{
    private const int HttpsPort = 8443;
    private readonly string _databasePath;
    private readonly string _ownerDeviceId;
    private readonly BridgeV1OwnershipTrustAnchor _ownershipTrustAnchor;
    private readonly DesktopHubStore _store;
    private readonly BridgeV1HubIdentityStore _hubIdentityStore;
    private readonly BridgeV1TlsCertificateStore _tlsCertificateStore;
    private readonly HubTaskPayloadStore _payloadStore;
    private readonly BridgeV1PairingTicketStore _pairingTickets;
    private readonly DpapiSqliteHubCredentialResolver _credentialResolver;
    private BridgeV1HttpsHost? _host;
    private BridgeV1TlsCertificateIdentity? _tlsIdentity;
    private string _status = "Остановлен";
    private string _availableAddressesText = "Поиск частных LAN-адресов…";
    private string _selectedAddress = string.Empty;
    private string _endpointsText = "—";
    private string _certificateFingerprint = "—";
    private string _pairingAgentId = string.Empty;
    private string _pairingScopesText = "desktop.task.claim, desktop.task.result";
    private string _pairingInvitationText = "—";
    private string _pairingStatus = "Укажите Agent ID, затем создайте краткоживущий код.";
    private string? _visiblePairingTicketId;

    public DesktopHubHostController(
        string rootDirectory,
        string databasePath,
        DesktopHubStore store,
        string ownerDeviceId,
        long ownershipSigningKeyEpoch,
        string ownershipSigningPublicKeySpkiBase64,
        string ownershipSigningPublicKeySha256)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(rootDirectory);
        ArgumentException.ThrowIfNullOrWhiteSpace(databasePath);
        ArgumentNullException.ThrowIfNull(store);
        ArgumentException.ThrowIfNullOrWhiteSpace(ownerDeviceId);
        if (ownerDeviceId.Length is < 8 or > 128 ||
            !ownerDeviceId.All(character =>
                char.IsAsciiLetterOrDigit(character) || character is '-' or '_' or '.'))
            throw new ArgumentException(
                "Owner device ID must be an 8–128 character ASCII identifier.", nameof(ownerDeviceId));

        var root = Path.GetFullPath(rootDirectory);
        _databasePath = Path.GetFullPath(databasePath);
        _ownerDeviceId = ownerDeviceId;
        _ownershipTrustAnchor = new BridgeV1OwnershipTrustAnchor(
            ownerDeviceId,
            ownershipSigningKeyEpoch,
            ownershipSigningPublicKeySpkiBase64,
            ownershipSigningPublicKeySha256);
        _store = store;
        _hubIdentityStore = new BridgeV1HubIdentityStore(Path.Combine(root, "hub-id.txt"));
        _tlsCertificateStore = new BridgeV1TlsCertificateStore();
        _payloadStore = new HubTaskPayloadStore(
            Path.Combine(root, "payloads"), new WindowsDpapiHubDataProtector());
        _pairingTickets = new BridgeV1PairingTicketStore(_databasePath);
        _credentialResolver = new DpapiSqliteHubCredentialResolver(_databasePath);
        RefreshAvailableAddresses();
    }

    public string Status { get => _status; private set => Set(ref _status, value); }
    public ObservableCollection<string> AvailableAddresses { get; } = [];
    public string SelectedAddress
    {
        get => _selectedAddress;
        set => Set(ref _selectedAddress, value);
    }
    public string AvailableAddressesText
    {
        get => _availableAddressesText;
        private set => Set(ref _availableAddressesText, value);
    }
    public string EndpointsText { get => _endpointsText; private set => Set(ref _endpointsText, value); }
    public string CertificateFingerprintSha256
    {
        get => _certificateFingerprint;
        private set => Set(ref _certificateFingerprint, value);
    }
    public string PairingAgentId
    {
        get => _pairingAgentId;
        set
        {
            if (!Set(ref _pairingAgentId, value)) return;
            Notify(nameof(CanCreatePairingTicket));
            Notify(nameof(CanRevokeAgent));
            Notify(nameof(CanRePairAgent));
        }
    }
    public string PairingScopesText
    {
        get => _pairingScopesText;
        set => Set(ref _pairingScopesText, value);
    }
    public string PairingInvitationText
    {
        get => _pairingInvitationText;
        private set => Set(ref _pairingInvitationText, value);
    }
    public string PairingStatus { get => _pairingStatus; private set => Set(ref _pairingStatus, value); }
    public bool IsRunning => _host?.IsRunning == true;
    public bool CanStart => !IsRunning;
    public bool CanStop => IsRunning;
    public bool CanCreatePairingTicket =>
        IsRunning && IsValidAgentId(PairingAgentId) && _visiblePairingTicketId is null;
    public bool CanCopyPairingTicket => IsRunning && _visiblePairingTicketId is not null;
    public bool CanRevokeAgent => IsValidAgentId(PairingAgentId);
    public bool CanRePairAgent => CanCreatePairingTicket;

    public void RefreshAvailableAddresses()
    {
        var addresses = BridgeV1LanAddressDiscovery.GetPrivateAddresses();
        AvailableAddresses.Clear();
        foreach (var address in addresses)
            AvailableAddresses.Add(address.ToString());
        if (!AvailableAddresses.Contains(SelectedAddress, StringComparer.Ordinal))
            SelectedAddress = AvailableAddresses.FirstOrDefault() ?? string.Empty;
        AvailableAddressesText = addresses.Count == 0
            ? "Активные частные LAN-адреса не найдены"
            : "Выберите адрес, доступный только в вашей локальной сети.";
    }

    public void ClearPairingTicket()
    {
        if (_visiblePairingTicketId is not null)
            _pairingTickets.Cancel(_visiblePairingTicketId);
        _visiblePairingTicketId = null;
        PairingInvitationText = "—";
        PairingStatus = "Код скрыт и аннулирован. Для сопряжения создайте новый одноразовый код.";
        NotifyPairingAvailability();
    }

    public async Task StartAsync(CancellationToken cancellationToken = default)
    {
        if (IsRunning) return;
        RefreshAvailableAddresses();

        try
        {
            var available = BridgeV1LanAddressDiscovery.GetPrivateAddresses();
            var selectedAddress = available.FirstOrDefault(address =>
                StringComparer.Ordinal.Equals(address.ToString(), SelectedAddress));
            if (selectedAddress is null)
            {
                Status = "Не запущен: выберите активный частный LAN-адрес.";
                return;
            }
            var addresses = new[] { selectedAddress };

            var hubId = _hubIdentityStore.LoadOrCreate();
            _tlsIdentity = _tlsCertificateStore.LoadOrCreate(hubId, addresses);
            var replayGuard = new SqliteHubReplayGuard(_databasePath);
            var authenticator = new BridgeV1RequestAuthenticator(_credentialResolver, replayGuard);
            var dispatcher = new BridgeV1TaskOperationDispatcher(_store, _payloadStore);
            var options = new BridgeV1HttpsHostOptions(hubId, addresses, HttpsPort);
            var pairingEndpoint = new BridgeV1PairingEndpoint(
                _pairingTickets,
                _store,
                hubId,
                _tlsIdentity.FingerprintSha256,
                _ownershipTrustAnchor);
            _host = new BridgeV1HttpsHost(
                options, _tlsIdentity, authenticator, dispatcher, pairingEndpoint);
            await _host.StartAsync(cancellationToken).ConfigureAwait(true);

            EndpointsText = string.Join("  |  ", _host.Endpoints.Select(endpoint => endpoint.ToString()));
            CertificateFingerprintSha256 = _host.CertificateFingerprintSha256;
            Status = $"HTTPS запущен · Hub {hubId}";
            Notify(nameof(IsRunning));
            Notify(nameof(CanStart));
            Notify(nameof(CanStop));
            NotifyPairingAvailability();
        }
        catch (InvalidOperationException error) when (error.Message == "BRIDGE_TLS_ROTATION_REQUIRED")
        {
            DisposeTlsIdentity();
            Status = "Не запущен: требуется явная ротация TLS-сертификата и повторное сопряжение устройств.";
            EndpointsText = "—";
            Notify(nameof(IsRunning));
            Notify(nameof(CanStart));
            Notify(nameof(CanStop));
            NotifyPairingAvailability();
        }
        catch (Exception)
        {
            if (_host is not null)
            {
                await _host.DisposeAsync().ConfigureAwait(true);
                _host = null;
            }
            DisposeTlsIdentity();
            Status = "Не удалось запустить HTTPS. Проверьте порт 8443, сетевой интерфейс и сертификат.";
            EndpointsText = "—";
            Notify(nameof(IsRunning));
            Notify(nameof(CanStart));
            Notify(nameof(CanStop));
            NotifyPairingAvailability();
        }
    }

    public async Task StopAsync(CancellationToken cancellationToken = default)
    {
        var host = _host;
        _host = null;
        try
        {
            if (host is not null)
            {
                await host.StopAsync(cancellationToken).ConfigureAwait(true);
                await host.DisposeAsync().ConfigureAwait(true);
            }
        }
        finally
        {
            ClearPairingTicket();
            DisposeTlsIdentity();
            EndpointsText = "—";
            Status = "Остановлен";
            PairingStatus = "HTTPS остановлен; показанный ранее код аннулирован.";
            Notify(nameof(IsRunning));
            Notify(nameof(CanStart));
            Notify(nameof(CanStop));
            NotifyPairingAvailability();
        }
    }

    public string CreatePairingTicket()
    {
        if (!CanCreatePairingTicket)
            throw new InvalidOperationException("Запустите HTTPS, укажите Agent ID и аннулируйте прежний код.");
        var endpoint = GetRunningEndpoint();
        var ticket = _pairingTickets.CreateForAgent(
            PairingAgentId, ParseScopes(), TimeSpan.FromMinutes(2));
        PublishInvitation(ticket, endpoint, "Создан новый одноразовый код.");
        return PairingInvitationText;
    }

    public bool RevokeAgent(string agentId)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(agentId);
        if (StringComparer.Ordinal.Equals(agentId, PairingAgentId))
            ClearPairingTicket();
        var now = DateTimeOffset.UtcNow;
        var enrollmentId = _pairingTickets.GetActiveEnrollmentId(agentId);
        AgentRevocationDecision? decision = null;
        var revoked = false;
        if (enrollmentId is not null)
        {
            decision = _pairingTickets.RevokeAgent(new AgentRevocationRequest(
                1,
                "revoke-" + Guid.NewGuid().ToString("N"),
                enrollmentId,
                agentId,
                _ownerDeviceId,
                now,
                AgentRevocationReason.OWNER_REQUEST));
            revoked = decision.Status is AgentRevocationStatus.REVOKED or AgentRevocationStatus.ALREADY_REVOKED;
        }
        else
        {
            // Clean up scheduler-only legacy rows that predate generation-bound enrollment.
            revoked = _store.RevokeAgent(agentId, now);
        }
        PairingStatus = revoked
            ? decision?.RequiresOwnerReconciliation == true
                ? $"Доступ {agentId} отозван; незавершённая запись требует проверки результата владельцем."
                : $"Доступ {agentId} отозван. Agent удалит старый credential после подтверждения Hub."
            : $"Активный credential {agentId} не найден.";
        return revoked;
    }

    public async Task<string> RevokeAndCreatePairingTicketAsync(CancellationToken cancellationToken = default)
    {
        if (!CanRePairAgent)
            throw new InvalidOperationException("Запустите HTTPS, укажите Agent ID и аннулируйте прежний код.");
        var endpoint = GetRunningEndpoint();
        var result = await _pairingTickets.RevokeAndCreateForAgentAsync(
            PairingAgentId, _ownerDeviceId, ParseScopes(), TimeSpan.FromMinutes(2), cancellationToken)
            .ConfigureAwait(true);
        PublishInvitation(
            result.Ticket,
            endpoint,
            result.RevokedExistingEnrollment
                ? result.RevocationDecision?.RequiresOwnerReconciliation == true
                    ? "Старый credential отозван; активная запись требует проверки владельцем; создан новый одноразовый код."
                    : "Старый credential отозван; создан новый одноразовый код."
                : "Активный credential не найден; создан новый одноразовый код.");
        return PairingInvitationText;
    }

    private Uri GetRunningEndpoint()
    {
        if (_host?.IsRunning != true || _host.Endpoints.Count != 1 || _tlsIdentity is null)
            throw new InvalidOperationException("Bridge HTTPS is not running.");
        return _host.Endpoints[0];
    }

    private string[] ParseScopes() => PairingScopesText
        .Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);

    private void PublishInvitation(BridgeV1PairingTicket ticket, Uri endpoint, string status)
    {
        _visiblePairingTicketId = ticket.TicketId;
        PairingInvitationText = JsonSerializer.Serialize(new
        {
            version = 1,
            agentId = ticket.AgentId,
            endpoint = endpoint.AbsoluteUri,
            tlsCertificateSha256 = CertificateFingerprintSha256,
            ownerCoreId = _ownershipTrustAnchor.OwnerCoreId,
            ownershipSigningKeyEpoch = _ownershipTrustAnchor.SigningKeyEpoch,
            ownershipSigningPublicKeySpkiBase64 = _ownershipTrustAnchor.SigningPublicKeySpkiBase64,
            ownershipSigningPublicKeySha256 = _ownershipTrustAnchor.SigningPublicKeySha256,
            ticketId = ticket.TicketId,
            pairingCode = ticket.PairingCode,
            scopes = ticket.Scopes.Order(StringComparer.Ordinal),
            expiresAtEpochMs = ticket.ExpiresAt.ToUnixTimeMilliseconds()
        });
        PairingStatus = $"{status} Действует до {ticket.ExpiresAt.ToLocalTime():HH:mm:ss}. Передайте bundle только выбранному Agent.";
        NotifyPairingAvailability();
    }

    private static bool IsValidAgentId(string value) => value is { Length: >= 1 and <= 128 } &&
        value.All(character => char.IsAsciiLetterOrDigit(character) || character is '-' or '_');

    private void NotifyPairingAvailability()
    {
        Notify(nameof(CanCreatePairingTicket));
        Notify(nameof(CanCopyPairingTicket));
        Notify(nameof(CanRevokeAgent));
        Notify(nameof(CanRePairAgent));
    }

    private void DisposeTlsIdentity()
    {
        _tlsIdentity?.Certificate.Dispose();
        _tlsIdentity = null;
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    private bool Set<T>(ref T field, T value, [CallerMemberName] string? name = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value)) return false;
        field = value;
        Notify(name);
        return true;
    }

    private void Notify(string? name) =>
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));

    public async ValueTask DisposeAsync() => await StopAsync().ConfigureAwait(false);
}
