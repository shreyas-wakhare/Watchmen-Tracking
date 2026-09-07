package com.watchmen.tracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

class SimCardMonitor(private val context: Context) {

    private var lastSimState: Int = -1
    private var lastNetworkOperator: String? = null
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    data class SimStatus(
        val isPresent: Boolean,
        val simState: String,
        val networkOperator: String?,
        val isRoaming: Boolean,
        val dataConnectionChanged: Boolean
    )

    fun getCurrentStatus(): SimStatus {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w("SimMonitor", "⚠️ READ_PHONE_STATE permission not granted")
            return SimStatus(false, "UNKNOWN", null, false, false)
        }

        val simState = telephonyManager.simState
        val networkOperator = try {
            telephonyManager.networkOperatorName
        } catch (e: Exception) {
            null
        }
        val isRoaming = telephonyManager.isNetworkRoaming

        val simStateString = when (simState) {
            TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
            TelephonyManager.SIM_STATE_READY -> "READY"
            TelephonyManager.SIM_STATE_UNKNOWN -> "UNKNOWN"
            TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN_REQUIRED"
            TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK_REQUIRED"
            else -> "OTHER"
        }

        val dataChanged = networkOperator != lastNetworkOperator

        // Detect SIM removal
        if (lastSimState == TelephonyManager.SIM_STATE_READY && simState == TelephonyManager.SIM_STATE_ABSENT) {
            Log.e("SimMonitor", "🚨 SIM CARD REMOVED!")
            sendSimRemovalAlert()
        }

        // Detect network switching (SIM swap)
        if (lastNetworkOperator != null && networkOperator != null && networkOperator != lastNetworkOperator) {
            Log.e("SimMonitor", "🚨 NETWORK CHANGED: $lastNetworkOperator → $networkOperator (Possible SIM swap)")
            sendNetworkChangeAlert(lastNetworkOperator, networkOperator)
        }

        lastSimState = simState
        lastNetworkOperator = networkOperator

        return SimStatus(
            isPresent = simState != TelephonyManager.SIM_STATE_ABSENT,
            simState = simStateString,
            networkOperator = networkOperator,
            isRoaming = isRoaming,
            dataConnectionChanged = dataChanged
        )
    }

    private fun sendSimRemovalAlert() {
        // TODO: Send to server
        Log.e("SimMonitor", "📡 Sending SIM removal alert to server...")
    }

    private fun sendNetworkChangeAlert(oldNetwork: String?, newNetwork: String?) {
        // TODO: Send to server
        Log.e("SimMonitor", "📡 Sending network change alert: $oldNetwork → $newNetwork")
    }
}
