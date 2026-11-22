using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using GLAFIRA.Desktop.Hub.Core;

namespace GLAFIRA.Desktop.App;

public sealed class HubDashboardViewModel : INotifyPropertyChanged
{
    private readonly DesktopHubStore _store;
    private string _status = "Готово";
    private DateTimeOffset _refreshedAt;
    private HubTaskSnapshot? _selectedTask;
    private string _diagnosticsProjectId = "default";
    private string _selectedTaskResultSummary =
        "Выберите задачу, чтобы увидеть безопасное состояние результата.";
    private string _selectedTaskArtifactReference = "—";
    private string _selectedTaskArtifactDigest = "—";
    private string _selectedTaskArtifactContract = "—";

    public HubDashboardViewModel(DesktopHubStore store, DesktopHubHostController bridgeHost)
    {
        _store = store;
        BridgeHost = bridgeHost;
        Refresh();
    }
    public DesktopHubHostController BridgeHost { get; }
    public ObservableCollection<HubAgentSnapshot> Agents { get; } = [];
    public ObservableCollection<HubTaskSnapshot> Tasks { get; } = [];
    public ObservableCollection<HubTaskEventSnapshot> TaskHistory { get; } = [];
    public string Status { get => _status; private set => Set(ref _status, value); }
    public DateTimeOffset RefreshedAt { get => _refreshedAt; private set => Set(ref _refreshedAt, value); }
    public HubTaskSnapshot? SelectedTask
    {
        get => _selectedTask;
        private set
        {
            if (!Set(ref _selectedTask, value)) return;
            OnPropertyChanged(nameof(CanCancelSelectedTask));
        }
    }
    public bool CanCancelSelectedTask => SelectedTask?.State is HubTaskState.Queued or HubTaskState.Leased;
    public string DiagnosticsProjectId
    {
        get => _diagnosticsProjectId;
        set
        {
            if (!Set(ref _diagnosticsProjectId, value)) return;
            OnPropertyChanged(nameof(CanQueueDiagnostics));
        }
    }
    public bool CanQueueDiagnostics => !string.IsNullOrWhiteSpace(DiagnosticsProjectId);
    public string SelectedTaskResultSummary
    {
        get => _selectedTaskResultSummary;
        private set => Set(ref _selectedTaskResultSummary, value);
    }
    public string SelectedTaskArtifactReference
    {
        get => _selectedTaskArtifactReference;
        private set => Set(ref _selectedTaskArtifactReference, value);
    }
    public string SelectedTaskArtifactDigest
    {
        get => _selectedTaskArtifactDigest;
        private set => Set(ref _selectedTaskArtifactDigest, value);
    }
    public string SelectedTaskArtifactContract
    {
        get => _selectedTaskArtifactContract;
        private set => Set(ref _selectedTaskArtifactContract, value);
    }
    public int OnlineAgents => Agents.Count(agent => agent.IsOnline && agent.OwnerApproved);
    public int PendingTasks => Tasks.Count(task => task.State == HubTaskState.Queued);
    public int AttentionTasks => Tasks.Count(task => task.State is HubTaskState.OutcomeUnknown or HubTaskState.Failed);

    public void Refresh()
    {
        try
        {
            var now = DateTimeOffset.UtcNow;
            _store.ReconcilePendingAgents(now);
            var agents = _store.GetAgents(now);
            var tasks = _store.GetTasks();
            Agents.Clear();
            foreach (var agent in agents) Agents.Add(agent);
            Tasks.Clear();
            foreach (var task in tasks) Tasks.Add(task);
            if (_selectedTask is not null)
                SelectedTask = Tasks.SingleOrDefault(task => task.TaskId == _selectedTask.TaskId);
            RefreshSelectedTaskResult();
            RefreshedAt = now;
            Status = $"Обновлено: {now.ToLocalTime():dd.MM.yyyy HH:mm:ss}";
            OnPropertyChanged(nameof(OnlineAgents));
            OnPropertyChanged(nameof(PendingTasks));
            OnPropertyChanged(nameof(AttentionTasks));
        }
        catch (Exception error) { Status = $"Не удалось прочитать состояние Desktop Hub: {error.Message}"; }
    }

    public void ApproveAgent(string agentId, IEnumerable<string> requestedCapabilities, int maxParallelTasks)
    {
        var agent = Agents.SingleOrDefault(item => StringComparer.Ordinal.Equals(item.AgentId, agentId))
            ?? throw new InvalidOperationException("Выбранный Agent больше не зарегистрирован.");
        var capabilities = requestedCapabilities
            .Select(value => value.Trim())
            .Where(value => value.Length > 0)
            .Distinct(StringComparer.Ordinal)
            .ToArray();
        var allowed = new HashSet<string>(
            [
                "document.analysis",
                "code.build",
                "web.research",
                "core.orchestration",
                HubDiagnosticsTaskContract.RequiredCapability
            ],
            StringComparer.Ordinal);
        if (capabilities.Length is < 1 or > 5 || capabilities.Any(value => !allowed.Contains(value)))
            throw new ArgumentException("Выберите одну или несколько поддерживаемых возможностей Agent.");
        if (!_store.ConfigureAgentApproval(agent.AgentId, capabilities, maxParallelTasks))
            throw new InvalidOperationException("Не удалось применить одобрение Agent.");
        Refresh();
        Status = $"Agent {agent.AgentId} одобрен. Он появится онлайн только после первого аутентифицированного heartbeat.";
    }

