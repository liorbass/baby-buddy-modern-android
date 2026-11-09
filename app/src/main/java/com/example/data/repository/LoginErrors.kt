package com.example.data.repository

import com.example.R
import com.example.data.api.UntrustedCertificateException
import com.example.data.api.findInChain
import com.example.data.model.AppText
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import java.security.cert.CertPathValidatorException

/**
 * Why the server refused the login (HTTP 401/403) in words. Behind Cloudflare Access ([behindCloudflare]: the
 * response has a `cf-ray` header) without a service token, Cloudflare is what blocked it.
 */
internal fun loginRefusedMessage(code: Int, behindCloudflare: Boolean, hasCloudflareToken: Boolean): String = when {
    behindCloudflare && !hasCloudflareToken -> AppText.get(R.string.error_blocked_by_cloudflare, code)
    behindCloudflare -> AppText.get(R.string.error_check_token_and_cloudflare, code)
    else -> AppText.get(R.string.error_check_token, code)
}

/**
 * What a login that threw [e] failed with: a web page instead of the API, a certificate to trust (OkHttp wraps the
 * trust manager's verdict in an SSLHandshakeException, so it is looked up in the chain), or one the system doesn't
 * trust while self-signed certificates aren't allowed.
 */
internal fun loginFailure(e: Exception, allowSelfSigned: Boolean): Throwable = when {
    e is JsonDataException || e is JsonEncodingException -> Exception(AppText.get(R.string.error_web_page_not_api))

    else -> e.findInChain { it is UntrustedCertificateException }
        ?: if (!allowSelfSigned && e.findInChain { it is CertPathValidatorException } != null) Exception(AppText.get(R.string.error_certificate_not_trusted)) else e
}
