using System.Collections.ObjectModel;
using System.ComponentModel;
using System.IO;
using System.Net;
using System.Runtime.CompilerServices;
using System.Runtime.Versioning;
using GLAFIRA.Desktop.Hub.Core;

namespace GLAFIRA.Desktop.App;

[SupportedOSPlatform("windows")]
public sealed class DesktopHubHostController : INotifyPropertyChanged, IAsyncDisposable
{
    private const int HttpsPort = 8443;
    private readonly string _databasePath;
    private readonly DesktopHubStore _store;
    private readonly BridgeV1HubIdentityStore _hubIdentityStore;
    private readonly BridgeV1TlsCertificateStore _tlsCertificateStore;
    private readonly HubTaskPayloadStore _payloadStore;
    private BridgeV1HttpsHost? _host;
    private BridgeV1TlsCertificateIdentity? _tlsIdentity;
    private string _status = "Остановлен";
    private string _availableAddressesText = "Поиск частных LAN-адресов…";
    private string _selectedAddress = string.Empty;
    private string _endpointsText = "—";
    private string _certificateFingerprint = "—";
    private string _pairingTicketDescriptor = "Сначала запустите HTTPS, затем создайте одноразовый билет сопряжения.";
    private bool _pairingTicketActive;

    public DesktopHubHostController(string rootDirectory, string databasePath, DesktopHubStore store)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(rootDirectory);
        ArgumentException.ThrowIfNullOrWhiteSpace(databasePath);
        ArgumentNullException.ThrowIfNull(store);

        var root = Path.GetFullPath(rootDirectory);
        _databasePath = Path.GetFullPath(databasePath);
        _store = store;
        _ = new BridgeV1PairingTicketStore(_databasePath);
        _hubIdentityStore = new BridgeV1HubIdentityStore(Path.Combine(root, "hub-id.txt"));
        _tlsCertificateStore = new BridgeV1TlsCertificateStore();
        _payloadStore = new HubTaskPayloadStore(
            Path.Combine(root, "payloads"), new WindowsDpapiHubDataProtector());
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
    public string PairingTicketDescriptor
    {
        get => _pairingTicketDescriptor;
        private set => Set(ref _pairingTicketDescriptor, value);
    }
    public bool IsRunning => _host?.IsRunning == true;
    public bool CanStart => !IsRunning;
    public bool CanStop => IsRunning;
    public bool CanCreatePairingTicket =>
        IsRunning && _host is not null && _tlsIdentity is not null && !_pairingTicketActive;
    public bool CanCopyPairingTicket =>
        IsRunning && _host is not null && PairingTicketDescriptor.Contains("Код:", StringComparison.Ordinal);

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

    public string CreatePairingTicket()
    {
        if (!CanCreatePairingTicket || _host is null || _tlsIdentity is null)
            throw new InvalidOperationException("Сначала запустите HTTPS Bridge.");

        // The code is returned once and only its digest is stored by the ticket store.
        var ticket = new BridgeV1PairingTicketStore(_databasePath).Create(
            ["desktop.task.claim", "desktop.task.result"],
            TimeSpan.FromMinutes(5));
        var endpoint = _host.Endpoints.Single().AbsoluteUri;
        _pairingTicketActive = true;
        PairingTicketDescriptor = string.Join(Environment.NewLine,
            "Сопряжение GLAFIRA Agent — действительно 5 минут",
            $"Endpoint: {endpoint}",
            $"TLS SHA-256: {_tlsIdentity.FingerprintSha256}",
            $"Ticket ID: {ticket.TicketId}",
            $"Код: {ticket.PairingCode}",
            $"Истекает: {ticket.ExpiresAt.ToLocalTime():dd.MM.yyyy HH:mm:ss}",
            "Scopes: desktop.task.claim, desktop.task.result",
            "Передавайте код только на подключаемый компьютер. После истечения создайте новый билет.");
        Notify(nameof(CanCreatePairingTicket));
        Notify(nameof(CanCopyPairingTicket));
        return PairingTicketDescriptor;
    }

    public void ClearPairingTicket()
    {
        PairingTicketDescriptor = "Билет скрыт. Для нового сопряжения создайте новый одноразовый билет.";
        _pairingTicketActive = false;
        Notify(nameof(CanCreatePairingTicket));
        Notify(nameof(CanCopyPairingTicket));
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
            var credentials = new DpapiSqliteHubCredentialResolver(_databasePath);
            var replayGuard = new SqliteHubReplayGuard(_databasePath);
            var authenticator = new BridgeV1RequestAuthenticator(credentials, replayGuard);
            var dispatcher = new BridgeV1TaskOperationDispatcher(_store, _payloadStore);
            var options = new BridgeV1HttpsHostOptions(hubId, addresses, HttpsPort);
            var pairingTickets = new BridgeV1PairingTicketStore(_databasePath);
            var pairingEndpoint = new BridgeV1PairingEndpoint(
                pairingTickets, _store, hubId, _tlsIdentity.FingerprintSha256);
            _host = new BridgeV1HttpsHost(
                options, _tlsIdentity, authenticator, dispatcher, pairingEndpoint);
            await _host.StartAsync(cancellationToken).ConfigureAwait(true);

            EndpointsText = string.Join("  |  ", _host.Endpoints.Select(endpoint => endpoint.ToString()));
            CertificateFingerprintSha256 = _host.CertificateFingerprintSha256;
            Status = $"HTTPS запущен · Hub {hubId}";
            Notify(nameof(IsRunning));
            Notify(nameof(CanStart));
            Notify(nameof(CanStop));
            Notify(nameof(CanCreatePairingTicket));
        }
        catch (InvalidOperationException error) when (error.Message == "BRIDGE_TLS_ROTATION_REQUIRED")
        {
            DisposeTlsIdentity();
            Status = "Не запущен: требуется явная ротация TLS-сертификата и повторное сопряжение устройств.";
            EndpointsText = "—";
            Notify(nameof(IsRunning));
            Notify(nameof(CanStart));
            Notify(nameof(CanStop));
            Notify(nameof(CanCreatePairingTicket));
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
            Notify(nameof(CanCreatePairingTicket));
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
            Notify(nameof(IsRunning));
            Notify(nameof(CanStart));
            Notify(nameof(CanStop));
            Notify(nameof(CanCreatePairingTicket));
        }
    }

    public bool RevokeAgent(string agentId)
    {
        // Ensure enrollment/credential tables exist before the atomic revoke transaction.
        _ = new BridgeV1PairingTicketStore(_databasePath);
        return _store.RevokeAgent(agentId, DateTimeOffset.UtcNow);
    }

    private void DisposeTlsIdentity()
    {
        _tlsIdentity?.Certificate.Dispose();
        _tlsIdentity = null;
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    private void Set<T>(ref T field, T value, [CallerMemberName] string? name = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value)) return;
        field = value;
        Notify(name);
    }

    private void Notify(string? name) =>
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));

    public async ValueTask DisposeAsync() => await StopAsync().ConfigureAwait(false);
}
