package com.vizuzik.app

import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Pont du bouton « Rechercher une mise à jour » des réglages web. La fenêtre qui guide l'installation est
 * native ([UpdateDialogs]) ; ici on ne renvoie au web qu'un statut à afficher sous le bouton.
 */
@CapacitorPlugin(name = "AppUpdate")
class AppUpdatePlugin : Plugin() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Résout `{ status: "upToDate" | "available" | "busy" | "disabled" | "error", version?, message? }`. */
    @PluginMethod
    fun checkForUpdate(call: PluginCall) {
        scope.launch {
            val result = JSObject()
            when (val r = AppUpdater.checkNow()) {
                CheckResult.UpToDate -> result.put("status", "upToDate")
                is CheckResult.Available -> {
                    result.put("status", "available")
                    if (r.versionName.isNotEmpty()) result.put("version", r.versionName)
                }
                CheckResult.Busy -> result.put("status", "busy")
                CheckResult.Disabled -> result.put("status", "disabled")
                is CheckResult.Failed -> {
                    result.put("status", "error")
                    result.put("message", updateErrorMessage(context, r.error))
                }
            }
            call.resolve(result)
        }
    }
}
