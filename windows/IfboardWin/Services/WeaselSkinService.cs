using System.IO;
using System.Text;
using System.Windows.Media;
using IfboardWin.Models;

namespace IfboardWin.Services;

public static class WeaselSkinService
{
    public static (WeaselColorSchemeSpec Light, WeaselColorSchemeSpec Dark) GetTerracottaSchemes() =>
    (
        new WeaselColorSchemeSpec(
            Id: "claude_terracotta",
            Name: "Claude 陶土橙／Anthropic Terracotta",
            BackColor: Color.FromRgb(0xFA, 0xF9, 0xF5),
            BorderColor: Color.FromRgb(0xE6, 0xE2, 0xD6),
            TextColor: Color.FromRgb(0x14, 0x14, 0x13),
            LabelColor: Color.FromRgb(0x8C, 0x87, 0x7B),
            CommentColor: Color.FromRgb(0x9E, 0x98, 0x8B),
            HilitedBackColor: Color.FromRgb(0xD9, 0x77, 0x57),
            HilitedTextColor: Colors.White,
            HilitedLabelColor: Color.FromRgb(0xFF, 0xE8, 0xDF),
            HilitedCommentColor: Color.FromRgb(0xFF, 0xE8, 0xDF)
        ),
        new WeaselColorSchemeSpec(
            Id: "claude_terracotta_dark",
            Name: "Claude 陶土橙·暗色／Anthropic Terracotta Dark",
            BackColor: Color.FromRgb(0x26, 0x26, 0x24),
            BorderColor: Color.FromRgb(0x3D, 0x3C, 0x38),
            TextColor: Color.FromRgb(0xEC, 0xE9, 0xE0),
            LabelColor: Color.FromRgb(0x8C, 0x87, 0x7B),
            CommentColor: Color.FromRgb(0x8C, 0x87, 0x7B),
            HilitedBackColor: Color.FromRgb(0xD9, 0x77, 0x57),
            HilitedTextColor: Colors.White,
            HilitedLabelColor: Color.FromRgb(0xFF, 0xE8, 0xDF),
            HilitedCommentColor: Color.FromRgb(0xFF, 0xE8, 0xDF)
        )
    );

    public static (WeaselColorSchemeSpec Light, WeaselColorSchemeSpec Dark) GetWarmAmberSchemes() =>
    (
        new WeaselColorSchemeSpec(
            Id: "ifboard_warm_amber",
            Name: "暖纸琥珀／Warm Paper Amber",
            BackColor: Color.FromRgb(0xF9, 0xF5, 0xEB),
            BorderColor: Color.FromRgb(0xE2, 0xD7, 0xC1),
            TextColor: Color.FromRgb(0x22, 0x1B, 0x12),
            LabelColor: Color.FromRgb(0x8A, 0x7B, 0x63),
            CommentColor: Color.FromRgb(0x9A, 0x8B, 0x74),
            HilitedBackColor: Color.FromRgb(0xB8, 0x6B, 0x22),
            HilitedTextColor: Colors.White,
            HilitedLabelColor: Color.FromRgb(0xFF, 0xF1, 0xDE),
            HilitedCommentColor: Color.FromRgb(0xFF, 0xF1, 0xDE)
        ),
        new WeaselColorSchemeSpec(
            Id: "ifboard_warm_amber_dark",
            Name: "暖纸琥珀·暗色／Warm Paper Amber Dark",
            BackColor: Color.FromRgb(0x22, 0x1E, 0x19),
            BorderColor: Color.FromRgb(0x3D, 0x35, 0x2B),
            TextColor: Color.FromRgb(0xF3, 0xEC, 0xDF),
            LabelColor: Color.FromRgb(0x9B, 0x8F, 0x7E),
            CommentColor: Color.FromRgb(0x9B, 0x8F, 0x7E),
            HilitedBackColor: Color.FromRgb(0xD9, 0x82, 0x2B),
            HilitedTextColor: Color.FromRgb(0x14, 0x12, 0x0F),
            HilitedLabelColor: Color.FromRgb(0x2D, 0x23, 0x16),
            HilitedCommentColor: Color.FromRgb(0x2D, 0x23, 0x16)
        )
    );