    public bool RevokeAgent(string agentId)
    {
        var revoked = BridgeHost.RevokeAgent(agentId);
        Refresh();
        Status = revoked
            ? $"Сопряжение Agent {agentId} отозвано; credential отключён."
            : $"Agent {agentId} не найден.";
        return revoked;
    }

    public void LoadTaskHistory(HubTaskSnapshot? task)
    {
        SelectedTask = task;
        TaskHistory.Clear();
        if (task is null)
        {
            RefreshSelectedTaskResult();
            Status = "Выберите задачу, чтобы просмотреть её историю.";
            return;
        }

        try
        {
            foreach (var item in _store.GetTaskHistory(task.TaskId, 500))
                TaskHistory.Add(item);
            RefreshSelectedTaskResult();
            Status = $"История задачи {task.TaskId}: {TaskHistory.Count} событий";
        }
        catch (Exception error)
        {
            Status = $"Не удалось загрузить историю задачи: {error.Message}";
        }
    }

    public async Task QueueDiagnosticsSnapshotAsync(CancellationToken cancellationToken = default)
    {
        var projectId = DiagnosticsProjectId.Trim();
        if (projectId.Length is < 1 or > 128)
            throw new ArgumentException("Project ID must contain 1–128 characters.", nameof(DiagnosticsProjectId));

        var submission = await BridgeHost.QueueDiagnosticsSnapshotAsync(projectId, cancellationToken)
            .ConfigureAwait(true);
        Refresh();
        var queued = Tasks.SingleOrDefault(task => task.TaskId == submission.TaskId);
        LoadTaskHistory(queued);
        Status = $"Безопасный локальный снимок диагностики поставлен в очередь: {submission.TaskId}";
    }

    public void CancelSelectedTask(string taskId)
    {
        var selected = SelectedTask;
        if (selected is null || !StringComparer.Ordinal.Equals(selected.TaskId, taskId))
            throw new InvalidOperationException("Выбранная задача больше не актуальна.");
        if (!_store.CancelTask(taskId, DateTimeOffset.UtcNow))
        {
            Refresh();
            Status = "Задача уже завершена или требует отдельной сверки результата; отмена не применена.";
            return;
        }

        Refresh();
        var updated = Tasks.SingleOrDefault(task => task.TaskId == taskId);
        LoadTaskHistory(updated);
        Status = updated?.State == HubTaskState.OutcomeUnknown
            ? "Остановка задачи не доказывает, что побочный эффект не произошёл. Задача переведена в OutcomeUnknown и требует сверки владельца."
            : "Задача отменена. Поздние progress/result сообщения от Agent будут отклонены по lease/fencing.";
    }

    private void RefreshSelectedTaskResult()
    {
        if (SelectedTask is null)
        {
            SelectedTaskResultSummary = "Выберите задачу, чтобы увидеть безопасное состояние результата.";
            SelectedTaskArtifactReference = "—";
            SelectedTaskArtifactDigest = "—";
            SelectedTaskArtifactContract = "—";
            return;
        }

        var result = _store.GetTaskOwnerResult(SelectedTask.TaskId);
        if (result is null)
        {
            SelectedTaskResultSummary = "Состояние результата недоступно.";
            SelectedTaskArtifactReference = "—";
            SelectedTaskArtifactDigest = "—";
            SelectedTaskArtifactContract = "—";
            return;
        }

        var completedAt = result.CompletedAt?.ToLocalTime().ToString("dd.MM.yyyy HH:mm:ss") ?? "—";
        var error = string.IsNullOrWhiteSpace(result.ErrorCode) ? "нет" : result.ErrorCode;
        SelectedTaskResultSummary = result.TerminalResultAvailable
            ? $"{result.State} · завершено {completedAt} · код ошибки: {error}"
            : $"{result.State} · терминальный результат ещё не получен";
        SelectedTaskArtifactReference = result.ResultReference ?? "—";
        SelectedTaskArtifactDigest = result.ResultSha256 ?? "—";
        SelectedTaskArtifactContract = result.ArtifactAvailable
            ? $"{result.ArtifactSchemaId} v{result.ArtifactSchemaVersion} · {result.ArtifactRedactionStatus}"
            : "Артефакт ещё не подтверждён или недоступен";
    }

    public event PropertyChangedEventHandler? PropertyChanged;
    private bool Set<T>(ref T field, T value, [CallerMemberName] string? name = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value)) return false;
        field = value;
        OnPropertyChanged(name);
        return true;
    }
    private void OnPropertyChanged(string? name) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}
