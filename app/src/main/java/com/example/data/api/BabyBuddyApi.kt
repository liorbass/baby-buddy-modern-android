package com.example.data.api

import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

data class BabyBuddyChildDto(
    val id: Int,
    @param:Json(name = "first_name") val firstName: String,
    @param:Json(name = "last_name") val lastName: String,
    @param:Json(name = "birth_date") val birthDate: String?,
    val slug: String?
)

data class BabyBuddyFeedingDto(
    val id: Long? = null,
    val child: Int,
    val start: String,
    val end: String? = null,
    val duration: String? = null, // read-only, "HH:MM:SS" (or "D HH:MM:SS")
    val type: String, // "breast milk", "formula", "fortified breast milk", "solid food"
    val method: String, // "bottle", "left breast", "right breast", "both breasts", "parent fed", "self fed"
    val amount: Double? = null,
    val notes: String? = null
)

data class BabyBuddySleepDto(
    val id: Long? = null,
    val child: Int,
    val start: String,
    val end: String? = null,
    val duration: String? = null,
    val nap: Boolean? = null,
    val notes: String? = null
)

data class BabyBuddyChangeDto(
    val id: Long? = null,
    val child: Int,
    val time: String,
    val wet: Boolean = false,
    val solid: Boolean = false,
    val color: String? = null,
    val amount: Double? = null,
    val notes: String? = null
)

data class BabyBuddyTummyTimeDto(
    val id: Long? = null,
    val child: Int,
    val start: String,
    val end: String? = null,
    val duration: String? = null,
    val milestone: String? = null,
    val notes: String? = null
)

data class BabyBuddyTemperatureDto(
    val id: Long? = null,
    val child: Int,
    val temperature: Double? = null,
    val time: String? = null,
    val notes: String? = null
)

/** A row from api/weight/, api/height/ or api/head-circumference/; only that endpoint's value field is set. */
data class BabyBuddyGrowthDto(
    val id: Long? = null,
    val child: Int,
    val weight: Double? = null,
    val height: Double? = null,
    @Json(name = "head_circumference") val headCircumference: Double? = null,
    val date: String? = null,
    val notes: String? = null
)

data class BabyBuddyNoteDto(
    val id: Long? = null,
    val child: Int,
    val note: String? = null,
    val time: String? = null,
    val tags: List<String>? = null
)

/** A Baby Buddy timer; the app keeps a running feed's or sleep's live state in its name (see SharedTimers). */
data class BabyBuddyTimerDto(
    val id: Long? = null,
    val child: Int? = null,
    val name: String? = null,
    val start: String? = null
)

data class BabyBuddyTagDto(
    val slug: String? = null,
    val name: String = "",
    val color: String? = null,
    @param:Json(name = "last_used") val lastUsed: String? = null
)

data class BabyBuddyPumpingDto(
    val id: Long? = null,
    val child: Int,
    val amount: Double? = null,
    val start: String? = null,
    val end: String? = null,
    val duration: String? = null,
    val notes: String? = null
)

data class PaginatedResponse<T>(
    val count: Int,
    val next: String?,
    val previous: String?,
    val results: List<T>
)

