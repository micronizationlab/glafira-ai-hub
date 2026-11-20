using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;
using System.ComponentModel;
using GLAFIRA.Desktop.Hub.Core;

namespace GLAFIRA.Desktop.App;

public partial class MainWindow : Window
{
    private readonly HubDashboardViewModel _viewModel;
    private readonly DesktopHubHostController _bridgeHost;
    private readonly DispatcherTimer _pairingTicketTimer = new() { Interval = TimeSpan.FromMinutes(2) };
    private bool _allowClose;
    public MainWindow()
    {
        InitializeComponent();
        var root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "GLAFIRA", "DesktopHub");
        var databasePath = Path.Combine(root, "hub.db");
        var store = new DesktopHubStore(databasePath);
        _bridgeHost = new DesktopHubHostController(root, databasePath, store);
        _pairingTicketTimer.Tick += (_, _) =>
        {
            _pairingTicketTimer.Stop();
            _bridgeHost.ClearPairingTicket();
        };
        _viewModel = new HubDashboardViewModel(store, _bridgeHost);
        DataContext = _viewModel;
    }
    private void Refresh_Click(object sender, RoutedEventArgs e) => _viewModel.Refresh();
    private void Tasks_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (sender is DataGrid grid && grid.SelectedItem is HubTaskSnapshot task)
            _viewModel.LoadTaskHistory(task);
    }
    private void Agents_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (sender is DataGrid grid && grid.SelectedItem is HubAgentSnapshot agent)
            _bridgeHost.PairingAgentId = agent.AgentId;
    }
    private void RefreshBridgeAddresses_Click(object sender, RoutedEventArgs e) =>
        _bridgeHost.RefreshAvailableAddresses();

    private async void StartBridge_Click(object sender, RoutedEventArgs e) =>
        await _bridgeHost.StartAsync();

    private async void StopBridge_Click(object sender, RoutedEventArgs e) =>
        await _bridgeHost.StopAsync();

    private void CreatePairingTicket_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            _bridgeHost.CreatePairingTicket();
            _pairingTicketTimer.Stop();
            _pairingTicketTimer.Start();
        }
        catch (InvalidOperationException error)
        {
            MessageBox.Show(error.Message, "GLAFIRA Bridge", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
        catch (Exception)
        {
            MessageBox.Show("Не удалось создать билет. Проверьте состояние HTTPS Hub.", "GLAFIRA Bridge",
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void CopyPairingTicket_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            Clipboard.SetText(_bridgeHost.PairingInvitationText);
        }
        catch (Exception)
        {
            MessageBox.Show("Не удалось скопировать билет. Вы можете выделить и скопировать текст вручную.",
                "GLAFIRA Bridge", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    private void ClearPairingTicket_Click(object sender, RoutedEventArgs e)
    {
        _pairingTicketTimer.Stop();
        _bridgeHost.ClearPairingTicket();
    }

    private async void RevokeAgent_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            _pairingTicketTimer.Stop();
            await _bridgeHost.RevokeAgentAsync();
        }
        catch (Exception)
        {
            MessageBox.Show("Не удалось отозвать Agent. Проверьте идентификатор и хранилище Hub.",
                "GLAFIRA Bridge", MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private async void RePairAgent_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            await _bridgeHost.RevokeAndCreatePairingTicketAsync();
            _pairingTicketTimer.Stop();
            _pairingTicketTimer.Start();
        }
        catch (InvalidOperationException error)
        {
            MessageBox.Show(error.Message, "GLAFIRA Bridge", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
        catch (Exception)
        {
            MessageBox.Show("Повторное сопряжение не выполнено; операция отменена атомарно.",
                "GLAFIRA Bridge", MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    protected override async void OnClosing(CancelEventArgs e)
    {
        if (_allowClose)
        {
            base.OnClosing(e);
            return;
        }

        e.Cancel = true;
        _pairingTicketTimer.Stop();
        _bridgeHost.ClearPairingTicket();
        try
        {
            await _bridgeHost.DisposeAsync();
        }
        catch (Exception)
        {
            // Closing the Desktop Control Center must not leave an unhandled async-void failure.
        }
        finally
        {
            _allowClose = true;
            Close();
        }
    }
}
