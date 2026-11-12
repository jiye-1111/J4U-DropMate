package com.jiye.dropmate;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;

/** Explicit live UI check; no API keys or raw responses are logged. */
public class SearchUiCheck extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private void collect(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) collect(node.getChild(i), out);
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            startActivitySync(new android.content.Intent(targetContext(), MainActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(1500);
            List<AccessibilityNodeInfo> nodes = new ArrayList<>();
            collect(getUiAutomation().getRootInActiveWindow(), nodes);
            List<AccessibilityNodeInfo> fields = new ArrayList<>();
            for (AccessibilityNodeInfo node : nodes) {
                if ("android.widget.EditText".contentEquals(node.getClassName())) fields.add(node);
            }
            if (fields.size() < 2) throw new IllegalStateException("Search fields not found");
            Bundle input = new Bundle();
            input.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "신구강");
            if (!fields.get(1).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, input))
                throw new IllegalStateException("Text input rejected");
            long until = SystemClock.uptimeMillis() + 45000;
            boolean found = false;
            while (SystemClock.uptimeMillis() < until) {
                nodes.clear();
                collect(getUiAutomation().getRootInActiveWindow(), nodes);
                boolean name = false, address = false;
                for (AccessibilityNodeInfo node : nodes) {
                    String text = String.valueOf(node.getText());
                    if ("android.widget.TextView".contentEquals(node.getClassName()) &&
                        text.equals("신구강변연가아파트")) name = true;
                    if (text.contains("서울 마포구 토정로 136-11")) address = true;
                }
                if (name && address) { found = true; break; }
                SystemClock.sleep(250);
            }
            result.putString("search_result", found ? "PASS: prefix query rendered apartment name and detailed address" : "FAIL: result not rendered");
            sendStatus(found ? 0 : -1, result);
            // Assertion above checks actual rendered text without persisting API responses.
            finish(found ? -1 : 0, result);
        } catch (Exception e) {
            result.putString("search_result", "FAIL: " + e.getClass().getSimpleName());
            finish(0, result);
        }
    }
    private android.content.Context targetContext() { return getTargetContext(); }
}
