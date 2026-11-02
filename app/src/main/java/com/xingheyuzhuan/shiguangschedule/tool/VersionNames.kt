package com.xingheyuzhuan.shiguangschedule.tool

/**
 * 版本号规范化与比较。
 *
 * 更新接口与 GitHub Releases 共用同一套判定：
 * - 去掉首尾空白、前导 `v`、`+` 之后的构建元数据；
 * - 只比较数字段（`1.1.2-dev` 视为 `[1, 1, 2]`），缺失段按 0 处理。
 */
internal object VersionNames {

    fun normalize(version: String): String =
        version.trim().removePrefix("v").substringBefore("+").trim()

    /** 远端版本高于本地返回正数，相等返回 0，低于返回负数。 */
    fun compare(remote: String, local: String): Int {
        val remoteParts = remote.split(Regex("[^0-9]+"))
            .filter { it.isNotBlank() }
            .map { it.toIntOrNull() ?: 0 }
        val localParts = local.split(Regex("[^0-9]+"))
            .filter { it.isNotBlank() }
            .map { it.toIntOrNull() ?: 0 }

        val maxSize = maxOf(remoteParts.size, localParts.size)
        for (index in 0 until maxSize) {
            val left = remoteParts.getOrElse(index) { 0 }
            val right = localParts.getOrElse(index) { 0 }
            if (left != right) return left.compareTo(right)
        }
        return 0
    }
}
