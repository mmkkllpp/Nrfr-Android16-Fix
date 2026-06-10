package com.github.nrfr.manager

import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.PersistableBundle
import android.telephony.CarrierConfigManager
import android.telephony.TelephonyManager
import com.github.nrfr.model.SimCardInfo
import rikka.shizuku.ShizukuBinderWrapper

object CarrierConfigManager {

    fun getSimCards(context: Context): List<SimCardInfo> {
        val simCards = mutableListOf<SimCardInfo>()

        try {
            val subMgrCls = Class.forName("android.telephony.SubscriptionManager")
            val getSubId = subMgrCls.getMethod("getSubId", Int::class.javaPrimitiveType)
            val subId1 = getSubId.invoke(null, 0) as? IntArray
            val subId2 = getSubId.invoke(null, 1) as? IntArray

            if (subId1 != null && subId1.isNotEmpty()) {
                val config1 = getCurrentConfig(subId1[0])
                simCards.add(SimCardInfo(1, subId1[0], getCarrierNameBySubId(context, subId1[0]), config1))
            }
            if (subId2 != null && subId2.isNotEmpty()) {
                val config2 = getCurrentConfig(subId2[0])
                simCards.add(SimCardInfo(2, subId2[0], getCarrierNameBySubId(context, subId2[0]), config2))
            }
        } catch (_: Exception) {}

        return simCards
    }

    private fun getCurrentConfig(subId: Int): Map<String, String> {
        try {
            val loader = getCarrierConfigLoader()
            val config = loader.javaClass.getMethod("getConfigForSubId", Int::class.javaPrimitiveType, String::class.java)
                .invoke(loader, subId, "com.github.nrfr") as? PersistableBundle ?: return emptyMap()

            val result = mutableMapOf<String, String>()
            config.getString(CarrierConfigManager.KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING)?.let {
                result["国家码"] = it
            }
            if (config.getBoolean(CarrierConfigManager.KEY_CARRIER_NAME_OVERRIDE_BOOL, false)) {
                config.getString(CarrierConfigManager.KEY_CARRIER_NAME_STRING)?.let {
                    result["运营商名称"] = it
                }
            }
            return result
        } catch (_: Exception) {
            return emptyMap()
        }
    }

    private fun getCarrierNameBySubId(context: Context, subId: Int): String {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return ""
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val m = TelephonyManager::class.java.getMethod("getNetworkOperatorName", Int::class.javaPrimitiveType)
                m.invoke(tm, subId) as? String ?: ""
            } else {
                tm.networkOperatorName ?: ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    fun setCarrierConfig(
        context: Context,
        subId: Int,
        countryCode: String?,
        carrierName: String? = null
    ): Boolean {
        val bundle = PersistableBundle()
        if (!countryCode.isNullOrEmpty() && countryCode.length == 2) {
            bundle.putString(CarrierConfigManager.KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING, countryCode.lowercase())
        }
        if (!carrierName.isNullOrEmpty()) {
            bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_NAME_OVERRIDE_BOOL, true)
            bundle.putString(CarrierConfigManager.KEY_CARRIER_NAME_STRING, carrierName)
        }
        return overrideCarrierConfig(context, subId, bundle)
    }

    fun resetCarrierConfig(context: Context, subId: Int): Boolean {
        return overrideCarrierConfig(context, subId, null)
    }

    private fun overrideCarrierConfig(context: Context, subId: Int, bundle: PersistableBundle?): Boolean {
        try {
            val loader = getCarrierConfigLoader()
            loader.javaClass.getMethod("overrideConfig", Int::class.javaPrimitiveType, PersistableBundle::class.java, Boolean::class.javaPrimitiveType)
                .invoke(loader, subId, bundle, true)
            return false
        } catch (e: SecurityException) {
            if (e.message?.contains("cannot be invoked by shell") == true) {
                PrivilegedCarrierConfigRunner.overrideConfig(context, subId, bundle)
                return true
            } else {
                throw e
            }
        } catch (e: Exception) {
            // Fallback to instrumented method for any error on newer Android
            PrivilegedCarrierConfigRunner.overrideConfig(context, subId, bundle)
            return true
        }
    }

    private fun getCarrierConfigLoader(): Any {
        val initCls = Class.forName("android.telephony.TelephonyFrameworkInitializer")
        val getSvcMgr = initCls.getMethod("getTelephonyServiceManager")
        val svcMgr = getSvcMgr.invoke(null)

        val registererCls = Class.forName("android.os.TelephonyServiceManager")
        val getRegisterer = registererCls.getMethod("carrierConfigServiceRegisterer")
        val registerer = getRegisterer.invoke(svcMgr)

        val regCls = Class.forName("android.os.TelephonyServiceManager\$ServiceRegisterer")
        val get = regCls.getMethod("get")
        val binder = get.invoke(registerer) as IBinder

        val loaderCls = Class.forName("com.android.internal.telephony.ICarrierConfigLoader")
        val asInterface = loaderCls.getMethod("asInterface", IBinder::class.java)
        return asInterface.invoke(null, ShizukuBinderWrapper(binder))
    }
}
