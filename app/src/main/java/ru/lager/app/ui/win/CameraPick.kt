package ru.lager.app.ui.win

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector

/**
 * Выбор камеры из списка (cameraPickerOverlay в warehouse.html).
 * В настройках хранится идентификатор камеры ("cameraId"); пусто = автоматический выбор.
 * Если выбранной камеры нет, сканер сам вернётся к задней (так уже сделано в местах запуска камеры).
 */
object CameraPick {
    class Cam(val id: String, val label: String)

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun describe(infos: List<CameraInfo>): List<Cam> {
        var back = 0
        var front = 0
        return infos.mapNotNull { info ->
            runCatching {
                val c2 = Camera2CameraInfo.from(info)
                val facing = c2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
                val label = if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    front += 1
                    if (front == 1) "Фронтальная камера" else "Фронтальная камера $front"
                } else {
                    back += 1
                    "Задняя камера $back"
                }
                Cam(c2.cameraId, label + " · ID ${c2.cameraId}")
            }.getOrNull()
        }
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun selector(ctx: Context): CameraSelector {
        val id = SettingsStore.str(ctx, "cameraId")
        if (id.isNotEmpty()) {
            return CameraSelector.Builder()
                .addCameraFilter { infos ->
                    infos.filter { info -> runCatching { Camera2CameraInfo.from(info).cameraId == id }.getOrDefault(false) }
                }
                .build()
        }
        return if (SettingsStore.camera(ctx) == 2) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
    }
}
