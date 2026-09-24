package com.docssuite.tasks

import android.app.AlarmManager
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class TasksScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val output: String? = System.getProperty("screenshots")

    private fun shoot(name: String) {
        val dir = output ?: return
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `ecrire une tache avec une date pose un rappel puis la cocher`() {
        val app = RuntimeEnvironment.getApplication()
        File(app.filesDir, "tasks.json").delete()
        TaskStore(app).apply {
            add(TaskStore.DEFAULT_LIST, "Acheter du pain")
            add(TaskStore.DEFAULT_LIST, "Payer le loyer", remindAt = System.currentTimeMillis() + 5 * 86_400_000L, repeat = Repeat.MONTHLY)
        }
        compose.setContent { MaterialTheme(colorScheme = lightColorScheme()) { TasksScreen(onBack = {}) } }

        compose.onNodeWithText("Ex. : Appeler Léa demain 18h").performTextInput("Appeler le dentiste demain 18h")
        compose.onNodeWithText("« Appeler le dentiste » · rappel demain 18:00").assertIsDisplayed()
        shoot("1-taches-saisie")
        compose.onNodeWithContentDescription("Ajouter la tâche").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Tâche Appeler le dentiste")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Demain 18:00").assertIsDisplayed()
        val alarm = shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms
        assertTrue("rappel programmé", alarm.isNotEmpty())
        shoot("2-taches-liste")

        compose.onNodeWithContentDescription("Cocher Acheter du pain").performClick()
        compose.onNodeWithText("Terminées (1)").assertIsDisplayed()
        assertEquals(true, TaskStore(app).all().single { it.title == "Acheter du pain" }.done)
    }
}
