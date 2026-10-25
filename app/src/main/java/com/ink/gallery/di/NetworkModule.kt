package com.ink.gallery.di

import android.content.Context
import com.ink.gallery.BuildConfig
import com.ink.gallery.R
import com.ink.gallery.data.remote.api.AppVersionApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 网络层：只服务于「检查更新」，没有登录态，也没有任何照片数据上行。
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)

        if (BuildConfig.DEBUG) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
            )
        }

        // 服务器 IP 入口用的是自建 CA（MyInk Root CA）签发的证书，公网 CA 不认，
        // 所以只信任内置根证书（res/raw/server.crt）。只走 IP 访问，主机名校验交给 OkHttp 默认逻辑。
        // 证书读取失败时退回系统默认信任链：宁可更新检查失败，也不要让整个 App 起不来。
        runCatching {
            val certificateFactory = CertificateFactory.getInstance("X.509")
            val certificate = context.resources.openRawResource(R.raw.server).use { input ->
                certificateFactory.generateCertificate(input) as X509Certificate
            }

            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("ca", certificate)
            }

            val trustManager = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm()
            ).apply {
                init(keyStore)
            }.trustManagers
                .filterIsInstance<X509TrustManager>()
                .firstOrNull()
                ?: throw IllegalStateException("未找到 X509TrustManager")

            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustManager), null)
            }

            builder.sslSocketFactory(sslContext.socketFactory, trustManager)
        }

        return builder.build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.SERVER_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    @Provides
    @Singleton
    fun provideAppVersionApi(retrofit: Retrofit): AppVersionApi =
        retrofit.create(AppVersionApi::class.java)
}
