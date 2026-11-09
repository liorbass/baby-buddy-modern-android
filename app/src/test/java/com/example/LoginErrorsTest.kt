package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.data.api.UntrustedCertificateException
import com.example.data.model.AppText
import com.example.data.repository.loginFailure
import com.example.data.repository.loginRefusedMessage
import com.squareup.moshi.JsonDataException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException

/** What a failed login tells the user: refused token, Cloudflare in the way, a web page, an untrusted certificate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LoginErrorsTest {
    private fun text(id: Int, vararg args: Any) = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id, *args)

    @Test
    fun refusedLoginsNameWhatToCheck() {
        assertEquals(text(R.string.error_check_token, 401), loginRefusedMessage(401, behindCloudflare = false, hasCloudflareToken = false))
        assertEquals(text(R.string.error_blocked_by_cloudflare, 403), loginRefusedMessage(403, behindCloudflare = true, hasCloudflareToken = false))
        assertEquals(text(R.string.error_check_token_and_cloudflare, 403), loginRefusedMessage(403, behindCloudflare = true, hasCloudflareToken = true))
    }

    @Test
    fun failuresAreExplainedOrPassedOn() {
        assertEquals(AppText.get(R.string.error_web_page_not_api), loginFailure(JsonDataException("html"), allowSelfSigned = false).message)

        val untrusted = UntrustedCertificateException("AB:CD")
        assertSame(untrusted, loginFailure(SSLHandshakeException("handshake").apply { initCause(untrusted) }, allowSelfSigned = true))

        val notTrusted = SSLHandshakeException("handshake").apply { initCause(CertPathValidatorException("no path")) }
        assertEquals(AppText.get(R.string.error_certificate_not_trusted), loginFailure(notTrusted, allowSelfSigned = false).message)
        assertNotEquals(AppText.get(R.string.error_certificate_not_trusted), loginFailure(notTrusted, allowSelfSigned = true).message)

        val offline = IOException("unreachable")
        assertSame(offline, loginFailure(offline, allowSelfSigned = false))
    }
}
