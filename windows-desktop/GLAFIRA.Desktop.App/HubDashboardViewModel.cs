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
    public int OnlineAgents => Agents.Count(agent => agent.IsOnline && agent.OwnerApproved);
    public int PendingTasks => Tasks.Count(task => task.State == HubTaskState.Queued);
    public int AttentionTasks => Tasks.Count(task => task.State is HubTaskState.OutcomeUnknown or HubTaskState.Failed);

    public void Refresh()
    {
        try
        {
            var now = DateTimeOffset.UtcNow;
            var agents = _store.GetAgents(now);
            var tasks = _store.GetTasks();
            Agents.Clear();
            foreach (var agent in agents) Agents.Add(agent);
            Tasks.Clear();
            foreach (var task in tasks) Tasks.Add(task);
            RefreshedAt = now;
            Status = $"Обновлено: {now.ToLocalTime():dd.MM.yyyy HH:mm:ss}";
            OnPropertyChanged(nameof(OnlineAgents));
            OnPropertyChanged(nameof(PendingTasks));
            OnPropertyChanged(nameof(AttentionTasks));
        }
        catch (Exception error) { Status = $"Не удалось прочитать состояние Desktop Hub: {error.Message}"; }
    }

    public void LoadTaskHistory(HubTaskSnapshot? task)
    {
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

    public event PropertyChangedEventHandler? PropertyChanged;
    private void Set<T>(ref T field, T value, [CallerMemberName] string? name = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value)) return;
        field = value;
        OnPropertyChanged(name);
    }
    private void OnPropertyChanged(string? name) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}