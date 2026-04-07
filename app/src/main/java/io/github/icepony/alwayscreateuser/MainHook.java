package io.github.icepony.alwayscreateuser;

import android.os.Bundle;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook extends XposedHelper implements IXposedHookLoadPackage {

    private boolean isModuleEnabled;
    private Class<?> userManagerServiceClass;
    private Class<?> devicePolicyManagerServiceClass;

    // --- 静态内存变量（仅启动时加载一次，这四项修改须重启） ---
    private static boolean sStaticModuleEnabled = true;
    private static boolean sBypassUser = true;
    private static boolean sBypassManaged = true;
    private static boolean sBypassPrivate = true;
    private static boolean sBypassClone = true;

    private static final Set<String> TARGET_RESTRICTIONS = new HashSet<>(Arrays.asList(
            "no_add_user", "no_add_managed_profile", "no_add_private_profile", "no_add_clone_profile"
    ));

    /**
     * 系统启动时调用，用于初始化静态快照
     */
    private void initStaticPreferences() {
        if (prefs != null) {
            prefs.reload();
            sStaticModuleEnabled = prefs.getBoolean("enable_module", true);
            sBypassUser = prefs.getBoolean("bypass_no_add_user", true);
            sBypassManaged = prefs.getBoolean("bypass_no_add_managed_profile", true);
            sBypassPrivate = prefs.getBoolean("bypass_no_add_private_profile", true);
            sBypassClone = prefs.getBoolean("bypass_no_add_clone_profile", true);
        }
    }

    /**
     * 针对高频 API 的极速判断逻辑（无磁盘读取）
     */
    private boolean shouldBypassStatic(String key) {
        if (!sStaticModuleEnabled) return false;
        switch (key) {
            case "no_add_user": return sBypassUser;
            case "no_add_managed_profile": return sBypassManaged;
            case "no_add_private_profile": return sBypassPrivate;
            case "no_add_clone_profile": return sBypassClone;
            default: return false;
        }
    }

    /**
     * 针对低频 API 的动态重载逻辑（支持免重启切换）
     */
    private void reloadPreferences() {
        prefs.reload();
        isModuleEnabled = prefs.getBoolean("enable_module", true);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"android".equals(lpparam.packageName)) return;

        // 1. 初始化启动快照
        initStaticPreferences();

        try {
            userManagerServiceClass = XposedHelpers.findClass("com.android.server.pm.UserManagerService", lpparam.classLoader);
            hookUserManager();
            
            Class<?> utilsClass = XposedHelpers.findClass("com.android.server.pm.UserRestrictionsUtils", lpparam.classLoader);
            hookUserRestrictionsUtils(utilsClass);
        } catch (Throwable ignored) {}

        try {
            devicePolicyManagerServiceClass = XposedHelpers.findClass("com.android.server.devicepolicy.DevicePolicyManagerService", lpparam.classLoader);
            hookDevicePolicyManager();
        } catch (Throwable ignored) {}
    }

    private void hookUserRestrictionsUtils(Class<?> utilsClass) {
        // 高频方法：使用静态变量，极致省电
        XposedBridge.hookAllMethods(utilsClass, "contains", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length > 0 && param.args[0] instanceof String) {
                    String key = (String) param.args[0];
                    if (TARGET_RESTRICTIONS.contains(key) && shouldBypassStatic(key)) {
                        param.setResult(false);
                    }
                }
            }
        });

        // 重启加载方法：使用静态变量
        XposedBridge.hookAllMethods(utilsClass, "readRestrictions", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Bundle bundle = (Bundle) param.getResult();
                if (bundle != null) {
                    for (String key : TARGET_RESTRICTIONS) {
                        if (shouldBypassStatic(key)) bundle.remove(key);
                    }
                }
            }
        });
    }

    private void hookUserManager() {
        // --- 核心限制写入：使用静态变量拦截 ---
        XposedBridge.hookAllMethods(userManagerServiceClass, "setUserRestriction", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length >= 2 && param.args[0] instanceof String) {
                    String key = (String) param.args[0];
                    if (TARGET_RESTRICTIONS.contains(key) && shouldBypassStatic(key)) {
                        param.args[1] = false;
                    }
                }
            }
        });

        // --- 状态位 Hook：保留动态切换逻辑 (低频调用) ---
        checkAndHookWithSwitch(userManagerServiceClass, "isCreationOverrideEnabled", true);
        checkAndHookWithSwitch(userManagerServiceClass, "canAddMoreProfilesToUser", true);
        checkAndHookWithSwitch(userManagerServiceClass, "canAddMoreManagedProfiles", true);
        checkAndHookWithSwitch(userManagerServiceClass, "isUserLimitReached", false);
        checkAndHookWithSwitch(userManagerServiceClass, "isUserLimitReachedLocked", false);
    }

    private void hookDevicePolicyManager() {
        checkAndHookWithSwitch(devicePolicyManagerServiceClass, "enforceCanSetDeviceOwnerLocked", null);
        checkAndHookWithSwitch(devicePolicyManagerServiceClass, "enforceCanSetProfileOwnerLocked", null);

        // DPM 指令下发：使用静态变量
        XposedBridge.hookAllMethods(devicePolicyManagerServiceClass, "setUserRestriction", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length > 0 && param.args[0] instanceof String) {
                    String key = (String) param.args[0];
                    if (TARGET_RESTRICTIONS.contains(key) && shouldBypassStatic(key)) {
                        param.setResult(null);
                    }
                }
            }
        });
    }

    /**
     * 动态 Hook 工具：每次触发都会 reload 磁盘配置
     */
    private void checkAndHookWithSwitch(Class<?> clazz, String methodName, Object result) {
        XposedBridge.hookAllMethods(clazz, methodName, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                reloadPreferences();
                if (!isModuleEnabled) return;
                if (prefs.getBoolean("hook_" + methodName, true)) {
                    param.setResult(result);
                }
            }
        });
    }
}
