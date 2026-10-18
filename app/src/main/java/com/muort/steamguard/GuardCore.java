package com.muort.steamguard;

import android.content.Context;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.Hashtable;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * 截获逻辑主体：静默运行，仅通过弹窗呈现密钥（不写日志、不落盘、不弹 Toast）。
 *
 * 去重表用 Hashtable（回调可能来自多线程，自带同步）。
 */
class GuardCore {

    /** 去重表（Hashtable 自带同步，回调可能来自多线程）。 */
    private final Hashtable<String, Boolean> seen = new Hashtable<>();

    private volatile Context cachedCtx;

    GuardCore() {
    }

    // ======================= Context =======================

    /** 不依赖任何框架注入变量，一律反射自取宿主 Context。 */
    Context getAppCtx() {
        if (cachedCtx != null) {
            return cachedCtx;
        }
        try {
            Method m = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication");
            cachedCtx = (Context) m.invoke(null);
        } catch (Throwable ignored) {
        }
        if (cachedCtx == null) {
            try {
                Method m2 = Class.forName("android.app.AppGlobals")
                        .getMethod("getInitialApplication");
                cachedCtx = (Context) m2.invoke(null);
            } catch (Throwable ignored) {
            }
        }
        return cachedCtx;
    }

    void onAttach(Context ctx) {
        if (ctx != null) {
            cachedCtx = ctx;
        }
    }

    // ======================= 编码工具 =======================

    static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte value : b) {
            int v = value & 0xFF;
            if (v < 16) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }

    /**
     * 标准 RFC4648 Base32（A-Z + 2-7）。Aegis 手动输入密钥一律按 Base32 解码。
     */
    static String base32Encode(byte[] b) {
        final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        int bits = 0;
        int acc = 0;
        StringBuilder sb = new StringBuilder();
        for (byte value : b) {
            acc = ((acc << 8) | (value & 0xFF));
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                sb.append(B32.charAt((acc >> bits) & 31));
            }
        }
        while (sb.length() % 8 != 0) {
            sb.append('=');
        }
        return sb.toString();
    }

    // ======================= Hook 回调 =======================

    /** hook A（after）：解密输出即 guard_data 明文；单参重载的加密输入也检查。 */
    Object onDoFinal(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        try {
            if (result != null && result.getClass() == byte[].class) {
                checkBytes((byte[]) result);
            }
            List<Object> args = chain.getArgs();
            if (args.size() == 1 && result != null) {
                Object inp = args.get(0);
                if (inp != null && inp.getClass() == byte[].class) {
                    checkBytes((byte[]) inp);
                }
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    /** hook B（before）：构造 HMAC 密钥时截获原始字节，然后放行原构造器。 */
    Object onSecretKeySpec(XposedInterface.Chain chain) throws Throwable {
        try {
            List<Object> args = chain.getArgs();
            Object key = args.get(0);
            Object algoObj = args.get(1);
            if (key instanceof byte[] && algoObj != null) {
                String a = algoObj.toString();
                if ("HmacSHA1".equals(a) || "HmacSHA256".equals(a)) {
                    handleHmac((byte[]) key, a);
                }
            }
        } catch (Throwable ignored) {
        }
        return chain.proceed();
    }

    // ======================= hook A 数据处理 =======================

    /** 快速过滤：仅对大小合理且包含 shared_secret 字样的字节数组做解析。 */
    private void checkBytes(byte[] bytesArr) {
        if (bytesArr == null) {
            return;
        }
        int n = bytesArr.length;
        if (n < 8 || n > 8192) {
            return;
        }
        String s = new String(bytesArr, java.nio.charset.StandardCharsets.UTF_8);
        if (s.indexOf("shared_secret") >= 0) {
            markGuardJson(s);
        }
    }

    /** 仅对 guard_data JSON 做去重标记，不输出、不落盘。 */
    private void markGuardJson(String jsonStr) {
        try {
            JSONObject obj = new JSONObject(jsonStr);
            String ss = obj.optString("shared_secret", "");
            String ids = obj.optString("identity_secret", "");
            if ((ss == null || ss.length() == 0) && (ids == null || ids.length() == 0)) {
                return;
            }
            String fp = obj.optString("steamid", "") + "|" + ss;
            if (!seen.containsKey(fp)) {
                seen.put(fp, Boolean.TRUE);
            }
        } catch (Throwable ignored) {
        }
    }

    // ======================= hook B 数据处理 =======================

    private void handleHmac(byte[] key, String a) {
        String hex = bytesToHex(key);
        String id = "k" + a + hex;
        if (seen.containsKey(id)) {
            return;
        }
        seen.put(id, Boolean.TRUE);

        // Aegis 手动输入的密钥按标准 RFC4648 Base32 解码
        try {
            String b32 = base32Encode(key);
            if (!seen.containsKey("b32" + b32)) {
                seen.put("b32" + b32, Boolean.TRUE);
                GuardUi.showSecretDialog(this, b32);
            }
        } catch (Throwable ignored) {
        }
    }

    // ======================= 当前 Activity（弹窗用） =======================

    /** 获取当前 resumed Activity；拿不到时返回 null。 */
    android.app.Activity getCurrentActivity() {
        try {
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            Method m = atCls.getMethod("currentActivityThread");
            Object thread = m.invoke(null);

            // 1) 优先取当前 resumed 的 Activity（跳过已 finishing 的，避免 BadTokenException）
            try {
                java.lang.reflect.Field fResumed = atCls.getDeclaredField("mResumedActivity");
                fResumed.setAccessible(true);
                Object act = fResumed.get(thread);
                if (act instanceof android.app.Activity
                        && !((android.app.Activity) act).isFinishing()) {
                    return (android.app.Activity) act;
                }
            } catch (Throwable ignored) {
            }

            // 2) 兜底：mActivities 是 ArrayMap<IBinder, ActivityClientRecord>
            try {
                java.lang.reflect.Field fActs = atCls.getDeclaredField("mActivities");
                fActs.setAccessible(true);
                Object map = fActs.get(thread);
                if (map instanceof java.util.Map) {
                    for (Object rec : ((java.util.Map<?, ?>) map).values()) {
                        if (rec == null) {
                            continue;
                        }
                        try {
                            java.lang.reflect.Field fAct =
                                    rec.getClass().getDeclaredField("activity");
                            fAct.setAccessible(true);
                            Object a = fAct.get(rec);
                            if (a instanceof android.app.Activity
                                    && !((android.app.Activity) a).isFinishing()) {
                                return (android.app.Activity) a;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
