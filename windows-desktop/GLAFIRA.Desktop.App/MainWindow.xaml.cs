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
        var ownerCoreId = ((App)Application.Current).CoreIdentity.CoreId;
        _bridgeHost = new DesktopHubHostController(root, databasePath, store, ownerCoreId);
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

    private void CancelSelectedTask_Click(object sender, RoutedEventArgs e)
    {
        if (TasksGrid.SelectedItem is not HubTaskSnapshot task)
        {
            MessageBox.Show("Сначала выберите задачу.", "GLAFIRA Core",
                MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }

        var warning = task.State == HubTaskState.Leased && task.EffectRisk != HubTaskEffectRisk.ReadOnly
            ? "Задача уже выполняется и имеет риск записи/внешнего эффекта. Мы остановим дальнейшую работу Agent, но не будем предполагать, что эффект не произошёл: состояние станет OutcomeUnknown и потребует сверки. Продолжить?"
            : "Отменить выбранную задачу? Поздние сообщения Agent будут отклонены по lease/fencing.";
        var answer = MessageBox.Show(
            warning, "Подтверждение отмены", MessageBoxButton.YesNo, MessageBoxImage.Warning);
        if (answer != MessageBoxResult.Yes) return;

        try
        {
            _viewModel.CancelSelectedTask(task.TaskId);
        }
        catch (InvalidOperationException error)
        {
            MessageBox.Show(error.Message, "GLAFIRA Core",
                MessageBoxButton.OK, MessageBoxImage.Warning);
        }
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
            _viewModel.RevokeAgent(_bridgeHost.PairingAgentId);
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
            _viewModel.Refresh();
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

    private void ApproveSelectedAgent_Click(object sender, RoutedEventArgs e)
    {
        if (AgentsGrid.SelectedItem is not HubAgentSnapshot agent)
        {
            MessageBox.Show("Сначала выберите сопряжённый Agent.", "GLAFIRA Agent",
                MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }

        if (!int.TryParse(AgentParallelInput.Text, out var parallel) || parallel is < 1 or > 16)
        {
            MessageBox.Show("Параллельность должна быть числом от 1 до 16.", "GLAFIRA Agent",
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        try
        {
            var capabilities = AgentCapabilitiesInput.Text.Split(
                ',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
            _viewModel.ApproveAgent(agent.AgentId, capabilities, parallel);
        }
        catch (ArgumentException error)
        {
            MessageBox.Show(error.Message, "GLAFIRA Agent", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
        catch (InvalidOperationException error)
        {
            MessageBox.Show(error.Message, "GLAFIRA Agent", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    private void RevokeSelectedAgent_Click(object sender, RoutedEventArgs e)
    {
        if (AgentsGrid.SelectedItem is not HubAgentSnapshot agent)
        {
            MessageBox.Show("Сначала выберите Agent.", "GLAFIRA Agent",
                MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }

        var answer = MessageBox.Show(
            $"Отозвать сопряжение и отключить credential для {agent.AgentId}?",
            "Подтверждение отзыва", MessageBoxButton.YesNo, MessageBoxImage.Warning);
        if (answer != MessageBoxResult.Yes) return;
        _pairingTicketTimer.Stop();
        _bridgeHost.PairingAgentId = agent.AgentId;
        _viewModel.RevokeAgent(agent.AgentId);
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
