package com.seniorenlauncher.util

import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager

object SimUtil {
    /** True when at least one SIM is inserted and ready (calls and SMS over the phone network are possible). */
    fun hasUsableSim(context: Context): Boolean {
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return false
        if (tm.simState == TelephonyManager.SIM_STATE_READY) return true
        @Suppress("DEPRECATION")
        val slots = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tm.activeModemCount else tm.phoneCount
        return (0 until slots).any { tm.getSimState(it) == TelephonyManager.SIM_STATE_READY }
    }
}
