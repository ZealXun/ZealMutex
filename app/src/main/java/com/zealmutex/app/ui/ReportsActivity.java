package com.zealmutex.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.zealmutex.app.data.DataStore;
import com.zealmutex.app.engine.RuleEngine;
import com.zealmutex.app.engine.TrustedTime;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;

/** In-app-only current-week details and retained weekly summaries. */
public final class ReportsActivity extends Activity {
    private LinearLayout content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        build();
    }

    private void build() {
        ScrollView scroll = new ScrollView(this);
        content = Ui.column(this, 20);
        content.setBackgroundColor(Ui.BLACK);
        scroll.addView(content);
        Ui.applyStatusBarInset(scroll);
        setContentView(scroll);

        content.addView(Ui.title(this, "使用统计", 30f));
        content.addView(Ui.text(this,
                "详细记录仅保留到下周一；生成周报后自动清除。周报只能在 ZealMutex 内查看。",
                13f, Ui.MUTED), Ui.matchWrap(this, 8));
        addCurrentWeek();
        addReports();
    }

    private void addCurrentWeek() {
        content.addView(Ui.title(this, "本周明细", 21f), Ui.matchWrap(this, 28));
        JSONArray days = DataStore.get(this).getCurrentWeekDays(TrustedTime.now(this));
        boolean hasData = false;
        for (int i = 0; i < days.length(); i++) {
            JSONObject day = days.optJSONObject(i);
            JSONObject apps = day == null ? null : day.optJSONObject("apps");
            if (apps == null || apps.length() == 0) {
                continue;
            }
            hasData = true;
            LinearLayout card = Ui.card(this);
            card.addView(Ui.title(this, day.optString("date", ""), 17f));
            Iterator<String> keys = apps.keys();
            while (keys.hasNext()) {
                String packageName = keys.next();
                JSONObject stats = apps.optJSONObject(packageName);
                if (stats == null) {
                    continue;
                }
                addUsageStats(card, packageName, stats, false);
            }
            content.addView(card);
        }
        if (!hasData) {
            content.addView(Ui.text(this, "本周还没有使用记录", 14f, Ui.MUTED),
                    Ui.matchWrap(this, 8));
        }
    }

    private void addReports() {
        content.addView(Ui.title(this, "历史周报", 21f), Ui.matchWrap(this, 22));
        JSONArray reports = DataStore.get(this).getReports(TrustedTime.now(this));
        if (reports.length() == 0) {
            content.addView(Ui.text(this, "首份周报将在下周一生成", 14f, Ui.MUTED),
                    Ui.matchWrap(this, 8));
            return;
        }
        for (int i = reports.length() - 1; i >= 0; i--) {
            JSONObject report = reports.optJSONObject(i);
            if (report == null) {
                continue;
            }
            String reportId = report.optString("id");
            LinearLayout card = Ui.card(this);
            card.addView(Ui.title(this,
                    report.optString("start") + " — " + report.optString("end"), 17f));
            JSONObject apps = report.optJSONObject("apps");
            if (apps != null) {
                Iterator<String> keys = apps.keys();
                while (keys.hasNext()) {
                    String packageName = keys.next();
                    JSONObject stats = apps.optJSONObject(packageName);
                    if (stats == null) {
                        continue;
                    }
                    addUsageStats(card, packageName, stats, true);
                }
            }
            Button delete = Ui.secondaryButton(this, "删除这份周报");
            delete.setTextColor(Ui.DANGER);
            delete.setOnClickListener(view -> confirmDelete(reportId));
            card.addView(delete, Ui.matchWrap(this, 14));
            content.addView(card);
        }
    }

    private void confirmDelete(String reportId) {
        new AlertDialog.Builder(this)
                .setTitle("删除周报？")
                .setMessage("删除后无法恢复。")
                .setPositiveButton("删除", (dialog, which) -> {
                    DataStore.get(this).deleteReport(reportId);
                    build();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void addUsageStats(LinearLayout card, String ruleKey,
                               JSONObject stats, boolean weekly) {
        String line = stats.optString("label", ruleKey)
                + (stats.optBoolean("group", false) ? " · 应用组" : "")
                + (weekly ? "\n总计 " : "\n已使用 ")
                + RuleEngine.formatDuration(stats.optLong("usedMs", 0L));
        if (weekly) {
            line += " · 超额 " + RuleEngine.formatDuration(stats.optLong("overMs", 0L));
        }
        line += " · 临时解锁 " + stats.optInt("temporaryUnlockCount", 0) + " 次";
        card.addView(Ui.text(this, line, 14f, Ui.WHITE), Ui.matchWrap(this, 12));

        JSONObject members = stats.optJSONObject("members");
        if (!stats.optBoolean("group", false) || members == null) {
            return;
        }
        JSONArray order = stats.optJSONArray("memberOrder");
        if (order != null) {
            for (int i = 0; i < order.length(); i++) {
                addMemberLine(card, members, order.optString(i));
            }
        }
        Iterator<String> remaining = members.keys();
        while (remaining.hasNext()) {
            String packageName = remaining.next();
            boolean alreadyShown = false;
            if (order != null) {
                for (int i = 0; i < order.length(); i++) {
                    if (packageName.equals(order.optString(i))) {
                        alreadyShown = true;
                        break;
                    }
                }
            }
            if (!alreadyShown) {
                addMemberLine(card, members, packageName);
            }
        }
    }

    private void addMemberLine(LinearLayout card, JSONObject members, String packageName) {
        JSONObject member = members.optJSONObject(packageName);
        if (member == null) {
            return;
        }
        String line = "  · " + member.optString("label", packageName)
                + "  " + RuleEngine.formatDuration(member.optLong("usedMs", 0L));
        card.addView(Ui.text(this, line, 13f, Ui.MUTED), Ui.matchWrap(this, 5));
    }
}
