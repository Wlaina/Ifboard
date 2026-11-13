using System.Windows;
using System.Windows.Media;
using IfboardWin.Models;
using IfboardWin.Services;

namespace IfboardWin.Theme;

public static class WarmPaperPalette
{
    public static bool IsDarkActive(UiThemeMode mode) => mode switch
    {
        UiThemeMode.Dark => true,
        UiThemeMode.Light => false,
        _ => SystemThemeService.IsWindowsDarkMode()
    };

    public static void ApplyToResources(ResourceDictionary res, UiThemeMode mode)
    {
        var dark = IsDarkActive(mode);

        SetBrush(res, "WpCanvasBrush", dark ? "#141413" : "#F5F4ED");
        SetBrush(res, "WpSurfaceBrush", dark ? "#232321" : "#FAF9F5");
        SetBrush(res, "WpCardBgBrush", dark ? "#EA232321" : "#F5FFFFFF");
        SetBrush(res, "WpCardSubtleBrush", dark ? "#C8292926" : "#EBFAF9F5");
        SetBrush(res, "WpGlassBgBrush", dark ? "#E81B1B19" : "#EEFAF9F5");
        SetBrush(res, "WpGlassBorderBrush", dark ? "#26FFFFFF" : "#B0FFFFFF");
        SetBrush(res, "WpNavPillBgBrush", dark ? "#26FFFFFF" : "#D6F0EEE6");
        SetBrush(res, "WpTextBrush", dark ? "#FAF9F5" : "#141413");
        SetBrush(res, "WpTextSecondaryBrush", dark ? "#C2C0B6" : "#5E5D59");
        SetBrush(res, "WpMutedBrush", dark ? "#9C9A92" : "#87867F");
        SetBrush(res, "WpDividerBrush", dark ? "#30302E" : "#EFECE3");
        SetBrush(res, "WpBorderStrongBrush", dark ? "#3A3A36" : "#E3DACC");
        SetBrush(res, "WpInputBgBrush", dark ? "#1A1A18" : "#FFFFFF");
        SetBrush(res, "WpBrandBrush", "#D97757");
        SetBrush(res, "WpBrandInkBrush", dark ? "#E68A6B" : "#C6613F");
        SetBrush(res, "WpBrandSoftBrush", dark ? "#33D97757" : "#21D97757");
        SetBrush(res, "WpSageBrush", "#7D8B6A");
        SetBrush(res, "WpSageSoftBrush", dark ? "#307D8B6A" : "#227D8B6A");
        SetBrush(res, "WpErrorBrush", "#B53333");
        SetBrush(res, "WpErrorSoftBrush", dark ? "#30B53333" : "#1FB53333");
        SetBrush(res, "WpAmberBrush", "#D97706");
        SetBrush(res, "WpSwitchOffTrackBrush", dark ? "#3D3C38" : "#DDD8CC");
        SetBrush(res, "WpSwitchThumbBrush", "#FAF9F5");

        res["WpGlow1Color"] = ParseColor(dark ? "#24D97757" : "#3CD97757");
        res["WpGlow2Color"] = ParseColor(dark ? "#1A7D8B6A" : "#2E7D8B6A");
        res["WpGlow3Color"] = ParseColor(dark ? "#16D6AA68" : "#28D6AA68");

        SetGlowBrush(res, "WpGlow1Brush", ParseColor(dark ? "#2CD97757" : "#40D97757"));
        SetGlowBrush(res, "WpGlow2Brush", ParseColor(dark ? "#1E7D8B6A" : "#307D8B6A"));
        SetGlowBrush(res, "WpGlow3Brush", ParseColor(dark ? "#1AD6AA68" : "#2CD6AA68"));
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
