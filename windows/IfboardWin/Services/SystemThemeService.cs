using System.Windows.Media;
using IfboardWin.Models;
using Microsoft.Win32;

namespace IfboardWin.Services;

public static class SystemThemeService
{
    public static bool IsWindowsDarkMode()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(
                @"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
            var val = key?.GetValue("AppsUseLightTheme");
            return val is int i && i == 0;
        }
        catch
        {
            return false;
        }
    }

    public static Color GetWindowsAccentColor()
    {
        // 优先读取 Explorer AccentPalette（索引 3 为标准系统主体色，精度高于 DWM ColorizationColor）
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(
                @"Software\Microsoft\Windows\CurrentVersion\Explorer\Accent");
            if (key?.GetValue("AccentPalette") is byte[] palette && palette.Length >= 16)
            {
                return Color.FromRgb(palette[12], palette[13], palette[14]);
            }
        }
        catch
        {
            // 回退 DWM 键
        }

        try
        {
            using var dwm = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\DWM");
            if (dwm?.GetValue("AccentColor") is int abgr)
            {
                var r = (byte)(abgr & 0xFF);
                var g = (byte)((abgr >> 8) & 0xFF);
                var b = (byte)((abgr >> 16) & 0xFF);
                return Color.FromRgb(r, g, b);
            }
            if (dwm?.GetValue("ColorizationColor") is int argb)
            {
                var r = (byte)((argb >> 16) & 0xFF);
                var g = (byte)((argb >> 8) & 0xFF);
                var b = (byte)(argb & 0xFF);
                return Color.FromRgb(r, g, b);
            }
        }
        catch
        {
            // 使用默认陶土橙
        }

        return Color.FromRgb(0xD9, 0x77, 0x57);
    }

    public static (WeaselColorSchemeSpec Light, WeaselColorSchemeSpec Dark) BuildSystemAccentSchemes(Color? overrideAccent = null)
    {
        var accent = overrideAccent ?? GetWindowsAccentColor();

        // 浅色候选窗：温润微染色纸面 + 高饱和明确高亮胶囊 + 高对比墨色文字
        var lightAccent = ClampLightness(accent, 0.34, 0.52);
        var lightBack = Blend(Color.FromRgb(0xFA, 0xF9, 0xF5), accent, 0.055);
        var lightBorder = Blend(Color.FromRgb(0xE2, 0xDC, 0xD2), accent, 0.16);
        var lightInk = Color.FromRgb(0x14, 0x14, 0x13);
        var lightMuted = Blend(Color.FromRgb(0x6C, 0x6A, 0x64), lightAccent, 0.18);
        var lightHiliteFg = PickContrastText(lightAccent);
        var lightHiliteSub = Blend(lightHiliteFg, lightAccent, 0.20);

        var lightSpec = new WeaselColorSchemeSpec(
            Id: "ifboard_system_accent",
            Name: "跟随系统主题色／System Accent",
            BackColor: Opaque(lightBack),
            BorderColor: Opaque(lightBorder),
            TextColor: Opaque(lightInk),
            LabelColor: Opaque(lightMuted),
            CommentColor: Opaque(lightMuted),
            HilitedBackColor: Opaque(lightAccent),
            HilitedTextColor: Opaque(lightHiliteFg),
            HilitedLabelColor: Opaque(lightHiliteSub),
            HilitedCommentColor: Opaque(lightHiliteSub)
        );

        // 深色候选窗：深炭微染色底面 + 提亮防暗沉主体色高亮
        var darkAccent = ClampLightness(accent, 0.48, 0.66);
        var darkBack = Blend(Color.FromRgb(0x1F, 0x1F, 0x1D), accent, 0.075);
        var darkBorder = Blend(Color.FromRgb(0x36, 0x36, 0x33), accent, 0.20);
        var darkInk = Color.FromRgb(0xF6, 0xF4, 0xEE);
        var darkMuted = Blend(Color.FromRgb(0x98, 0x96, 0x8E), darkAccent, 0.18);
        var darkHiliteFg = PickContrastText(darkAccent);
        var darkHiliteSub = Blend(darkHiliteFg, darkAccent, 0.22);

        var darkSpec = new WeaselColorSchemeSpec(
            Id: "ifboard_system_accent_dark",
            Name: "跟随系统主题色·暗色／System Accent Dark",
            BackColor: Opaque(darkBack),
            BorderColor: Opaque(darkBorder),
            TextColor: Opaque(darkInk),
            LabelColor: Opaque(darkMuted),
            CommentColor: Opaque(darkMuted),
            HilitedBackColor: Opaque(darkAccent),
            HilitedTextColor: Opaque(darkHiliteFg),
            HilitedLabelColor: Opaque(darkHiliteSub),
            HilitedCommentColor: Opaque(darkHiliteSub)
        );

        return (lightSpec, darkSpec);
    }

    public static Color ClampLightness(Color c, double minL, double maxL)
    {
        var (h, s, l) = RgbToHsl(c);
        var nl = Math.Clamp(l, minL, maxL);
        return HslToRgb(h, Math.Max(s, 0.28), nl);
    }

    public static Color Blend(Color a, Color b, double ratioB)
    {
        var t = Math.Clamp(ratioB, 0.0, 1.0);
        return Color.FromRgb(
            (byte)Math.Round(a.R + (b.R - a.R) * t),
            (byte)Math.Round(a.G + (b.G - a.G) * t),
            (byte)Math.Round(a.B + (b.B - a.B) * t)
        );
    }

    public static Color PickContrastText(Color bg)
    {
        // WCAG 相对亮度判定，浅色主体自动切换黑字保证清晰度
        double lum = (0.2126 * bg.R + 0.7152 * bg.G + 0.0722 * bg.B) / 255.0;
        return lum > 0.62 ? Color.FromRgb(0x14, 0x14, 0x13) : Colors.White;
    }

    public static Color Opaque(Color c) => Color.FromArgb(0xFF, c.R, c.G, c.B);

    private static (double H, double S, double L) RgbToHsl(Color c)
    {
        double r = c.R / 255.0, g = c.G / 255.0, b = c.B / 255.0;
        double max = Math.Max(r, Math.Max(g, b));
        double min = Math.Min(r, Math.Min(g, b));
        double h = 0, s, l = (max + min) / 2.0;

        if (Math.Abs(max - min) < 1e-6)
        {
            s = 0;
        }
        else
        {
            double d = max - min;
            s = l > 0.5 ? d / (2.0 - max - min) : d / (max + min);
            if (Math.Abs(max - r) < 1e-6)
                h = (g - b) / d + (g < b ? 6 : 0);
            else if (Math.Abs(max - g) < 1e-6)
                h = (b - r) / d + 2;
            else
                h = (r - g) / d + 4;
            h /= 6.0;
        }
        return (h, s, l);
    }

    private static Color HslToRgb(double h, double s, double l)
    {
        if (s <= 1e-6)
        {
            var v = (byte)Math.Round(l * 255);
            return Color.FromRgb(v, v, v);
        }
        double q = l < 0.5 ? l * (1 + s) : l + s - l * s;
        double p = 2 * l - q;
        static double HueToRgb(double p, double q, double t)
        {
            if (t < 0) t += 1;
            if (t > 1) t -= 1;
            if (t < 1.0 / 6.0) return p + (q - p) * 6.0 * t;
            if (t < 0.5) return q;
            if (t < 2.0 / 3.0) return p + (q - p) * (2.0 / 3.0 - t) * 6.0;
            return p;
        }
        return Color.FromRgb(
            (byte)Math.Round(HueToRgb(p, q, h + 1.0 / 3.0) * 255),
            (byte)Math.Round(HueToRgb(p, q, h) * 255),
            (byte)Math.Round(HueToRgb(p, q, h - 1.0 / 3.0) * 255)
        );
    }
}
