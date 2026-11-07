package com.xingheyuzhuan.shiguangschedule.tool

import android.content.Context
import com.xingheyuzhuan.shiguangschedule.BuildConfig
import com.xingheyuzhuan.shiguangschedule.R
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** GitHub Releases API 的单个发布。 */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    val body: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<GitHubAsset> = emptyList()
)

/** GitHub Release 附件。 */
@Serializable
data class GitHubAsset(
    val name: String = "",
    val size: Long = 0L,
    @SerialName("browser_download_url") val downloadUrl: String = "",
    val digest: String? = null
)

/**
 * 纯逻辑：从 Release 列表里挑出要提示的那一条，并按 ABI 找到对应 APK。
 *
 * 抽成独立 object 便于单测（不触发网络）。
 */
internal object GitHubReleaseLogic {

    /**
     * 选择版本号最高的已发布 Release。
     *
     * GitHub 源只分两档：
     * - [includePrerelease] = false（Release）时恒过滤 `prerelease`；
     * - [includePrerelease] = true（PreRelease）时允许 `prerelease`。
     *
     * 两种模式都恒过滤 `draft`。
     */
    fun selectLatest(releases: List<GitHubRelease>, includePrerelease: Boolean): GitHubRelease? {
        val candidates = releases.filter { release ->
            !release.draft && (includePrerelease || !release.prerelease)
        }
        if (candidates.isEmpty()) return null

        return candidates.reduce { best, candidate ->
            val cmp = VersionNames.compare(
                VersionNames.normalize(candidate.tagName),
                VersionNames.normalize(best.tagName)
            )
            if (cmp > 0) candidate else best
        }
    }

    /**
     * 匹配当前 flavor + ABI 的 APK。
     *
     * 优先 `app-{flavor}-{arch}-release.apk`，其次宽松匹配同 flavor/arch，
     * 最后允许同 flavor 只有一个 universal apk 时使用；**绝不跨 flavor**。
     */
    fun pickAsset(
        assets: List<GitHubAsset>,
        flavorId: String,
        arch: String
    ): GitHubAsset? {
        val apks = assets.filter { it.downloadUrl.isNotBlank() && it.name.endsWith(".apk", ignoreCase = true) }
        if (apks.isEmpty()) return null

        val exactName = "app-$flavorId-$arch-release.apk"
        apks.firstOrNull { it.name.equals(exactName, ignoreCase = true) }?.let { return it }

        apks.firstOrNull {
            it.name.contains("-$flavorId-", ignoreCase = true) &&
                it.name.contains("-$arch-", ignoreCase = true)
        }?.let { return it }

        // universal 兜底：同一 flavor 下只有唯一一个 APK 才能确定它是给本版本的
        return apks.filter { it.name.contains("-$flavorId-", ignoreCase = true) }.singleOrNull()
    }
}

/**
 * GitHub Releases 更新源（当构建没有配置更新接口时使用）。
 *
 * 已知限制：GitHub API 不提供 `versionCode`，因此只按 `versionName` 比较；
 * dev 渠道的 CI Release 目前是 draft，未认证请求看不到，主要服务 prod 正式版。
 */
class GitHubUpdateSource(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val deviceArch: String,
    private val repo: String = BuildConfig.GITHUB_UPDATE_REPO,
    private val currentVersionName: String = BuildConfig.VERSION_NAME,
    private val flavorId: String = BuildConfig.CURRENT_FLAVOR_ID
) {

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }

    suspend fun checkUpdate(channel: String = "stable"): UpdateStatus = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/$repo/releases?per_page=30"
        val request = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("X-GitHub-Api-Version", "2022-11-28")
            .addHeader("User-Agent", "ClassFlow-Android/$currentVersionName")
            .build()

        try {
            val (statusCode, body) = httpClient.newCall(request).execute().use { response ->
                response.code to response.body.string()
            }

            if (statusCode == 403 || statusCode == 429) {
                return@withContext UpdateStatus.Error(context.getString(R.string.err_github_rate_limited))
            }
            if (statusCode !in 200..299) {
                throw IOException(context.getString(R.string.format_err_server_response, statusCode))
            }

            val releases = json.decodeFromString<List<GitHubRelease>>(body)
            // GitHub 源只有两档：stable = Release（排除 prerelease）；其它渠道 = PreRelease（允许 prerelease）
            val includePrerelease = !channel.equals("stable", ignoreCase = true)
            val release = GitHubReleaseLogic.selectLatest(releases, includePrerelease)
                ?: return@withContext UpdateStatus.Error(context.getString(R.string.err_github_no_release))

            val remoteVersion = VersionNames.normalize(release.tagName)
            if (VersionNames.compare(remoteVersion, currentVersionName) <= 0) {
                return@withContext UpdateStatus.Latest(currentVersionName)
            }

            val asset = GitHubReleaseLogic.pickAsset(release.assets, flavorId, deviceArch)
                ?: return@withContext UpdateStatus.Error(
                    context.getString(R.string.err_github_no_compatible_asset)
                )

            UpdateStatus.Found(
                ReleaseUpdateInfo(
                    latestVersionName = remoteVersion.ifBlank { release.tagName },
                    latestVersionCode = 0L,
                    releaseTitle = release.name.ifBlank { release.tagName },
                    summary = release.body.trim(),
                    releaseUrl = release.htmlUrl,
                    downloadUrl = asset.downloadUrl,
                    downloadAvailable = true,
                    expectedSize = asset.size.takeIf { it > 0L },
                    expectedMd5 = null
                )
            )
        } catch (e: Exception) {
            UpdateStatus.Error(
                context.getString(
                    R.string.format_err_check_update,
                    e.message ?: context.getString(R.string.err_unknown_network_error)
                )
            )
        }
    }
}