interface BabyBuddyApiService {
    // Generic writes used by the offline queue; endpoint is "feedings", "changes" or "tummy-times"
    @POST("api/{endpoint}/")
    suspend fun createRecord(
        @Header("Authorization") authHeader: String,
        @Path("endpoint") endpoint: String,
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<Map<String, Any?>>

    @PATCH("api/{endpoint}/{id}/")
    suspend fun updateRecord(
        @Header("Authorization") authHeader: String,
        @Path("endpoint") endpoint: String,
        @Path("id") id: Long,
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<Map<String, Any?>>

    @DELETE("api/{endpoint}/{id}/")
    suspend fun deleteRecord(
        @Header("Authorization") authHeader: String,
        @Path("endpoint") endpoint: String,
        @Path("id") id: Long
    ): Response<Unit>

    @GET("api/children/")
    suspend fun getChildren(
        @Header("Authorization") authHeader: String
    ): Response<PaginatedResponse<BabyBuddyChildDto>>

    @GET("api/feedings/")
    suspend fun getFeedings(
        @Header("Authorization") authHeader: String,
        @Query("child") child: Int? = null,
        @Query("start_min") since: String? = null,
        @Query("start_max") until: String? = null,
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int? = null
    ): Response<PaginatedResponse<BabyBuddyFeedingDto>>

    @POST("api/feedings/")
    suspend fun postFeeding(
        @Header("Authorization") authHeader: String,
        @Body feeding: BabyBuddyFeedingDto
    ): Response<BabyBuddyFeedingDto>

    @PATCH("api/feedings/{id}/")
    suspend fun patchFeeding(
        @Header("Authorization") authHeader: String,
        @Path("id") id: Long,
        @Body fields: Map<String, @JvmSuppressWildcards Any>
    ): Response<BabyBuddyFeedingDto>

    @DELETE("api/feedings/{id}/")
    suspend fun deleteFeeding(
        @Header("Authorization") authHeader: String,
        @Path("id") id: Long
    ): Response<Unit>

    @GET("api/sleep/")
    suspend fun getSleep(
        @Header("Authorization") authHeader: String,
        @Query("child") child: Int? = null,
        @Query("start_min") since: String? = null,
        @Query("start_max") until: String? = null,
        @Query("end_min") endedSince: String? = null,
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int? = null
    ): Response<PaginatedResponse<BabyBuddySleepDto>>

    @POST("api/sleep/")
    suspend fun postSleep(
        @Header("Authorization") authHeader: String,
        @Body sleep: BabyBuddySleepDto
    ): Response<BabyBuddySleepDto>

    @GET("api/changes/")
    suspend fun getChanges(
        @Header("Authorization") authHeader: String,
        @Query("child") child: Int? = null,
        @Query("date_min") since: String? = null,
        @Query("date_max") until: String? = null,
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int? = null
    ): Response<PaginatedResponse<BabyBuddyChangeDto>>

    @POST("api/tummy-times/")
    suspend fun postTummyTime(
        @Header("Authorization") authHeader: String,
        @Body tummyTime: BabyBuddyTummyTimeDto
    ): Response<BabyBuddyTummyTimeDto>

    @GET("api/temperature/")
    suspend fun getTemperatures(
        @Header("Authorization") authHeader: String,
        @Query("child") child: Int? = null,
        @Query("date_min") since: String? = null,
        @Query("date_max") until: String? = null,
        @Query("limit") limit: Int = 100
    ): Response<PaginatedResponse<BabyBuddyTemperatureDto>>

    /** [endpoint]: "weight", "height" or "head-circumference" (newest first). */
    @GET("api/{endpoint}/")
    suspend fun getGrowth(
        @Header("Authorization") authHeader: String,
        @Path("endpoint") endpoint: String,
        @Query("child") child: Int? = null,
        @Query("date") date: String? = null,
        @Query("limit") limit: Int = 200
    ): Response<PaginatedResponse<BabyBuddyGrowthDto>>

    @GET("api/timers/")
    suspend fun getTimers(
        @Header("Authorization") authHeader: String,
        @Query("child") child: Int? = null,
        @Query("limit") limit: Int = 50
    ): Response<PaginatedResponse<BabyBuddyTimerDto>>

    @GET("api/tags/")
    suspend fun getTags(
        @Header("Authorization") authHeader: String,
        @Query("limit") limit: Int = 100
    ): Response<PaginatedResponse<BabyBuddyTagDto>>

    @GET("api/notes/")
    suspend fun getNotes(
        @Header("Authorization") authHeader: String,
        @Query("child") child: Int? = null,
        @Query("date_min") since: String? = null,
        @Query("date_max") until: String? = null,
        @Query("limit") limit: Int = 100
    ): Response<PaginatedResponse<BabyBuddyNoteDto>>

    @GET("api/pumping/")
    suspend fun getPumping(
        @Header("Authorization") authHeader: String,
        @Query("child") child: Int? = null,
        @Query("start_min") since: String? = null,
        @Query("start_max") until: String? = null,
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int? = null
    ): Response<PaginatedResponse<BabyBuddyPumpingDto>>

    @GET("api/tummy-times/")
    suspend fun getTummyTimes(
        @Header("Authorization") authHeader: String,
        @Query("child") child: Int? = null,
        @Query("start_min") since: String? = null,
        @Query("start_max") until: String? = null,
        @Query("limit") limit: Int = 100
    ): Response<PaginatedResponse<BabyBuddyTummyTimeDto>>

    @POST("api/changes/")
    suspend fun postChange(
        @Header("Authorization") authHeader: String,
        @Body change: BabyBuddyChangeDto
    ): Response<BabyBuddyChangeDto>

    @PATCH("api/changes/{id}/")
    suspend fun patchChange(
        @Header("Authorization") authHeader: String,
        @Path("id") id: Long,
        @Body fields: Map<String, @JvmSuppressWildcards Any>
    ): Response<BabyBuddyChangeDto>

    @DELETE("api/changes/{id}/")
    suspend fun deleteChange(
        @Header("Authorization") authHeader: String,
        @Path("id") id: Long
    ): Response<Unit>
}

object BabyBuddyApiClient {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    /**
     * [cfClientId]/[cfClientSecret] are a Cloudflare Access service token, sent on every request
     * so a server behind Cloudflare Access is reachable without the browser login.
     * With [allowSelfSigned], a certificate the system doesn't trust is accepted only if its SHA-256
     * fingerprint is [pinnedCertSha256]; any other fails with [UntrustedCertificateException].
     */
    fun createService(
        baseUrl: String,
        allowSelfSigned: Boolean = false,
        cfClientId: String = "",
        cfClientSecret: String = "",
        pinnedCertSha256: String = ""
    ): BabyBuddyApiService {
        val sanitizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

        val builder = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)

        if (cfClientId.isNotBlank() && cfClientSecret.isNotBlank()) {
            builder.addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("CF-Access-Client-Id", cfClientId.trim())
                        .header("CF-Access-Client-Secret", cfClientSecret.trim())
                        .build()
                )
            }
        }

