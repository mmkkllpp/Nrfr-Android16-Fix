package com.github.nrfr.manager

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.os.Process
import android.telephony.CarrierConfigManager
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.ShizukuBinderWrapper

private const val ARG_CALLER_PID = "caller_pid"
private const val ARG_SUB_ID = "sub_id"
private const val ARG_CONFIG = "config"
private const val ARG_RESET = "reset"

/** @hide */
private const val INSTR_FLAG_DISABLE_HIDDEN_API_CHECKS = 1 shl 9 // 0x200
/** @hide */
private const val INSTR_FLAG_NO_RESTART = 1 shl 8 // 0x100

object PrivilegedCarrierConfigRunner {
    init {
        HiddenApiBypass.addHiddenApiExemptions("L")
        HiddenApiBypass.addHiddenApiExemptions("I")
    }

    fun overrideConfig(context: Context, subId: Int, bundle: PersistableBundle?) {
        val args = Bundle().apply {
            putInt(ARG_CALLER_PID, Process.myPid())
            putInt(ARG_SUB_ID, subId)
            putBoolean(ARG_RESET, bundle == null)
            if (bundle != null) {
                putParcelable(ARG_CONFIG, bundle)
            }
        }

        val activityManager = getIActivityManager()
        val component = ComponentName(context, PrivilegedCarrierConfigInstrumentation::class.java)
        val flags = INSTR_FLAG_DISABLE_HIDDEN_API_CHECKS or INSTR_FLAG_NO_RESTART

        // startInstrumentation(ComponentName, IProfilerInfo?, int, Bundle?, IInstrumentationWatcher?,
        //                      IUiAutomationConnection?, int, String?)
        val startInstrumentation = activityManager.javaClass.getMethod(
            "startInstrumentation",
            ComponentName::class.java,
            android.os.IBinder::class.java,
            Int::class.javaPrimitiveType,
            Bundle::class.java,
            android.app.IInstrumentationWatcher::class.java,
            Class.forName("android.app.IUiAutomationConnection"),
            Int::class.javaPrimitiveType,
            String::class.java
        )
        val uiAutomationConnection = Class.forName("android.app.UiAutomationConnection").newInstance()
        startInstrumentation.invoke(activityManager, component, null, flags, args, null, uiAutomationConnection, 0, null)
    }

    private fun getIActivityManager(): Any {
        val serviceManagerClass = Class.forName("android.os.ServiceManager")
        val getService = serviceManagerClass.getMethod("getService", String::class.java)
        val binder = getService.invoke(null, Context.ACTIVITY_SERVICE)
        val iActivityManagerClass = Class.forName("android.app.IActivityManager")
        val asInterface = iActivityManagerClass.getMethod("asInterface", android.os.IBinder::class.java)
        val am = asInterface.invoke(null, ShizukuBinderWrapper(binder as android.os.IBinder))
        return am
    }
}

class PrivilegedCarrierConfigInstrumentation : Instrumentation() {
    init {
        HiddenApiBypass.addHiddenApiExemptions("L")
        HiddenApiBypass.addHiddenApiExemptions("I")
    }

    override fun onCreate(arguments: Bundle) {
        super.onCreate(arguments)

        if (arguments.getInt(ARG_CALLER_PID, 0) != Process.myPid()) {
            finish(0, Bundle())
            return
        }

        val activityManager = getIActivityManager()
        val subId = arguments.getInt(ARG_SUB_ID)
        val bundle = getPersistableBundle(arguments)

        try {
            // startDelegateShellPermissionIdentity(int uid, String[]? permissions)
            val delegateMethod = activityManager.javaClass.getMethod(
                "startDelegateShellPermissionIdentity",
                Int::class.javaPrimitiveType,
                Array<String>::class.java
            )
            delegateMethod.invoke(activityManager, Process.myUid(), null)
            overrideCarrierConfig(subId, bundle, persistent = true)
        } catch (e: SecurityException) {
            overrideCarrierConfig(subId, bundle, persistent = false)
        } finally {
            try {
                activityManager.javaClass.getMethod("stopDelegateShellPermissionIdentity").invoke(activityManager)
            } catch (_: Exception) {}
            finish(0, Bundle())
        }
    }

    private fun overrideCarrierConfig(
        subId: Int,
        bundle: PersistableBundle?,
        persistent: Boolean
    ) {
        @Suppress("UNCHECKED_CAST")
        val manager = targetContext.getSystemService(Context.CARRIER_CONFIG_SERVICE) as? CarrierConfigManager
            ?: return
        manager.overrideConfig(subId, bundle, persistent)
    }

    private fun getPersistableBundle(arguments: Bundle): PersistableBundle? {
        if (arguments.getBoolean(ARG_RESET)) {
            return null
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arguments.getParcelable(ARG_CONFIG, PersistableBundle::class.java)
        } else {
            @Suppress("DEPRECATION")
            arguments.getParcelable<PersistableBundle>(ARG_CONFIG)
        }
    }

    private fun getIActivityManager(): Any {
        val serviceManagerClass = Class.forName("android.os.ServiceManager")
        val getService = serviceManagerClass.getMethod("getService", String::class.java)
        val binder = getService.invoke(null, Context.ACTIVITY_SERVICE)
        val iActivityManagerClass = Class.forName("android.app.IActivityManager")
        val asInterface = iActivityManagerClass.getMethod("asInterface", android.os.IBinder::class.java)
        val am = asInterface.invoke(null, ShizukuBinderWrapper(binder as android.os.IBinder))
        return am
    }
}
