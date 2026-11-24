using System.Text;
using System.Windows;
using System.Windows.Controls;

namespace IfboardWin.Theme;

/// <summary>
/// 设置行：左边标题 + 一句说明，右边放控件；行与行之间用细线分隔（最后一行由 <see cref="SettingSearch"/> 隐藏分隔线）。
/// Keywords 用于设置搜索，写同义词即可，标题和说明会自动参与匹配（含拼音首字母）。
/// </summary>
public class SettingRow : ContentControl
{
    public static readonly DependencyProperty TitleProperty =
        DependencyProperty.Register(nameof(Title), typeof(string), typeof(SettingRow), new PropertyMetadata(""));

    public static readonly DependencyProperty HintProperty =
        DependencyProperty.Register(nameof(Hint), typeof(string), typeof(SettingRow), new PropertyMetadata(""));

    public static readonly DependencyProperty KeywordsProperty =
        DependencyProperty.Register(nameof(Keywords), typeof(string), typeof(SettingRow), new PropertyMetadata(""));

    public static readonly DependencyProperty ShowSeparatorProperty =
        DependencyProperty.Register(nameof(ShowSeparator), typeof(bool), typeof(SettingRow), new PropertyMetadata(true));

    /// <summary>控件放在标题下方（宽控件，如服务商选择、按钮组）</summary>
    public static readonly DependencyProperty StackedProperty =
        DependencyProperty.Register(nameof(Stacked), typeof(bool), typeof(SettingRow), new PropertyMetadata(false));

    public static readonly DependencyProperty IsFlashingProperty =
        DependencyProperty.Register(nameof(IsFlashing), typeof(bool), typeof(SettingRow), new PropertyMetadata(false));

    public string Title { get => (string)GetValue(TitleProperty); set => SetValue(TitleProperty, value); }
    public string Hint { get => (string)GetValue(HintProperty); set => SetValue(HintProperty, value); }
    public string Keywords { get => (string)GetValue(KeywordsProperty); set => SetValue(KeywordsProperty, value); }
    public bool ShowSeparator { get => (bool)GetValue(ShowSeparatorProperty); set => SetValue(ShowSeparatorProperty, value); }
    public bool Stacked { get => (bool)GetValue(StackedProperty); set => SetValue(StackedProperty, value); }
    public bool IsFlashing { get => (bool)GetValue(IsFlashingProperty); set => SetValue(IsFlashingProperty, value); }

    private string? _searchText;

    /// <summary>参与搜索的全部文字：标题、说明、关键词，以及它们的拼音首字母</summary>
    public string SearchText
    {
        get
        {
            if (_searchText != null) return _searchText;
            var raw = $"{Title} {Hint} {Keywords}";
            _searchText = (raw + " " + PinyinInitials.Of(Title) + " " + PinyinInitials.Of(Keywords)).ToLowerInvariant();
            return _searchText;
        }
    }
}

/// <summary>汉字拼音首字母（GB2312 一级常用字按区位范围判断，足够覆盖设置项里的词）</summary>
public static class PinyinInitials
{
    private static readonly Encoding? Gb2312;

    private static readonly (int Code, char Letter)[] Table =
    [
        (0xB0A1, 'a'), (0xB0C5, 'b'), (0xB2C1, 'c'), (0xB4EE, 'd'), (0xB6EA, 'e'), (0xB7A2, 'f'),
        (0xB8C1, 'g'), (0xB9FE, 'h'), (0xBBF7, 'j'), (0xBFA6, 'k'), (0xC0AC, 'l'), (0xC2E8, 'm'),
        (0xC4C3, 'n'), (0xC5B6, 'o'), (0xC5BE, 'p'), (0xC6DA, 'q'), (0xC8BB, 'r'), (0xC8F6, 's'),
        (0xCBFA, 't'), (0xCDDA, 'w'), (0xCEF4, 'x'), (0xD1B9, 'y'), (0xD4D1, 'z')
    ];

    static PinyinInitials()
    {
        try
        {
            Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
            Gb2312 = Encoding.GetEncoding(936);
        }
        catch
        {
            Gb2312 = null;
        }
    }

    public static string Of(string? text)
    {
        if (string.IsNullOrEmpty(text) || Gb2312 == null) return "";
        var sb = new StringBuilder(text.Length);
        var buf = new byte[4];
        foreach (var ch in text)
        {
            if (ch < 0x80)
            {
                if (char.IsLetterOrDigit(ch)) sb.Append(char.ToLowerInvariant(ch));
                continue;
            }
            int n;
            try { n = Gb2312.GetBytes(ch.ToString(), 0, 1, buf, 0); }
            catch { continue; }
            if (n != 2) continue;
            var code = (buf[0] << 8) | buf[1];
            if (code < 0xB0A1 || code > 0xD7F9) continue; // 只处理一级汉字
            var letter = Table[0].Letter;
            foreach (var (c, l) in Table)
            {
                if (code >= c) letter = l;
                else break;
            }
            sb.Append(letter);
        }
        return sb.ToString();
    }
}
