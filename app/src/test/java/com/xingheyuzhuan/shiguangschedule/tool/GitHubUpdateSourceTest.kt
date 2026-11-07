package com.xingheyuzhuan.shiguangschedule.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubUpdateSourceTest {

    private fun release(
        tag: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: List<GitHubAsset> = emptyList()
    ) = GitHubRelease(
        tagName = tag,
        name = tag,
        htmlUrl = "https://github.com/ClassFlow-Team/ClassFlow/releases/tag/$tag",
        draft = draft,
        prerelease = prerelease,
        assets = assets
    )

    private fun asset(name: String) = GitHubAsset(
        name = name,
        size = 100L,
        downloadUrl = "https://github.com/ClassFlow-Team/ClassFlow/releases/download/v1.1.3/$name"
    )

    @Test
    fun stableSkipsDraftAndPrerelease() {
        val releases = listOf(
            release("v1.2.0", draft = true),
            release("v1.1.5", prerelease = true),
            release("v1.1.3")
        )
        assertEquals("v1.1.3", GitHubReleaseLogic.selectLatest(releases, includePrerelease = false)?.tagName)
    }

    @Test
    fun betaAllowsPrerelease() {
        val releases = listOf(release("v1.1.3"), release("v1.1.4-beta"))
        assertEquals("v1.1.4-beta", GitHubReleaseLogic.selectLatest(releases, includePrerelease = true)?.tagName)
    }

    @Test
    fun picksHighestVersionNotFirstItem() {
        val releases = listOf(release("v1.0.9"), release("v1.1.2"), release("v1.1.1"))
        assertEquals("v1.1.2", GitHubReleaseLogic.selectLatest(releases, includePrerelease = false)?.tagName)
    }

    @Test
    fun noReleaseReturnsNull() {
        assertNull(GitHubReleaseLogic.selectLatest(emptyList(), includePrerelease = false))
        assertNull(GitHubReleaseLogic.selectLatest(listOf(release("v1.1.6", draft = true)), includePrerelease = true))
    }

    @Test
    fun picksExactFlavorAndArchAsset() {
        val assets = listOf(
            asset("app-prod-arm64-v8a-release.apk"),
            asset("app-prod-armeabi-v7a-release.apk"),
            asset("app-prod-x86_64-release.apk")
        )
        assertEquals(
            "app-prod-armeabi-v7a-release.apk",
            GitHubReleaseLogic.pickAsset(assets, flavorId = "prod", arch = "armeabi-v7a")?.name
        )
    }

    @Test
    fun devNeverMatchesProdAsset() {
        val assets = listOf(asset("app-prod-arm64-v8a-release.apk"))
        assertNull(GitHubReleaseLogic.pickAsset(assets, flavorId = "dev", arch = "arm64-v8a"))
    }

    @Test
    fun acceptsSingleUniversalAssetForFlavor() {
        val assets = listOf(asset("app-prod-universal-release.apk"))
        assertNotNull(GitHubReleaseLogic.pickAsset(assets, flavorId = "prod", arch = "arm64-v8a"))
    }

    @Test
    fun noCompatibleAssetReturnsNull() {
        assertNull(GitHubReleaseLogic.pickAsset(emptyList(), flavorId = "prod", arch = "arm64-v8a"))
        assertNull(
            GitHubReleaseLogic.pickAsset(
                listOf(asset("app-prod-arm64-v8a-release.apk"), asset("app-prod-x86_64-release.apk")),
                flavorId = "prod",
                arch = "armeabi-v7a"
            )
        )
    }
}
