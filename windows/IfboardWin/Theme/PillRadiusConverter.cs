using System.Globalization;
using System.Windows;
using System.Windows.Data;

namespace IfboardWin.Theme;

/// <summary>
/// 胶囊圆角：圆角半径取控件实际高度的一半。
/// WPF 的圆角超过高度一半时会画成椭圆，不能像 CSS 那样直接写 999。
/// </summary>
public sealed class PillRadiusConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture) =>
        value is double h && h > 0 ? new CornerRadius(h / 2) : new CornerRadius(0);

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) =>
        throw new NotSupportedException();
}
