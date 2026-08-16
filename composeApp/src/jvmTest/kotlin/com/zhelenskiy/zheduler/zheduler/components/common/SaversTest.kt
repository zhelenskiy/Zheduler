package com.zhelenskiy.zheduler.zheduler.components.common

import androidx.compose.runtime.saveable.SaverScope
import com.zhelenskiy.zheduler.zheduler.TaskStatus
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * These savers are used through `rememberSaveable(stateSaver = …)`, where a restore that answers
 * null is not treated as "could not restore": Compose puts the null straight into the state and
 * hands it back typed as a value, and the screen dies on the first read of it. So a value that
 * cannot be read back has to become something real.
 */
class SaversTest {

    private val scope = SaverScope { true }

    @Test
    fun aValueSurvivesSaveAndRestore() {
        val saver = jsonSaver<TaskStatus> { TaskStatus.Open }
        val blocked = TaskStatus.Blocked(persistentSetOf("TEST-1"), "waiting")

        val saved = assertNotNull(with(saver) { scope.save(blocked) })

        assertEquals(blocked, saver.restore(saved))
    }

    @Test
    fun aValueThisBuildCannotReadRestoresAsTheFallback() {
        val saver = jsonSaver<TaskStatus> { TaskStatus.Done }

        // What a build with a different shape for this type leaves behind.
        assertEquals(TaskStatus.Done, saver.restore(listOf("{\"type\":\"some.future.Status\"}")))
    }

    @Test
    fun anAbsentValueStaysAbsent() {
        val saver = nullableJsonSaver<TaskStatus>()

        val saved = assertNotNull(with(saver) { scope.save(null) })

        assertNull(saver.restore(saved))
    }
}
