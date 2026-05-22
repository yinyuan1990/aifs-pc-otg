#ifndef SHORTCUTSTORE_H
#define SHORTCUTSTORE_H

#include <QObject>
#include <QSettings>
#include <QString>

/**
 * 快捷键存储类（单例）
 * 管理应用程序的快捷键配置，使用 QSettings 持久化
 */
class ShortcutStore : public QObject
{
    Q_OBJECT
    
    // 全屏查看快捷键
    Q_PROPERTY(QString fullscreenViewerKey READ fullscreenViewerKey WRITE setFullscreenViewerKey NOTIFY shortcutsChanged)
    // 实时窗口切换快捷键
    Q_PROPERTY(QString realtimeWindowKey READ realtimeWindowKey WRITE setRealtimeWindowKey NOTIFY shortcutsChanged)
    // 慢放窗口切换快捷键
    Q_PROPERTY(QString slowmoWindowKey READ slowmoWindowKey WRITE setSlowmoWindowKey NOTIFY shortcutsChanged)
    // Grid全屏快捷键
    Q_PROPERTY(QString gridFullscreenKey READ gridFullscreenKey WRITE setGridFullscreenKey NOTIFY shortcutsChanged)

public:
    static ShortcutStore* instance();
    
    // 默认快捷键
    static constexpr const char* DEFAULT_FULLSCREEN_VIEWER_KEY = "A";
    static constexpr const char* DEFAULT_REALTIME_WINDOW_KEY = "G";
    static constexpr const char* DEFAULT_SLOWMO_WINDOW_KEY = "H";
    static constexpr const char* DEFAULT_GRID_FULLSCREEN_KEY = "F";
    
    // 全屏查看快捷键
    QString fullscreenViewerKey() const;
    void setFullscreenViewerKey(const QString &key);
    
    // 实时窗口切换快捷键
    QString realtimeWindowKey() const;
    void setRealtimeWindowKey(const QString &key);
    
    // 慢放窗口切换快捷键
    QString slowmoWindowKey() const;
    void setSlowmoWindowKey(const QString &key);
    
    // Grid全屏快捷键
    QString gridFullscreenKey() const;
    void setGridFullscreenKey(const QString &key);
    
    // 重置为默认值
    Q_INVOKABLE void resetToDefaults();

signals:
    void shortcutsChanged();

private:
    explicit ShortcutStore(QObject *parent = nullptr);
    ~ShortcutStore() = default;
    
    static ShortcutStore* s_instance;
    QSettings *m_settings;
};

#endif // SHORTCUTSTORE_H

