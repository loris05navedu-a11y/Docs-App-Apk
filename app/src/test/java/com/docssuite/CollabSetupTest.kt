package com.docssuite

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.docssuite.collab.Collab
import com.docssuite.collab.CollabSetup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * La configuration Firebase vient de google-services.json au moment de
 * construire : avec lui, l'édition partagée est prête ; sans lui, elle dit
 * ce qui manque. Les deux cas se vérifient selon la construction en cours.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class CollabSetupTest {

    @Test
    fun `l'edition partagee suit la configuration de la construction`() {
        val app = RuntimeEnvironment.getApplication()
        fun string(name: String): String? =
            app.resources.getIdentifier(name, "string", app.packageName).takeIf { it != 0 }?.let(app::getString)

        val setup = Collab.setup(app)
        val project = string("project_id")
        when {
            project == null -> assertEquals(CollabSetup.Missing(CollabSetup.Missing.Part.CONFIG_FILE), setup)
            string("firebase_database_url") == null -> assertEquals(CollabSetup.Missing(CollabSetup.Missing.Part.DATABASE), setup)
            else -> {
                assertTrue("$setup", setup is CollabSetup.Ready)
                assertEquals("https://$project.web.app/d/abc", (setup as CollabSetup.Ready).docs.link("abc"))
            }
        }
    }
}
