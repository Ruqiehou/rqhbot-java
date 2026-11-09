package kinkus;

/**
 * 数字转中文工具类
 * 支持整数、负数、小数
 */
public final class NumberToChineseConverter {

    private static final String[] DIGITS = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
    private static final String[] UNITS = {"", "十", "百", "千"};
    private static final String[] BIG_UNITS = {"", "万", "亿", "兆", "京", "垓", "秭", "穰"};

    private NumberToChineseConverter() {}

    public static String convert(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("输入不能为空");
        }

        input = input.trim();

        // 处理科学计数法
        if (input.toLowerCase().contains("e")) {
            try {
                double d = Double.parseDouble(input);
                input = String.format("%.15f", d).replaceAll("0+$", "").replaceAll("\\.$", "");
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("无效的数字格式");
            }
        }

        boolean negative = input.startsWith("-");
        if (negative || input.startsWith("+")) {
            input = input.substring(1);
        }

        StringBuilder result = new StringBuilder();
        if (negative) result.append("负");

        if (input.contains(".")) {
            String[] parts = input.split("\\.", 2);
            result.append(convertInteger(parts[0]));
            result.append("点");
            for (char c : parts[1].toCharArray()) {
                if (Character.isDigit(c)) {
                    result.append(DIGITS[c - '0']);
                }
            }
        } else {
            result.append(convertInteger(input));
        }

        return result.toString();
    }

    private static String convertInteger(String numStr) {
        // 去掉前导零
        numStr = numStr.replaceFirst("^0+", "");
        if (numStr.isEmpty()) return "零";

        StringBuilder sb = new StringBuilder();
        int len = numStr.length();

        // 按4位一组分组
        int groupCount = (len + 3) / 4;
        for (int g = 0; g < groupCount; g++) {
            int start = Math.max(0, len - (g + 1) * 4);
            int end = len - g * 4;
            String group = numStr.substring(start, end);

            String groupResult = convertGroup(group);
            if (!groupResult.isEmpty()) {
                sb.insert(0, groupResult + BIG_UNITS[g]);
            } else if (g > 0 && sb.length() > 0 && !sb.toString().startsWith("零")) {
                sb.insert(0, "零");
            }
        }

        // 特殊处理"一十" -> "十"
        String result = sb.toString();
        if (result.startsWith("一十")) {
            result = result.substring(1);
        }

        return result.isEmpty() ? "零" : result;
    }

    private static String convertGroup(String group) {
        StringBuilder sb = new StringBuilder();
        boolean needZero = false;
        int len = group.length();

        for (int i = 0; i < len; i++) {
            int digit = group.charAt(i) - '0';
            int pos = len - 1 - i;

            if (digit == 0) {
                needZero = true;
            } else {
                if (needZero) {
                    sb.append("零");
                    needZero = false;
                }
                sb.append(DIGITS[digit]).append(UNITS[pos]);
            }
        }

        return sb.toString();
    }
}
