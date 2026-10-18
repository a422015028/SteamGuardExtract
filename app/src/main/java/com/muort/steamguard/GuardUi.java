package com.muort.steamguard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 「Steam 安全密钥已捕获」对话框 —— 静默模式下唯一的密钥呈现入口。
 */
final class GuardUi {

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private GuardUi() {
    }

    // ======================= 密钥对话框 =======================

    static void showSecretDialog(final GuardCore core, final String secret) {
        try {
            MAIN_HANDLER.post(() -> {
                try {
                    Activity act = core.getCurrentActivity();
                    if (act == null) {
                        return;
                    }
                    buildAndShow(act, secret);
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static int dp(Activity act, float v) {
        float density = act.getResources().getDisplayMetrics().density;
        return (int) (v * density);
    }

    private static void buildAndShow(final Activity act, final String secret) {
        // ---------- 卡片容器 ----------
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(act, 24), dp(act, 24), dp(act, 24), dp(act, 18));
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor("#1B1F2A"));
        cardBg.setCornerRadius(dp(act, 20));
        card.setBackground(cardBg);

        // ---------- 标题 ----------
        TextView tvTitle = new TextView(act);
        tvTitle.setText("Steam 安全密钥已捕获");
        tvTitle.setTextColor(Color.parseColor("#66C0F4"));
        tvTitle.setTextSize(20);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setGravity(Gravity.CENTER);
        card.addView(tvTitle);

        // ---------- 副标题 ----------
        TextView tvSub = new TextView(act);
        tvSub.setText("已转换为 Aegis/Stratum 可用的 Base32 格式");
        tvSub.setTextColor(Color.parseColor("#8F98A0"));
        tvSub.setTextSize(13);
        tvSub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(act, 6);
        card.addView(tvSub, subLp);

        // ---------- 密钥文本 ----------
        TextView tvSecret = new TextView(act);
        tvSecret.setText(secret);
        tvSecret.setTextColor(Color.parseColor("#FFFFFF"));
        tvSecret.setTextSize(16);
        tvSecret.setTypeface(Typeface.MONOSPACE);
        tvSecret.setGravity(Gravity.CENTER);
        tvSecret.setTextIsSelectable(true);
        tvSecret.setPadding(dp(act, 16), dp(act, 14), dp(act, 16), dp(act, 14));
        GradientDrawable secretBg = new GradientDrawable();
        secretBg.setColor(Color.parseColor("#2A3F5F"));
        secretBg.setCornerRadius(dp(act, 12));
        tvSecret.setBackground(secretBg);
        LinearLayout.LayoutParams secretLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        secretLp.topMargin = dp(act, 20);
        card.addView(tvSecret, secretLp);

        // ---------- 提示文字 ----------
        TextView tvHint = new TextView(act);
        tvHint.setText("点击下方按钮复制密钥。\n"
                + "复制密钥后打开支持Steam令牌的2FA应用(如Aegis/Stratum)。\n"
                + "粘贴到密钥字段，类型选“Steam”，保存即可。\n"
                + "添加成功后对比Aegis/Stratum与SteamApp内动态码是否一致。");
        tvHint.setTextColor(Color.parseColor("#C7D5E0"));
        tvHint.setTextSize(13);
        tvHint.setLineSpacing(0f, 1.25f);
        tvHint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(act, 16);
        card.addView(tvHint, hintLp);

        // ---------- 按钮行 ----------
        LinearLayout btnRow = new LinearLayout(act);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams btnRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnRowLp.topMargin = dp(act, 24);
        card.addView(btnRow, btnRowLp);

        Button btnCancel = new Button(act);
        btnCancel.setText("关闭");
        btnCancel.setTextColor(Color.parseColor("#8F98A0"));
        btnCancel.setBackgroundColor(Color.TRANSPARENT);
        btnCancel.setAllCaps(false);
        btnCancel.setMinHeight(0);
        btnCancel.setMinWidth(0);
        btnCancel.setPadding(dp(act, 16), dp(act, 8), dp(act, 16), dp(act, 8));
        btnRow.addView(btnCancel);

        Button btnCopy = new Button(act);
        btnCopy.setText("复制密钥");
        btnCopy.setTextColor(Color.WHITE);
        btnCopy.setAllCaps(false);
        btnCopy.setMinHeight(0);
        btnCopy.setMinWidth(0);
        btnCopy.setPadding(dp(act, 20), dp(act, 10), dp(act, 20), dp(act, 10));
        GradientDrawable copyBg = new GradientDrawable();
        copyBg.setColor(Color.parseColor("#1A9FFF"));
        copyBg.setCornerRadius(dp(act, 24));
        btnCopy.setBackground(copyBg);
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        copyLp.leftMargin = dp(act, 12);
        btnRow.addView(btnCopy, copyLp);

        // ---------- 对话框 ----------
        AlertDialog.Builder builder = new AlertDialog.Builder(act);
        builder.setView(card);
        builder.setCancelable(false);
        final AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        btnCancel.setOnClickListener(v -> {
            try {
                dialog.dismiss();
            } catch (Throwable ignored) {
            }
        });
        btnCopy.setOnClickListener(v -> {
            try {
                ClipboardManager cm = (ClipboardManager)
                        act.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData cd = ClipData.newPlainText("SteamGuardSecret", secret);
                cm.setPrimaryClip(cd);
                Toast.makeText(act.getApplicationContext(), "已复制到剪贴板", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            } catch (Throwable ce) {
                Toast.makeText(act.getApplicationContext(), "复制失败: " + ce, Toast.LENGTH_SHORT).show();
            }
        });

        dialog.show();
    }
}
