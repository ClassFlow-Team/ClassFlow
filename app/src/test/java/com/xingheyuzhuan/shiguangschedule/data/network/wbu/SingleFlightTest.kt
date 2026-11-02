package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SingleFlight] 的单测。
 *
 * 这个是并发原语，所以测试本身必须并发地跑 —— 但用 `runBlocking` 的单线程事件循环 + [yield]
 * 就能把执行顺序钉死，不需要真的开线程，也就不会 flaky：
 *
 * 1. 先 `async` 一个「发起者」，它在 block 里撞到 `gate.await()` 挂起；
 * 2. [yield] 之后发起者一定已经进入 block 并登记了在途任务；
 * 3. 再 `async` 一个「跟随者」，它必然看到在途任务、转为等待；
 * 4. 放行 `gate`，两者拿到同一个结果，而 block 只执行了一次。
 */
class SingleFlightTest {

    @Test
    fun concurrentCallersShareOneExecution() = runBlocking {
        val flight = SingleFlight<Int>()
        val gate = CompletableDeferred<Unit>()
        var runs = 0

        val owner = async {
            flight.run {
                runs++
                gate.await()
                42
            }
        }
        yield() // 发起者已进入 block，挂在 gate 上

        val follower = async {
            flight.run {
                runs++
                99 // 不该被执行
            }
        }
        yield() // 跟随者已看到在途任务，转为等待

        gate.complete(Unit)
        assertEquals(42, owner.await())
        assertEquals("后来者必须复用同一个结果", 42, follower.await())
        assertEquals("block 只该跑一次", 1, runs)
    }

    @Test
    fun failureIsSharedWithWaitersInsteadOfRetried() = runBlocking {
        val flight = SingleFlight<Int>()
        val gate = CompletableDeferred<Unit>()
        var runs = 0

        val owner = async {
            runCatching {
                flight.run {
                    runs++
                    gate.await()
                    throw IllegalStateException("boom")
                }
            }
        }
        yield()

        val follower = async {
            runCatching {
                flight.run {
                    runs++
                    1
                }
            }
        }
        yield()

        gate.complete(Unit)
        val ownerFailure = owner.await().exceptionOrNull()
        val followerFailure = follower.await().exceptionOrNull()

        // 两个都是同一个失败原因。注意不能断言「同一个实例」：协程库在跨协程传递异常时会复制
        // 一份异常来恢复调用栈（stack trace recovery），所以只比类型和消息。
        assertEquals(IllegalStateException::class, ownerFailure!!::class)
        assertEquals(IllegalStateException::class, followerFailure!!::class)
        assertEquals("boom", followerFailure.message)
        assertEquals("失败也不该让后来者重跑", 1, runs)
    }

    @Test
    fun runsAgainAfterThePreviousOneFinished() = runBlocking {
        val flight = SingleFlight<Int>()

        assertEquals(1, flight.run { 1 })
        // 没有缓存：下一次调用必须真的重新执行（否则切换账号会拿到上一个账号的旧结果）
        assertEquals(2, flight.run { 2 })
    }

    @Test
    fun aFailedRunDoesNotPoisonLaterCalls() = runBlocking {
        val flight = SingleFlight<Int>()
        assertTrue(runCatching { flight.run { throw IllegalStateException("boom") } }.isFailure)
        // 失败后必须清空在途状态，后续调用照常执行
        assertEquals(7, flight.run { 7 })
    }

    @Test
    fun cancellationDoesNotLeaveCallersHanging() = runBlocking {
        val flight = SingleFlight<Int>()
        val gate = CompletableDeferred<Unit>()
        var runs = 0

        val owner = launch {
            flight.run {
                runs++
                gate.await()
                1
            }
        }
        yield()

        val follower = async { runCatching { flight.run { runs++; 2 } } }
        yield()

        // 发起者被取消：等待者不能永远挂在 await 上
        owner.cancel()
        val result = follower.await()
        assertTrue("发起者取消时，跟随者拿到异常而不是挂死", result.isFailure)
        assertEquals(1, runs)
        // 取消后状态也清干净了
        assertEquals(3, flight.run { 3 })
        assertFalse(gate.isCompleted)
    }
}
