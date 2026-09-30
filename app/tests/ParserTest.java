import com.local.pickup.SmsParser;
import java.util.*;

public class ParserTest {
  private static int n = 0;

  static void check(String input, String... expected) {
    List<SmsParser.Result> results = SmsParser.parse(input);
    List<String> codes = new ArrayList<>();
    for (SmsParser.Result r : results) codes.add(r.code);
    if (!codes.equals(Arrays.asList(expected)))
      throw new AssertionError(input + " -> " + codes + ", expected " + Arrays.toString(expected));
    n++;
  }

  public static void main(String[] args) {
    check("【菜鸟驿站】包裹已到达南门驿站，取件码：8-2066，请及时取件。", "8-2066");
    check("【京东物流】您的包裹已投递至JD代收点，取件码：JD1357。", "JD1357");
    check("【圆通速递】包裹已到达代收点，验证码：9876。", "9876");
    check("【丰巢】您的快递已投递到快递柜，密码：8642。", "8642");
    check("【极兔快递】包裹已送达驿站，取件码JT5432，48小时内取件。", "JT5432");
    check("【申通快递】货物已到自提点，凭码ST2468取件。", "ST2468");
    check("请凭取件码 A8B9 至柜台取件，24 小时内有效。", "A8B9");
    check("快递包裹已到，取件码为12-33、12-34，取件码12-33。", "12-33", "12-34");
    check("包裹到了，请报AB1234取件。", "AB1234");
    check("【银行】登录验证码123456，请勿泄露。");
    check("【顺丰快递】登录验证码：123456，请勿泄露。");
    check("快递客服电话13812345678，验证码：13812345678。");
    check("快递到达，取件码：12。");
    check("快递到达，取件码：ABCDEFGHIJKLMNOP。");
    if (!SmsParser.parse("【顺丰速运】包裹已到达南门驿站，取件码AB123。").get(0).station.equals("南门驿站"))
      throw new AssertionError("station");
    n++;
    if (!SmsParser.parse("【顺丰速运】取件码AB123。").get(0).carrier.equals("顺丰速运"))
      throw new AssertionError("carrier");
    n++;
    if (!SmsParser.parse("【圆通速递】包裹到达菜鸟驿站，取件码AB123。").get(0).carrier.equals("圆通速递"))
      throw new AssertionError("signature carrier");
    n++;
    System.out.println("PASS: " + n + " parser cases");
  }
}
