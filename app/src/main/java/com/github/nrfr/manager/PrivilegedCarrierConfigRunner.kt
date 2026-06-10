package com.github.nrfr.manager

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PersistableBundle
import android.os.Process
import android.telephony.CarrierConfigManager
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.ShizukuBinderWrapper

private const val ARG_CALLER_PID = "caller_pid"
private const val ARG_SUB_ID = "sub_id"
private const val ARG_CONFIG = "config"
private const val ARG_RESET = "reset"
private const val INSTR_FLAG_DISABLE_HIDDEN_API_CHECKS = 1 shl 9 // 0x200 @hide
private const val INSTR_FLAG_NO_RESTART = 1 shl 8 // 0x100 @hide

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

        val am = getIActivityManager()
        val component = ComponentName(context, PrivilegedCarrierConfigInstrumentation::class.java)
        val flags = INSTR_FLAG_DISABLE_HIDDEN_API_CHECKS or INSTR_FLAG_NO_RESTART
        val uiAutomationConnection = Class.forName("android.app.UiAutomationConnection").getDeclaredConstructor().newInstance()
        val iUiAutomationConnection = Class.forName("android.app.IUiAutomationConnection")

        // startInstrumentation(ComponentName, IBinder?, Int, Bundle?, IInstrumentationWatcher?, IUiAutomationConnection?, Int, String?)
        val mStart = am.javaClass.getMethod("startInstrumentation",
            ComponentName::class.java,
            IBinder::class.java,
            Int::class.javaPrimitiveType,
            Bundle::class.java,
            Class.forName("android.app.IInstrumentationWatcher"),
            iUiAutomationConnection,
            Int::class.javaPrimitiveType,
            String::class.java)
        mStart.invoke(am, component, null, flags, args, null, uiAutomationConnection, 0, null)
    }

    private fun getIActivityManager(): Any {
        val svcMgrCls = Class.forName("android.os.ServiceManager")
        val binder = svcMgrCls.getMethod("getService", String::class.java).invoke(null, Context.ACTIVITY_SERVICE) as IBinder
        val iamCls = Class.forName("android.app.IActivityManager")
        return iamCls.getMethod("asInterface", IBinder::class.java).invoke(null, ShizukuBinderWrapper(binder))
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

        val am = getIActivityManager()
        val subId = arguments.getInt(ARG_SUB_ID)
        val bundle = getPersistableBundle(arguments)

        try {
            am.javaClass.getMethod("startDelegateShellPermissionIdentity", Int::class.javaPrimitiveType, Array<String>::class.java)
                .invoke(am, Process.myUid(), null)
            overrideCarrierConfig(subId, bundle, persistent = true)
        } catch (_: SecurityException) {
            overrideCarrierConfig(subId, bundle, persistent = false)
        } finally {
            try { am.javaClass.getMethod("stopDelegateShellPermissionIdentity").invoke(am) } catch (_: Exception) {}
            finish(0, Bundle())
        }
    }

    private fun overrideCarrierConfig(subId: Int, bundle: PersistableBundle?, persistent: Boolean) {
        val mgr = targetContext.getSystemService(Context.CARRIER_CONFIG_SERVICE) as? CarrierConfigManager ?: return
        mgr.overrideConfig(subId, bundle, persistent)
    }

    private fun getPersistableBundle(arguments: Bundle): PersistableBundle? {
        if (arguments.getBoolean(ARG_RESET)) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            arguments.getParcelable(ARG_CONFIG, PersistableBundle::class.java)
        else
            @Suppress("DEPRECATION")
            arguments.getParcelable<PersistableBundle>(ARG_CONFIG)
    }

    private fun getIActivityManager(): Any {
        val svcMgrCls = Class.forName("android.os.ServiceManager")
        val binder = svcMgrCls.getMethod("getService", String::class.java).invoke(null, Context.ACTIVITY_SERVICE) as IBinder
        val iamCls = Class.forName("android.app.IActivityManager")
        return iamCls.getMethod("asInterface", IBinder::class.java).invoke(null, ShizukuBinderWrapper(binder))
    }
}
