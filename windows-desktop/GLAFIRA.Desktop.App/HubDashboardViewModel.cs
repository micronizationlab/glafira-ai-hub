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
            ["document.analysis", "code.build", "web.research", "core.orchestration"],
            StringComparer.Ordinal);
        if (capabilities.Length is < 1 or > 4 || capabilities.Any(value => !allowed.Contains(value)))
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
            Status = "Выберите задачу, чтобы просмотреть её историю.";
            return;
        }

        try
        {
            foreach (var item in _store.GetTaskHistory(task.TaskId, 500))
                TaskHistory.Add(item);
            Status = $"История задачи {task.TaskId}: {TaskHistory.Count} событий";
        }
        catch (Exception error)
        {
            Status = $"Не удалось загрузить историю задачи: {error.Message}";
        }
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