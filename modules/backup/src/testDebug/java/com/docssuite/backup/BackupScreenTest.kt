package com.docssuite.backup

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.docssuite.core.DocMeta
import com.docssuite.core.DocType
import com.docssuite.core.DocumentStorage
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
class BackupScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val output: String? = System.getProperty("screenshots")

    private fun show() {
        compose.setContent { MaterialTheme(colorScheme = lightColorScheme()) { BackupScreen(onBack = {}) } }
        compose.waitForIdle()
    }

    @Test
    fun `sans document on ne peut pas sauvegarder mais on peut restaurer`() {
        RuntimeEnvironment.getApplication().getSharedPreferences("docssuite_documents", 0).edit().clear().commit()
        show()
        compose.onNodeWithText("Enregistrer la sauvegarde").assertIsNotEnabled()
        compose.onNodeWithText("Choisir une sauvegarde").assertIsEnabled()
    }

    @Test
    fun `le nombre de documents et l absence de sauvegarde sont affiches`() {
        val context = RuntimeEnvironment.getApplication()
        listOf("docssuite_documents", "docssuite_backup").forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
        DocumentStorage(context).restore(DocMeta("a", "Lettre", DocType.TEXT, 1), """{"text":"x"}""")
        DocumentStorage(context).restore(DocMeta("b", "Budget", DocType.SHEET, 2), """{"cells":{}}""")
        show()
        compose.onNodeWithText("2 document(s)", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Aucune sauvegarde enregistrée depuis cet appareil").assertIsDisplayed()
        compose.onNodeWithText("Enregistrer la sauvegarde").assertIsEnabled()
        output?.let { dir ->
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            File(dir).mkdirs()
            File(dir, "sauvegarde.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
