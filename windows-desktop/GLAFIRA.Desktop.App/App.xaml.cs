using System.Windows;
using GLAFIRA.Desktop.Core.Identity;

namespace GLAFIRA.Desktop.App;

public partial class App : Application
{
    public DesktopCoreInstallationIdentity CoreIdentity { get; private set; } = null!;

    protected override void OnStartup(StartupEventArgs e)
    {
        try
        {
            CoreIdentity = new WindowsDesktopCoreInstallationIdentityStore().LoadOrCreate();
        }
        catch (Exception)
        {
            MessageBox.Show(
                "Не удалось подтвердить постоянную идентичность GLAFIRA Desktop Core. " +
                "Запуск остановлен: данные идентичности или ключ подписи повреждены, недоступны " +
                "либо принадлежат другой учётной записи Windows. Не удаляйте файл идентичности " +
                "и не создавайте новый Core ID автоматически; требуется восстановление владельцем.",
                "GLAFIRA Desktop — ошибка идентичности",
                MessageBoxButton.OK,
                MessageBoxImage.Error);
            Shutdown(2);
            return;
        }

        base.OnStartup(e);
    }
}
