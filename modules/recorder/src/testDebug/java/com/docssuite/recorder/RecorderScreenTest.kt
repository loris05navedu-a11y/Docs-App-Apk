package com.docssuite.recorder

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class RecorderScreenTest {

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
    fun `les enregistrements sont listes et la dictee est prete`() {
        val store = RecordingStore(RuntimeEnvironment.getApplication())
        store.list().forEach { store.delete(it.id) }
        val id = store.newId()
        store.audioFile(id).writeBytes(ByteArray(10))
        store.save(Recording(id, "Cours d'histoire", 1_758_700_000_000, 3_125_000, listOf(62_000, 1_830_000), store.audioFile(id)))

        compose.setContent { MaterialTheme(colorScheme = lightColorScheme()) { RecorderScreen(onBack = {}, onCreateDocument = { _, _ -> }) } }
        compose.onNodeWithContentDescription("Enregistrer").assertIsDisplayed()
        compose.onNodeWithText("Cours d'histoire").assertIsDisplayed()
        compose.onNodeWithText("2 repère(s)", substring = true).assertIsDisplayed()
        shoot("1-dictaphone")

        compose.onNodeWithText("Dictée").performClick()
        compose.onNodeWithContentDescription("Dicter").assertIsDisplayed()
        compose.onNodeWithText("Créer un document").assertIsNotEnabled()
        shoot("2-dictee")
    }
}
