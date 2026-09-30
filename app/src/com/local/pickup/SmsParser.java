package com.local.pickup;

import java.util.*;
import java.util.regex.*;

/** Independent, local-only parser; no original code or server dependencies. */
public final class SmsParser {
  public static final String[] CARRIERS = {
    "菜鸟驿站", "顺丰速运", "中通快递", "圆通速递", "申通快递", "韵达快递", "极兔速递", "京东物流", "邮政EMS", "德邦快递", "丰巢", "其他"
  };
  private static final String[] ALIASES = {
    "菜鸟", "顺丰", "中通", "圆通", "申通", "韵达", "极兔", "京东", "邮政", "德邦", "丰巢"
  };

  public static final class Result {
    public final String code, carrier, station;

    Result(String c, String e, String s) {
      code = c;
      carrier = e;
      station = s;
    }
  }

  public static List<Result> parse(String input) {
    List<Result> out = new ArrayList<>();
    if (input == null) return out;
    String s = input.replace('：', ':').replace('－', '-').replace('—', '-');
    if (!Pattern.compile("取件|取货|提货|开柜|快递|包裹|驿站|自提|货物|丰巢").matcher(s).find()) return out;
    if (Pattern.compile("登录|登陆|注册|支付|银行|转账").matcher(s).find()
        && !Pattern.compile("取件码|取货码|提货码|开柜码").matcher(s).find()) return out;
    String carrier = "其他";
    Matcher signature = Pattern.compile("【([^】]+)】|\\[([^\\]]+)\\]").matcher(s);
    String brand =
        signature.find()
            ? (signature.group(1) != null ? signature.group(1) : signature.group(2))
            : s;
    for (int i = 0; i < ALIASES.length; i++)
      if (brand.contains(ALIASES[i])) {
        carrier = CARRIERS[i];
        break;
      }
    if (carrier.equals("其他"))
      for (int i = 1; i < ALIASES.length; i++)
        if (s.contains(ALIASES[i])) {
          carrier = CARRIERS[i];
          break;
        }
    if (carrier.equals("其他") && s.contains("菜鸟")) carrier = CARRIERS[0];
    String station = "";
    Matcher address =
        Pattern.compile(
                "(?:取件地址|取货地址|暂存地址|地址|地点|送达|送至|投递至|已到达|到达|已到|请前往|请到)[:\\s]*(.{2,45}?)(?=[，。；!！\\n"
                    + "]|请凭|凭|取件码|$)")
            .matcher(s);
    if (address.find()) station = address.group(1).trim();
    LinkedHashSet<String> codes = new LinkedHashSet<>();
    Matcher m =
        Pattern.compile(
                "(?:取件码|提取码|提货码|提货号|取货码|开柜码|验证码|密码|凭码|凭号)(?:为|是)?[:\\s]*([A-Za-z0-9][A-Za-z0-9-]{2,11}(?![A-Za-z0-9-])(?:[,，、\\s]+[A-Za-z0-9][A-Za-z0-9-]{2,11}(?![A-Za-z0-9-]))*)")
            .matcher(s);
    while (m.find()) for (String c : m.group(1).split("[,，、\\s]+")) if (valid(c)) codes.add(c);
    if (codes.isEmpty()) {
      m =
          Pattern.compile(
                  "(?:请凭|请报|凭|报|输入)\\s*([A-Za-z0-9][A-Za-z0-9-]{2,11})\\s*(?:取件|领取|来取|到|至|前往)")
              .matcher(s);
      while (m.find()) if (valid(m.group(1))) codes.add(m.group(1));
    }
    for (String c : codes) out.add(new Result(c, carrier, station));
    return out;
  }

  public static boolean valid(String code) {
    return code != null
        && code.matches("[A-Za-z0-9][A-Za-z0-9-]{2,11}")
        && !code.matches("1[3-9]\\d{9}");
  }
}
