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
    private readonly DispatcherTimer _pairingTicketTimer = new() { Interval = TimeSpan.FromMinutes(5) };
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
            Clipboard.SetText(_bridgeHost.PairingTicketDescriptor);
        }
        catch (Exception)
        {
            MessageBox.Show("Не удалось скопировать билет. Вы можете выделить и скопировать текст вручную.",
                "GLAFIRA Bridge", MessageBoxButton.OK, MessageBoxImage.Warning);
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
