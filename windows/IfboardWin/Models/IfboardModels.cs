using System.Windows.Media;

namespace IfboardWin.Models;

public enum ThemePresetKind
{
    ClaudeTerracotta,
    SystemAccent,
    WarmAmber
}

public enum UiThemeMode
{
    Auto,
    Light,
    Dark
}

public enum LayoutDensity
{
    Compact,
    Standard
}

public sealed class WebDavConfig
{
    public const string KoofrUrl = "https://app.koofr.net/dav/Koofr";
    public const string JianguoyunUrl = "https://dav.jianguoyun.com/dav/";

    public string Url { get; set; } = KoofrUrl;
    public string User { get; set; } = "";
    public string Password { get; set; } = "";
    public string Dir { get; set; } = "Ifboard";
    public long LastSyncUnixMs { get; set; }
}

public sealed record SyncResult(
    int Uploaded,
    int Downloaded,
    IReadOnlyList<string> OtherDevices
);

public sealed record ProfileTransferResult(
    int FileCount,
    string Summary
);

public sealed record GrammarRemoteInfo(
    string DownloadUrl,
    long SizeBytes,
    string? Sha256,
    string UpdatedAt
);

public sealed class GrammarMeta
{
    public string? Sha256 { get; set; }
    public string? UpdatedAt { get; set; }
    public long SizeBytes { get; set; }
}

public sealed class AppSettings
{
    public UiThemeMode UiMode { get; set; } = UiThemeMode.Auto;
    public ThemePresetKind WeaselTheme { get; set; } = ThemePresetKind.ClaudeTerracotta;
    public LayoutDensity Density { get; set; } = LayoutDensity.Compact;
    public int FontPoint { get; set; } = 12;
    public int CornerRadius { get; set; } = 7;
    public bool AutoFollowSystemAccentTray { get; set; } = true;
    public bool DisableAppForcedAscii { get; set; } = true;
}

public sealed class RimePreferences
{
    /// <summary>默认输入方案：rime_ice 或 double_pinyin_flypy</summary>
    public string PrimarySchema { get; set; } = "rime_ice";

    /// <summary>候选菜单同时保留两种方案，方便 F4 / Ctrl+` 切换</summary>
    public bool KeepBothSchemas { get; set; } = true;

    public int PageSize { get; set; } = 5;
    public bool FuzzyEnEng { get; set; }
    public bool FuzzyInIng { get; set; }
    public bool FuzzyZhChSh { get; set; }
    public bool KeepRawFlypyPreedit { get; set; } = true;
    public bool CtrlSpaceOnlySwitch { get; set; }
    public bool TabPageFlip { get; set; } = true;
    public bool GrammarEnabled { get; set; } = true;
}

public sealed record WeaselColorSchemeSpec(
    string Id,
    string Name,
    Color BackColor,
    Color BorderColor,
    Color TextColor,
    Color LabelColor,
    Color CommentColor,
    Color HilitedBackColor,
    Color HilitedTextColor,
    Color HilitedLabelColor,
    Color HilitedCommentColor
);
