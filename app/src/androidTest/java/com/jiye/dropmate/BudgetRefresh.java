package com.jiye.dropmate;

import android.app.Instrumentation;
import android.os.Bundle;
import android.content.Context;
import android.content.SharedPreferences;
import com.jiye.dropmate.data.AppKeyStore;

/** Explicit developer action only; never invoked by normal app startup. */
public class BudgetRefresh extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Integer budget = new AppKeyStore(getTargetContext()).budget();
            if (budget == null) throw new IllegalStateException("tmap.budget is not configured");
            SharedPreferences sp = getTargetContext().getSharedPreferences("dropmate_call_ledger", Context.MODE_PRIVATE);
            int sentBefore = sp.getInt("total_sent", 0);
            boolean saved = sp.edit().putInt("remaining", budget).putInt("budget_applied", budget).commit();
            if (!saved || sp.getInt("remaining", -1) != budget || sp.getInt("total_sent", 0) != sentBefore)
                throw new IllegalStateException("Budget refresh verification failed");
            result.putString("budget_refresh", "PASS: remaining=" + budget + "; usage history preserved; no API calls");
            finish(-1, result);
        } catch (Exception e) {
            result.putString("budget_refresh", "FAIL: " + e.getClass().getSimpleName());
            finish(0, result);
        }
    }
}
