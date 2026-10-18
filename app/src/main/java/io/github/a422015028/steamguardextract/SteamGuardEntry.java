package io.github.a422015028.steamguardextract;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam;
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * SteamGuardExtract —— LSPosed / Vector 现代 API 102 模块入口（纯 Java）。
 *
 * 静默运行：截获密钥后仅通过弹窗呈现，不写日志、不落盘、不弹 Toast。
 *
 *   hook A: javax.crypto.Cipher.doFinal([B) / doFinal([B,I,I)（after）
 *           -> 截获 SecureStore 中 guard_data 的明文 JSON
 *   hook B: javax.crypto.spec.SecretKeySpec([B,String)（before）
 *           -> 截获 HmacSHA1(shared_secret) / HmacSHA256(identity_secret)
 *
 * 目标进程：Steam Android App（com.valvesoftware.android.steam.community）。
 * javax.crypto.* 由 boot classloader 提供，在 onPackageReady（业务代码尚未
 * 执行）时挂钩即可覆盖冷启动自动读取 guard_data 的场景。
 */
public class SteamGuardEntry extends XposedModule {

    /** Steam Android App 包名。 */
    public static final String TARGET_PACKAGE = "com.valvesoftware.android.steam.community";

    private static final String HOOK_ID_ATTACH = "sg_application_attach";
    private static final String HOOK_ID_ATTACH_BASE = "sg_application_attach_base";

    private final AtomicBoolean attachHooked = new AtomicBoolean(false);
    private final AtomicBoolean cryptoHooked = new AtomicBoolean(false);

    private volatile GuardCore core;
    private volatile ClassLoader appLoader;

    // ======================= 生命周期 =======================

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        // no-op
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        // 尽早挂钩 Application.attach，拿到 Context（弹窗需要）
        hookApplicationAttach(ensureCore());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        appLoader = param.getClassLoader();
        GuardCore c = ensureCore();
        hookCipher(c, param.getClassLoader());
        hookSecretKeySpec(c, param.getClassLoader());
    }

    // ------- API 102 热重载（保存应用 ClassLoader，旧 hook 由框架回收） -------

    @Override
    public boolean onHotReloading(HotReloadingParam param) {
        param.setSavedInstanceState(appLoader);
        return true;
    }

    @Override
    public void onHotReloaded(HotReloadedParam param) {
        try {
            param.getOldHookHandles().forEach(h -> {
                try {
                    h.unhook();
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
        attachHooked.set(false);
        cryptoHooked.set(false);

        Object saved = param.getSavedInstanceState();
        ClassLoader loader = (saved instanceof ClassLoader) ? (ClassLoader) saved : appLoader;
        if (loader != null) {
            GuardCore c = ensureCore();
            hookApplicationAttach(c);
            hookCipher(c, loader);
            hookSecretKeySpec(c, loader);
        }
    }

    private GuardCore ensureCore() {
        GuardCore c = core;
        if (c == null) {
            synchronized (this) {
                c = core;
                if (c == null) {
                    c = new GuardCore();
                    core = c;
                }
            }
        }
        return c;
    }

    // ======================= 兜底：Application.attach =======================

    private void hookApplicationAttach(GuardCore c) {
        if (!attachHooked.compareAndSet(false, true)) {
            return;
        }

        Method attach = findAttachMethod(Application.class);
        if (attach != null) {
            try {
                attach.setAccessible(true);
                hook(attach)
                        .setId(HOOK_ID_ATTACH)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            Object arg = chain.getArg(0);
                            if (arg instanceof Context) {
                                c.onAttach((Context) arg);
                            }
                            return result;
                        });
                return;
            } catch (Throwable ignored) {
            }
        }

        // 最后兜底：ContextWrapper.attachBaseContext(Context)
        try {
            Method attachBase = ContextWrapper.class
                    .getDeclaredMethod("attachBaseContext", Context.class);
            attachBase.setAccessible(true);
            hook(attachBase)
                    .setId(HOOK_ID_ATTACH_BASE)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object self = chain.getThisObject();
                        if (self instanceof ContextWrapper) {
                            Context base = ((ContextWrapper) self).getBaseContext();
                            if (base != null) {
                                c.onAttach(base);
                            }
                        }
                        return result;
                    });
        } catch (Throwable ignored) {
            attachHooked.set(false);
        }
    }

    private Method findAttachMethod(Class<?> clazz) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (!"attach".equals(m.getName()) || Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            Class<?>[] params = m.getParameterTypes();
            if (params.length > 0 && Context.class.isAssignableFrom(params[0])) {
                return m;
            }
        }
        return null;
    }

    // ======================= hook A: Cipher.doFinal =======================

    private void hookCipher(GuardCore c, ClassLoader loader) {
        if (!cryptoHooked.compareAndSet(false, true)) {
            return;
        }
        try {
            Class<?> cipherCls = Class.forName("javax.crypto.Cipher", false, loader);
            int idx = 0;
            for (Method m : cipherCls.getDeclaredMethods()) {
                if (!"doFinal".equals(m.getName())) {
                    continue;
                }
                Class<?>[] pt = m.getParameterTypes();
                boolean target = (pt.length == 1 && pt[0] == byte[].class)
                        || (pt.length == 3 && pt[0] == byte[].class);
                if (!target) {
                    continue;
                }
                m.setAccessible(true);
                hook(m)
                        .setId("sg_doFinal_" + idx)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(c::onDoFinal);
                idx++;
            }
        } catch (Throwable ignored) {
        }
    }

    // ======================= hook B: SecretKeySpec(byte[], String) =======================

    private void hookSecretKeySpec(GuardCore c, ClassLoader loader) {
        try {
            Class<?> sksCls = Class.forName("javax.crypto.spec.SecretKeySpec", false, loader);
            int idx = 0;
            for (Constructor<?> ctor : sksCls.getDeclaredConstructors()) {
                Class<?>[] pt = ctor.getParameterTypes();
                if (pt.length == 2 && pt[0] == byte[].class && pt[1] == String.class) {
                    ctor.setAccessible(true);
                    hook(ctor)
                            .setId("sg_sks_" + idx)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(c::onSecretKeySpec);
                    idx++;
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
