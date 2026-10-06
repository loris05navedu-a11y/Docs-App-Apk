package com.docssuite.collab

import androidx.test.core.app.ApplicationProvider
import com.docssuite.collab.cloud.CollabException
import com.google.firebase.FirebaseApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CollabConfigTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun googleServices(packageName: String = context.packageName, database: Boolean = true) = """
        {
          "project_info": {
            "project_number": "123456789012",
            ${if (database) "\"firebase_url\": \"https://doc-app-suite-default-rtdb.europe-west1.firebasedatabase.app\"," else ""}
            "project_id": "doc-app-suite"
          },
          "client": [{
            "client_info": {
              "mobilesdk_app_id": "1:123456789012:android:0123456789abcdef",
              "android_client_info": { "package_name": "$packageName" }
            },
            "oauth_client": [{ "client_id": "123-android.apps.googleusercontent.com", "client_type": 1 }],
            "api_key": [{ "current_key": "AIzaFAKE" }],
            "services": { "appinvite_service": { "other_platform_oauth_client": [
              { "client_id": "123-web.apps.googleusercontent.com", "client_type": 3 }
            ] } }
          }],
          "configuration_version": "1"
        }
    """.trimIndent()

    @Before
    fun clean() = Collab.reset(context)

    @After
    fun cleanUp() {
        Collab.reset(context)
        FirebaseApp.getApps(context).forEach { it.delete() }
    }

    @Test
    fun `le fichier de Firebase est lu comme le fait la construction`() {
        val config = FirebaseConfig.parse(googleServices(), context.packageName)!!
        assertEquals("1:123456789012:android:0123456789abcdef", config.appId)
        assertEquals("AIzaFAKE", config.apiKey)
        assertEquals("doc-app-suite", config.projectId)
        assertEquals("https://doc-app-suite-default-rtdb.europe-west1.firebasedatabase.app", config.databaseUrl)
        // Le client Web, rangé ailleurs dans le fichier, est retrouvé.
        assertEquals("123-web.apps.googleusercontent.com", config.webClientId)
        assertNull(FirebaseConfig.parse(googleServices(packageName = "com.autre.app"), context.packageName))
        assertNull(FirebaseConfig.parse("{ pas du json", context.packageName))
    }

    @Test
    fun `sans configuration, l'edition partagee dit ce qui manque`() {
        assertEquals(CollabSetup.Missing(CollabSetup.Missing.Part.CONFIG_FILE), Collab.setup(context))
    }

    @Test
    fun `un fichier importe active l'edition partagee`() {
        try {
            Collab.importConfig(context, googleServices(packageName = "com.autre.app"))
            fail()
        } catch (e: CollabException) {
            assertTrue(e.message!!.contains("google-services.json"))
        }
        try {
            Collab.importConfig(context, googleServices(database = false))
            fail()
        } catch (e: CollabException) {
            assertTrue(e.message!!.contains("base temps réel"))
        }
        val setup = Collab.importConfig(context, googleServices())
        assertTrue("$setup", setup is CollabSetup.Ready)
        assertEquals("https://doc-app-suite.web.app/d/abc", (setup as CollabSetup.Ready).docs.link("abc"))
        // Elle le reste au prochain lancement.
        Collab.reset(context, forgetImport = false)
        assertTrue(Collab.setup(context) is CollabSetup.Ready)
    }
}
