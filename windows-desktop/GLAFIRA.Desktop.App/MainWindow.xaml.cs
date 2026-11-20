using System.IO;
using System.Windows;
using GLAFIRA.Desktop.Hub.Core;

namespace GLAFIRA.Desktop.App;

public partial class MainWindow : Window
{
    private readonly HubDashboardViewModel _viewModel;
    public MainWindow()
    {
        InitializeComponent();
        var root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "GLAFIRA", "DesktopHub");
        var store = new DesktopHubStore(Path.Combine(root, "hub.db"));
        _viewModel = new HubDashboardViewModel(store);
        DataContext = _viewModel;
    }
    private void Refresh_Click(object sender, RoutedEventArgs e) => _viewModel.Refresh();
}