        builder
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                }
            )

        if (allowSelfSigned) {
            val trustManager = PinningTrustManager(pinnedCertSha256)
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf(trustManager), null)
            builder.sslSocketFactory(sslContext.socketFactory, trustManager)
            val defaultVerifier = builder.build().hostnameVerifier
            // Self-signed certificates often don't name the host (e.g. a LAN IP), so the pinned one is accepted for any name
            builder.hostnameVerifier { host, session ->
                defaultVerifier.verify(host, session) ||
                    (session.peerCertificates.firstOrNull() as? X509Certificate)?.let { trustManager.isPinned(it) } == true
            }
        }

        val client = builder.build()

        return Retrofit.Builder()
            .baseUrl(sanitizedUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(BabyBuddyApiService::class.java)
    }
}

/** The server's certificate isn't trusted by the system and isn't the pinned one; [fingerprint] is its SHA-256. */
class UntrustedCertificateException(val fingerprint: String, cause: Throwable? = null) : CertificateException("Untrusted certificate $fingerprint", cause)

/**
 * Finds the certificate rejection behind a failed call: OkHttp wraps it in an SSLHandshakeException, and when it
 * tries several addresses (IPv6 then IPv4) it may sit as a suppressed exception under a "connection refused".
 */
fun Throwable.findInChain(match: (Throwable) -> Boolean): Throwable? {
    val seen = mutableSetOf<Throwable>()
    val queue = ArrayDeque(listOf(this))
    while (queue.isNotEmpty()) {
        val t = queue.removeFirst()
        if (!seen.add(t)) continue
        if (match(t)) return t
        t.cause?.let(queue::add)
        queue.addAll(t.suppressed)
    }
    return null
}

/** SHA-256 of the certificate, as colon-separated hex ("AB:CD:…"), the way browsers show it. */
fun certificateFingerprint(cert: X509Certificate): String = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }

/** Trusts what the system trusts, plus the one self-signed certificate the user confirmed (trust on first use). */
private class PinningTrustManager(private val pinnedSha256: String) : X509TrustManager {
    private val system: X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers.filterIsInstance<X509TrustManager>().first()

    fun isPinned(cert: X509Certificate) = pinnedSha256.isNotBlank() && certificateFingerprint(cert).equals(pinnedSha256, ignoreCase = true)

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        try {
            system.checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            val leaf = chain.firstOrNull() ?: throw e
            if (!isPinned(leaf)) throw UntrustedCertificateException(certificateFingerprint(leaf), e)
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
}