    public static (WeaselColorSchemeSpec Light, WeaselColorSchemeSpec Dark) ResolveActiveSchemes(ThemePresetKind kind) => kind switch
    {
        ThemePresetKind.SystemAccent => SystemThemeService.BuildSystemAccentSchemes(),
        ThemePresetKind.WarmAmber => GetWarmAmberSchemes(),
        _ => GetTerracottaSchemes()
    };

    public static void ReadCurrentIntoSettings(AppSettings settings)
    {
        if (!File.Exists(Paths.WeaselCustomYaml)) return;
        var text = File.ReadAllText(Paths.WeaselCustomYaml, Encoding.UTF8);

        foreach (var rawLine in text.Split('\n'))
        {
            var line = rawLine.Trim();
            if (line.StartsWith("style/color_scheme:", StringComparison.OrdinalIgnoreCase))
            {
                var scheme = line["style/color_scheme:".Length..].Trim();
                settings.WeaselTheme = scheme switch
                {
                    "ifboard_system_accent" => ThemePresetKind.SystemAccent,
                    "ifboard_warm_amber" => ThemePresetKind.WarmAmber,
                    _ => ThemePresetKind.ClaudeTerracotta
                };
            }
            else if (line.StartsWith("style/font_point:", StringComparison.OrdinalIgnoreCase) &&
                     int.TryParse(line["style/font_point:".Length..].Trim(), out var fp))
            {
                settings.FontPoint = Math.Clamp(fp, 10, 18);
            }
            else if (line.StartsWith("style/layout/corner_radius:", StringComparison.OrdinalIgnoreCase) &&
                     int.TryParse(line["style/layout/corner_radius:".Length..].Trim(), out var cr))
            {
                settings.CornerRadius = Math.Clamp(cr, 0, 14);
            }
            else if (line.StartsWith("style/layout/margin_y:", StringComparison.OrdinalIgnoreCase) &&
                     int.TryParse(line["style/layout/margin_y:".Length..].Trim(), out var my))
            {
                settings.Density = my <= 5 ? LayoutDensity.Compact : LayoutDensity.Standard;
            }
        }
    }

    public static void WriteWeaselCustomYaml(AppSettings settings)
    {
        Directory.CreateDirectory(Paths.RimeUserDir);

        var terracotta = GetTerracottaSchemes();
        var sysAccent = SystemThemeService.BuildSystemAccentSchemes();
        var amber = GetWarmAmberSchemes();

        var (activeLight, activeDark) = settings.WeaselTheme switch
        {
            ThemePresetKind.SystemAccent => (sysAccent.Light.Id, sysAccent.Dark.Id),
            ThemePresetKind.WarmAmber => (amber.Light.Id, amber.Dark.Id),
            _ => (terracotta.Light.Id, terracotta.Dark.Id)
        };

        var isCompact = settings.Density == LayoutDensity.Compact;
        var marginX = isCompact ? 8 : 10;
        var marginY = isCompact ? 4 : 7;
        var spacing = isCompact ? 8 : 11;
        var candSpacing = isCompact ? 14 : 18;
        var hilitePad = isCompact ? 4 : 6;
        var hilitePadX = isCompact ? 7 : 9;
        var hilitePadY = isCompact ? 2 : 4;
        var labelFont = Math.Max(9, settings.FontPoint - 1);
        var roundCorner = Math.Max(2, settings.CornerRadius - 2);

        var sb = new StringBuilder();
        sb.AppendLine("# Generated by Ifboard Desktop");
        sb.AppendLine("patch:");
        if (settings.DisableAppForcedAscii)
        {
            sb.AppendLine("  # 清空默认在终端/编辑器强制英文规则，切换窗口始终维持当前输入状态");
            sb.AppendLine("  app_options:");
            sb.AppendLine("    firefox.exe:");
            sb.AppendLine("      inline_preedit: true");
            sb.AppendLine();
        }
        sb.AppendLine($"  style/color_scheme: {activeLight}");
        sb.AppendLine($"  style/color_scheme_dark: {activeDark}");
        sb.AppendLine("  style/horizontal: true");
        sb.AppendLine("  style/inline_preedit: true");
        sb.AppendLine($"  style/font_point: {settings.FontPoint}");
        sb.AppendLine($"  style/label_font_point: {labelFont}");
        sb.AppendLine($"  style/comment_font_point: {labelFont}");
        sb.AppendLine("  style/layout/border_width: 1");
        sb.AppendLine($"  style/layout/margin_x: {marginX}");
        sb.AppendLine($"  style/layout/margin_y: {marginY}");
        sb.AppendLine($"  style/layout/spacing: {spacing}");
        sb.AppendLine($"  style/layout/candidate_spacing: {candSpacing}");
        sb.AppendLine("  style/layout/hilite_spacing: 4");
        sb.AppendLine($"  style/layout/hilite_padding: {hilitePad}");
        sb.AppendLine($"  style/layout/hilite_padding_x: {hilitePadX}");
        sb.AppendLine($"  style/layout/hilite_padding_y: {hilitePadY}");
        sb.AppendLine($"  style/layout/corner_radius: {settings.CornerRadius}");
        sb.AppendLine($"  style/layout/round_corner: {roundCorner}");
        sb.AppendLine();

        AppendScheme(sb, terracotta.Light, "羊皮暖白底色 + 陶土橙高亮");
        AppendScheme(sb, terracotta.Dark, "暖炭黑底色 + 陶土橙高亮");
        AppendScheme(sb, sysAccent.Light, "自动采集 Windows 系统主体色（浅色自动配色）");
        AppendScheme(sb, sysAccent.Dark, "自动采集 Windows 系统主体色（深色自动配色）");
        AppendScheme(sb, amber.Light, "暖纸琥珀浅色方案");
        AppendScheme(sb, amber.Dark, "暖纸琥珀深色方案");

        File.WriteAllText(Paths.WeaselCustomYaml, sb.ToString(), new UTF8Encoding(false));
    }

