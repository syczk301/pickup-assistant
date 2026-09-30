package qa;

import android.app.UiAutomation;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;

/** Unicode text entry through Android's UI automator, used only on the QA emulator. */
public class FillText extends UiAutomatorTestCase {
  public void testFill() throws Exception {
    java.lang.reflect.Method bridgeMethod = getUiDevice().getClass().getDeclaredMethod("getAutomatorBridge");
    bridgeMethod.setAccessible(true);
    Object bridge = bridgeMethod.invoke(getUiDevice());
    UiAutomation automation = null;
    for(Class<?> cls=bridge.getClass();cls!=null;cls=cls.getSuperclass()) {
      for(java.lang.reflect.Field f:cls.getDeclaredFields())if(f.getType()==UiAutomation.class){f.setAccessible(true);automation=(UiAutomation)f.get(bridge);}
    }
    assertNotNull("Automation available", automation);
    AccessibilityNodeInfo field = find(automation.getRootInActiveWindow());
    assertNotNull("Text field must exist", field);
    Bundle args = new Bundle();
    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, getParams().getString("text", ""));
    assertTrue("Text entry must succeed", field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args));
  }
  private AccessibilityNodeInfo find(AccessibilityNodeInfo n) {
    if(n==null)return null;
    if("android.widget.EditText".contentEquals(n.getClassName()))return n;
    for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo result=find(n.getChild(i));if(result!=null)return result;}
    return null;
  }
}
