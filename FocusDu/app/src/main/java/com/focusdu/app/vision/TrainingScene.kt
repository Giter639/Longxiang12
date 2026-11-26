package com.focusdu.app.vision

import android.content.Context
import com.focusdu.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TrainingScene(
    val storageValue: String,
    val labelRes: Int,
    val hintRes: Int,
) {
    DESK("desk", R.string.p02_scene_desk, R.string.p02_scene_hint_desk),
    BED("bed", R.string.p02_scene_bed, R.string.p02_scene_hint_bed),
    STUDY_ROOM("study_room", R.string.p02_scene_study_room, R.string.p02_scene_hint_study_room);

    companion object {
        fun fromStorage(value: String?): TrainingScene =
            entries.firstOrNull { it.storageValue == value } ?: DESK
    }
}

class ScenePreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _scene = MutableStateFlow(
        TrainingScene.fromStorage(prefs.getString(KEY_SCENE, null))
    )
    val scene: StateFlow<TrainingScene> = _scene.asStateFlow()

    fun setScene(scene: TrainingScene) {
        prefs.edit().putString(KEY_SCENE, scene.storageValue).apply()
        _scene.value = scene
    }

    private companion object {
        const val PREFS = "calibration_preferences"
        const val KEY_SCENE = "training_scene"
    }
}
