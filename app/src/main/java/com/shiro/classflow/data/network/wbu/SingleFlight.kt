package com.shiro.classflow.data.network.wbu

import kotlinx.coroutines.CompletableDeferred

/**
 * 进程内「单飞」：同一时刻只跑一个任务，后来者直接等同一个结果。
 *
 * 用来解决「多个入口同时做同一件昂贵事」——典型是一卡通换票：打水页、同步引擎、网页容器
 * 各自调一次 [WbuCampusCardClient.ensureValidAccessToken]，三条链路拿着同一个 refresh_token /
 * CASTGC 同时去换票，平台按单活跃会话策略互相顶号，最后一起失败并回「已经在其他设备登录」。
 *
 * 只做**并发**去重，不做缓存：任务一结束（无论成功失败）就清空，下一次调用会重新执行。
 * 这样切换账号、重新登录时不会拿到上一个账号的旧结果。
 *
 * 失败时后来者拿到的是**同一个失败原因**，不会重新跑一遍再失败一次 —— 这也是省掉重复请求的关键。
 * （注意异常实例本身可能被协程库复制一份用于恢复调用栈，所以别按 `===` 去比较。）
 */
internal class SingleFlight<T> {

    private val lock = Any()
    private var inflight: CompletableDeferred<T>? = null

    /**
     * 执行 [block]；若已有同类任务在跑，则**不**执行本次 [block]，直接等它的结果。
     */
    suspend fun run(block: suspend () -> T): T {
        val (pending, isOwner) = synchronized(lock) {
            val running = inflight
            if (running != null) {
                running to false
            } else {
                CompletableDeferred<T>().also { inflight = it } to true
            }
        }

        if (!isOwner) return pending.await()

        return try {
            val result = block()
            pending.complete(result)
            result
        } catch (e: Throwable) {
            pending.completeExceptionally(e)
            throw e
        } finally {
            // 只由发起者清空，避免把别的任务误删
            synchronized(lock) { if (inflight === pending) inflight = null }
        }
    }
}