    private static void AppendScheme(StringBuilder sb, WeaselColorSchemeSpec s, string comment)
    {
        sb.AppendLine($"  preset_color_schemes/{s.Id}:");
        sb.AppendLine($"    name: \"{s.Name}\"");
        sb.AppendLine("    author: \"Ifboard\"");
        sb.AppendLine("    color_format: rgba");
        sb.AppendLine($"    # {comment}");
        sb.AppendLine($"    back_color: {ToRgbaHex(s.BackColor, 0xFF)}");
        sb.AppendLine($"    border_color: {ToRgbaHex(s.BorderColor, 0xFF)}");
        sb.AppendLine($"    text_color: {ToRgbaHex(s.TextColor, 0xFF)}");
        sb.AppendLine($"    label_color: {ToRgbaHex(s.LabelColor, 0xFF)}");
        sb.AppendLine($"    comment_text_color: {ToRgbaHex(s.CommentColor, 0xFF)}");
        sb.AppendLine($"    candidate_text_color: {ToRgbaHex(s.TextColor, 0xFF)}");
        sb.AppendLine($"    hilited_text_color: {ToRgbaHex(s.HilitedBackColor, 0xFF)}");
        sb.AppendLine($"    hilited_back_color: {ToRgbaHex(s.BackColor, 0x00)}");
        sb.AppendLine("    hilited_mark_color: 0x00000000");
        sb.AppendLine($"    hilited_candidate_back_color: {ToRgbaHex(s.HilitedBackColor, 0xFF)}");
        sb.AppendLine($"    hilited_candidate_border_color: {ToRgbaHex(s.HilitedBackColor, 0xFF)}");
        sb.AppendLine($"    hilited_candidate_text_color: {ToRgbaHex(s.HilitedTextColor, 0xFF)}");
        sb.AppendLine($"    hilited_label_color: {ToRgbaHex(s.HilitedLabelColor, 0xFF)}");
        sb.AppendLine($"    hilited_comment_text_color: {ToRgbaHex(s.HilitedCommentColor, 0xFF)}");
        sb.AppendLine();
    }

    private static string ToRgbaHex(Color c, byte alpha) =>
        $"0x{c.R:X2}{c.G:X2}{c.B:X2}{alpha:X2}";
}
