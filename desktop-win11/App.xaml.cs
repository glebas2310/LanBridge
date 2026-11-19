using System.Drawing;
using System.Windows;
using System.Windows.Interop;
using LanBridge.Core;
using Application = System.Windows.Application;
using Forms = System.Windows.Forms;

namespace LanBridge;

public partial class App : Application
{
    private Forms.NotifyIcon? _notifyIcon;
    private MainWindow? _mainWindow;
    private HwndSource? _hotkeyHwndSource;
    private static App? _instance;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        _instance = this;

        _mainWindow = new MainWindow();

        // 1. Setup System Tray Icon
        _notifyIcon = new Forms.NotifyIcon
        {
            Icon = SystemIcons.Application,
            Visible = true,
            Text = "LanBridge — P2P синхронизация буфера и файлов"
        };

        var contextMenu = new Forms.ContextMenuStrip();
        var openItem = new Forms.ToolStripMenuItem("Открыть LanBridge", null, (s, args) => _mainWindow.ToggleFlyoutVisibility());
        openItem.Font = new Font(openItem.Font, System.Drawing.FontStyle.Bold);
        contextMenu.Items.Add(openItem);

        contextMenu.Items.Add(new Forms.ToolStripSeparator());

        contextMenu.Items.Add("Выход", null, (s, args) =>
        {
            _notifyIcon.Visible = false;
            _notifyIcon.Dispose();
            Shutdown();
        });

        _notifyIcon.ContextMenuStrip = contextMenu;
        _notifyIcon.Click += (s, args) =>
        {
            if (args is Forms.MouseEventArgs me && me.Button == Forms.MouseButtons.Left)
            {
                _mainWindow.ToggleFlyoutVisibility();
            }
        };

        // 2. Register Global Hotkey Ctrl + Alt + B
        _mainWindow.Loaded += (s, args) =>
        {
            var helper = new WindowInteropHelper(_mainWindow);
            IntPtr hwnd = helper.Handle;
            _hotkeyHwndSource = HwndSource.FromHwnd(hwnd);
            _hotkeyHwndSource?.AddHook(HwndHook);

            Win32Interop.RegisterHotKey(
                hwnd,
                Win32Interop.HOTKEY_ID,
                Win32Interop.MOD_CONTROL | Win32Interop.MOD_ALT | Win32Interop.MOD_NOREPEAT,
                Win32Interop.VK_B);
        };

        // Show flyout on initial start
        _mainWindow.Show();
    }

    private IntPtr HwndHook(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg == Win32Interop.WM_HOTKEY && wParam.ToInt32() == Win32Interop.HOTKEY_ID)
        {
            _mainWindow?.ToggleFlyoutVisibility();
            handled = true;
        }
        return IntPtr.Zero;
    }

    public static void ShowNotification(string title, string message)
    {
        _instance?.Dispatcher.Invoke(() =>
        {
            _instance._notifyIcon?.ShowBalloonTip(3000, title, message, Forms.ToolTipIcon.Info);
        });
    }

    protected override void OnExit(ExitEventArgs e)
    {
        if (_mainWindow != null)
        {
            var helper = new WindowInteropHelper(_mainWindow);
            Win32Interop.UnregisterHotKey(helper.Handle, Win32Interop.HOTKEY_ID);
        }

        _notifyIcon?.Dispose();
        base.OnExit(e);
    }
}
