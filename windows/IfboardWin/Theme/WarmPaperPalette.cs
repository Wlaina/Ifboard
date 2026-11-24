using System.Windows;
using System.Windows.Media;
using IfboardWin.Models;
using IfboardWin.Services;

namespace IfboardWin.Theme;

/// <summary>
/// 暖纸色板（与 Readly / 手机端同一套令牌）：画布、卡片半透明实色、液态玻璃浮层、--brand-soft 选中底。
/// 同一深浅状态重复调用时直接跳过，避免每次打开托盘菜单都替换全部画刷、让整窗重新解析资源。
/// </summary>
public static class WarmPaperPalette
{
    private static bool? _appliedDark;
    private static ResourceDictionary? _appliedTo;

    public static bool IsDarkActive(UiThemeMode mode) => mode switch
    {
        UiThemeMode.Dark => true,
        UiThemeMode.Light => false,
        _ => SystemThemeService.IsWindowsDarkMode()
    };

    public static void ApplyToResources(ResourceDictionary res, UiThemeMode mode)
    {
        var dark = IsDarkActive(mode);
        if (_appliedDark == dark && ReferenceEquals(_appliedTo, res)) return;
        _appliedDark = dark;
        _appliedTo = res;

        SetBrush(res, "WpCanvasBrush", dark ? "#141413" : "#F5F4ED");
        SetBrush(res, "WpSurfaceBrush", dark ? "#252523" : "#FAF9F5");
        SetBrush(res, "WpCardBgBrush", dark ? "#DB252523" : "#C7FFFFFF");
        SetBrush(res, "WpCardSubtleBrush", dark ? "#C8292926" : "#EBFAF9F5");
        SetBrush(res, "WpGlassBgBrush", dark ? "#B3252523" : "#B8FFFFFF");
        SetBrush(res, "WpGlassBorderBrush", dark ? "#14FFFFFF" : "#80FFFFFF");
        SetBrush(res, "WpNavPillBgBrush", dark ? "#14FFFFFF" : "#0F141413");
        SetBrush(res, "WpTextBrush", dark ? "#FAF9F5" : "#141413");
        SetBrush(res, "WpTextSecondaryBrush", dark ? "#C2C0B6" : "#5E5D59");
        SetBrush(res, "WpMutedBrush", dark ? "#9C9A92" : "#87867F");
        SetBrush(res, "WpDividerBrush", dark ? "#30302E" : "#F0EEE6");
        SetBrush(res, "WpBorderStrongBrush", dark ? "#3D3D3A" : "#E3DACC");
        SetBrush(res, "WpInputBgBrush", dark ? "#1C1C1A" : "#FAF9F5");
        SetBrush(res, "WpBrandBrush", "#D97757");
        // --brand-ink：浅色下比主题色暗 12%，深色模式直接用 --brand
        SetBrush(res, "WpBrandInkBrush", dark ? "#D97757" : "#C6613F");
        SetBrush(res, "WpBrandSoftBrush", dark ? "#33D97757" : "#21D97757");
        SetBrush(res, "WpSageBrush", "#7D8B6A");
        SetBrush(res, "WpSageSoftBrush", dark ? "#307D8B6A" : "#227D8B6A");
        SetBrush(res, "WpErrorBrush", dark ? "#D15A5A" : "#B53333");
        SetBrush(res, "WpErrorSoftBrush", dark ? "#30B53333" : "#14B53333");
        SetBrush(res, "WpAmberBrush", "#D97706");
        SetBrush(res, "WpAmberSoftBrush", dark ? "#24D97706" : "#14D97706");
        SetBrush(res, "WpSwitchOffTrackBrush", dark ? "#3D3D3A" : "#E3DACC");
        SetBrush(res, "WpSwitchThumbBrush", "#FFFFFF");
        SetBrush(res, "WpSegmentOnBrush", dark ? "#3A3A37" : "#FFFFFF");

        // 背景色晕比 Readly 淡一半：工具界面先保证文字清晰
        SetGlowBrush(res, "WpGlow1Brush", ParseColor(dark ? "#18D97757" : "#22D97757"));
        SetGlowBrush(res, "WpGlow2Brush", ParseColor(dark ? "#107D8B6A" : "#1A7D8B6A"));
        SetGlowBrush(res, "WpGlow3Brush", ParseColor(dark ? "#0ED6AA68" : "#18D6AA68"));
    }

    public static Color ParseColor(string hex) =>
        (Color)ColorConverter.ConvertFromString(hex);

    private static void SetGlowBrush(ResourceDictionary res, string key, Color centerColor)
    {
        var edge = Color.FromArgb(0, centerColor.R, centerColor.G, centerColor.B);
        var brush = new RadialGradientBrush(centerColor, edge);
        brush.Freeze();
        res[key] = brush;
    }

    private static void SetBrush(ResourceDictionary res, string key, string hex)
    {
        var brush = new SolidColorBrush(ParseColor(hex));
        brush.Freeze();
        res[key] = brush;
    }
}
