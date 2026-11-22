using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;
using System.ComponentModel;
using System.Diagnostics;
using System.Net.Http;
using GLAFIRA.Desktop.Hub.Core;


namespace GLAFIRA.Desktop.App;

public partial class MainWindow : Window
{
    private readonly HubDashboardViewModel _viewModel;
    private readonly DesktopHubHostController _bridgeHost;
    private readonly DispatcherTimer _pairingTicketTimer = new() { Interval = TimeSpan.FromMinutes(5) };
    private bool _allowClose;
    private readonly HttpClient _githubHttpClient = new() { Timeout = TimeSpan.FromSeconds(30) };
    private readonly GitHubDesktopTokenStore _githubTokenStore = new();
    private CancellationTokenSource? _githubAuthorizationCancellation;
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
        InitializeGitHubSessionState();
    }
    private void Refresh_Click(object sender, RoutedEventArgs e) => _viewModel.Refresh();
    private void Tasks_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (sender is DataGrid grid && grid.SelectedItem is HubTaskSnapshot task)
            _viewModel.LoadTaskHistory(task);
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
            Clipboard.SetText(_bridgeHost.PairingTicketDescriptor);
        }
        catch (Exception)
        {
            MessageBox.Show("Не удалось скопировать билет. Вы можете выделить и скопировать текст вручную.",
                "GLAFIRA Bridge", MessageBoxButton.OK, MessageBoxImage.Warning);
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
        _viewModel.RevokeAgent(agent.AgentId);
    }


    private void InitializeGitHubSessionState()
    {
        try
        {
            var saved = _githubTokenStore.Load();
            if (saved is null)
            {
                GitHubStatusText.Text = "GitHub не подключён";
                GitHubAccountText.Text = "Сессия отсутствует. Нажмите «Подключить GitHub».";
                GitHubDisconnectButton.IsEnabled = false;
                return;
            }

            GitHubStatusText.Text = "Защищённая сессия найдена";
            GitHubAccountText.Text = "Токен зашифрован DPAPI для текущего пользователя Windows. Нажмите «Проверить через повторное подключение» только если требуется обновить доступ.";
            GitHubConnectButton.Content = "Переподключить GitHub";
            GitHubDisconnectButton.IsEnabled = true;
        }
        catch (Exception)
        {
            GitHubStatusText.Text = "Сохранённую сессию прочитать не удалось";
            GitHubAccountText.Text = "Возможно, профиль Windows изменился или DPAPI не может расшифровать файл. Для продолжения потребуется повторная авторизация.";
            GitHubDisconnectButton.IsEnabled = true;
        }
    }

    private async void GitHubConnect_Click(object sender, RoutedEventArgs e)
    {
        if (_githubAuthorizationCancellation is not null) return;
        _githubAuthorizationCancellation = new CancellationTokenSource();
        var cancellationToken = _githubAuthorizationCancellation.Token;
        GitHubConnectButton.IsEnabled = false;
        GitHubCancelButton.IsEnabled = true;
        GitHubDeviceCodeText.Text = "";
        try
        {
            GitHubStatusText.Text = "Запрашиваем одноразовый код GitHub…";
            using var flowClient = new GitHubDesktopDeviceFlowClient(_githubHttpClient);
            var device = await flowClient.RequestDeviceCodeAsync(cancellationToken);
            GitHubDeviceCodeText.Text = device.UserCode;
            GitHubDeviceInstructionsText.Text = "Откройте https://github.com/login/device и введите код. Окно можно оставить открытым.";
            Process.Start(new ProcessStartInfo(device.VerificationUri.AbsoluteUri) { UseShellExecute = true });

            var interval = device.PollIntervalSeconds;
            while (DateTimeOffset.UtcNow < device.ExpiresAtUtc)
            {
                await Task.Delay(TimeSpan.FromSeconds(interval), cancellationToken);
                var poll = await flowClient.PollAsync(device, cancellationToken);
                if (poll.Status == GitHubDesktopDevicePollStatus.Pending) continue;
                if (poll.Status == GitHubDesktopDevicePollStatus.SlowDown)
                {
                    interval = poll.NextPollIntervalSeconds ?? Math.Min(interval + 5, 60);
                    continue;
                }
                if (poll.Status == GitHubDesktopDevicePollStatus.Denied)
                    throw new InvalidOperationException("Авторизация отклонена на странице GitHub.");
                if (poll.Status == GitHubDesktopDevicePollStatus.Expired)
                    throw new InvalidOperationException("Одноразовый код истёк. Запустите подключение ещё раз.");
                if (poll.Status != GitHubDesktopDevicePollStatus.Authorized || poll.Tokens is null)
                    throw new InvalidOperationException("GitHub вернул неизвестное состояние авторизации.");

                _githubTokenStore.Save(poll.Tokens);
                GitHubStatusText.Text = "Проверяем учётную запись и доступ к репозиторию…";
                var api = new GitHubDesktopApiClient(_githubHttpClient);
                var user = await api.GetAuthenticatedUserAsync(poll.Tokens.AccessToken, cancellationToken);
                var installations = await api.ListInstallationsAsync(poll.Tokens.AccessToken, cancellationToken);
                GitHubDesktopRepository? repository = null;
                foreach (var installation in installations)
                {
                    try
                    {
                        repository = await api.GetAllowlistedRepositoryAsync(
                            poll.Tokens.AccessToken, installation.Id, cancellationToken);
                        break;
                    }
                    catch (HttpRequestException) { }
                    catch (InvalidOperationException) { }
                }

                GitHubAccountText.Text = repository is null
                    ? $"Аккаунт: {user.Login}. GitHub App авторизован, но установка приложения пока не предоставляет доступ к {GitHubDesktopAppConfiguration.AllowedRepository}. Проверьте установку GitHub App и разрешённый репозиторий."
                    : $"Аккаунт: {user.Login} · Репозиторий: {repository.FullName} · Ветка по умолчанию: {repository.DefaultBranch}";
                GitHubStatusText.Text = repository is null
                    ? "Авторизация есть; доступ к репозиторию не подтверждён"
                    : "Подключение и доступ к разрешённому репозиторию подтверждены";
                GitHubDisconnectButton.IsEnabled = true;
                GitHubConnectButton.Content = "Переподключить GitHub";
                GitHubDeviceInstructionsText.Text = "Сессия сохранена в DPAPI CurrentUser. Этот экран пока выполняет только проверку доступа и не изменяет GitHub.";
                GitHubDeviceCodeText.Text = "";
                return;
            }
            throw new InvalidOperationException("Время действия одноразового кода истекло.");
        }
        catch (OperationCanceledException)
        {
            GitHubStatusText.Text = "Подключение отменено";
            GitHubDeviceInstructionsText.Text = "Одноразовый код больше не используется.";
            GitHubDeviceCodeText.Text = "";
        }
        catch (Exception error)
        {
            GitHubStatusText.Text = "Не удалось подключить GitHub";
            GitHubAccountText.Text = error.Message;
            GitHubDeviceInstructionsText.Text = "Проверьте интернет-соединение и разрешения GitHub App, затем повторите попытку.";
            GitHubDeviceCodeText.Text = "";
        }
        finally
        {
            _githubAuthorizationCancellation?.Dispose();
            _githubAuthorizationCancellation = null;
            GitHubConnectButton.IsEnabled = true;
            GitHubCancelButton.IsEnabled = false;
        }
    }

    private void GitHubCancel_Click(object sender, RoutedEventArgs e) =>
        _githubAuthorizationCancellation?.Cancel();

    private void GitHubDisconnect_Click(object sender, RoutedEventArgs e)
    {
        var answer = MessageBox.Show(
            "Удалить локальную GitHub-сессию этого пользователя Windows? Для дальнейшей работы потребуется новая авторизация.",
            "Отключить GitHub", MessageBoxButton.YesNo, MessageBoxImage.Warning);
        if (answer != MessageBoxResult.Yes) return;
        try
        {
            _githubTokenStore.Clear();
            GitHubStatusText.Text = "GitHub отключён";
            GitHubAccountText.Text = "Локальная защищённая сессия удалена.";
            GitHubDeviceInstructionsText.Text = "";
            GitHubDeviceCodeText.Text = "";
            GitHubConnectButton.Content = "Подключить GitHub";
            GitHubDisconnectButton.IsEnabled = false;
        }
        catch (Exception)
        {
            GitHubStatusText.Text = "Не удалось удалить локальную сессию";
            GitHubAccountText.Text = "Проверьте доступ к профилю Windows.";
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
        _githubAuthorizationCancellation?.Cancel();
        _bridgeHost.ClearPairingTicket();
        try
        {
            await _bridgeHost.DisposeAsync();
            _githubHttpClient.Dispose();
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
