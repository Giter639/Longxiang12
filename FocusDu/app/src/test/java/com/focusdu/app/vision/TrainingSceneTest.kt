package com.focusdu.app.vision

import org.junit.Assert.assertEquals
import org.junit.Test

class TrainingSceneTest {

    @Test
    fun resolvesStoredSceneAndFallsBackToDesk() {
        assertEquals(TrainingScene.DESK, TrainingScene.fromStorage("desk"))
        assertEquals(TrainingScene.BED, TrainingScene.fromStorage("bed"))
        assertEquals(TrainingScene.STUDY_ROOM, TrainingScene.fromStorage("study_room"))
        assertEquals(TrainingScene.DESK, TrainingScene.fromStorage(null))
        assertEquals(TrainingScene.DESK, TrainingScene.fromStorage("unknown"))
    }
}
