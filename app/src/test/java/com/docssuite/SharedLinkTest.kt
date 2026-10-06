package com.docssuite

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class SharedLinkTest {

    private fun view(link: String) = Intent(Intent.ACTION_VIEW, Uri.parse(link))

    @Test
    fun `le lien de l'e-mail donne le document`() {
        assertEquals("-NxAbC_123", sharedDocFrom(view("https://doc-app-suite.web.app/d/-NxAbC_123")))
        assertEquals("-NxAbC_123", sharedDocFrom(view("https://doc-app-suite.web.app/d/-NxAbC_123?absente")))
    }

    @Test
    fun `le relais de la page web aussi`() {
        assertEquals("-NxAbC_123", sharedDocFrom(view("docssuite://shared/-NxAbC_123")))
    }

    @Test
    fun `le reste n'est pas un lien d'invitation`() {
        assertNull(sharedDocFrom(view("https://doc-app-suite.web.app/")))
        assertNull(sharedDocFrom(view("https://doc-app-suite.web.app/x/abc")))
        assertNull(sharedDocFrom(view("docssuite://autre/abc")))
        assertNull(sharedDocFrom(view("content://com.android.providers/document/12")))
        assertNull(sharedDocFrom(view("https://doc-app-suite.web.app/d/..%2F..%2Fetc")))
        assertNull(sharedDocFrom(Intent(Intent.ACTION_SEND)))
        assertNull(sharedDocFrom(null))
    }
}
