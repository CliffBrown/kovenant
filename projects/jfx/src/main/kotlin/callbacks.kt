/*
 * Copyright (c) 2015 Mark Platvoet<mplatvoet@gmail.com>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * THE SOFTWARE.
 */

package nl.komponents.kovenant.jfx

import nl.komponents.kovenant.*
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference


public fun <V> promiseOnUi(context: Context = Kovenant.context,
                           alwaysSchedule: Boolean = false,
                           body: () -> V): Promise<V, Exception> {
    if (!alwaysSchedule && JFXDispatcher.instance.currentIsUi()) {
        return try {
            Promise.ofSuccess(context = context, value = body())
        } catch(e: Exception) {
            Promise.ofFail(context = context, value = e)
        }
    } else {
        val deferred = deferred<V, Exception>(context)
        JFXDispatcher.instance offer {
            try {
                val result = body()
                deferred.resolve(result)
            } catch(e: Exception) {
                deferred.reject(e)
            }
        }
        return deferred.promise
    }
}


public fun <V, E> Promise<V, E>.successUi(body: (value: V) -> Unit): Promise<V, E> = successUi(false, body)

public fun <V, E> Promise<V, E>.successUi(alwaysSchedule: Boolean, body: (value: V) -> Unit): Promise<V, E> {
    if (!alwaysSchedule && isDone() && JFXDispatcher.instance.currentIsUi()) {
        if (isSuccess()) {
            try {
                body(get())
            } catch(e: Exception) {
                context.callbackContext.errorHandler(e)
            }
        }
    } else {
        val dispatcherContext = CallbackContextCache forContext context
        success(dispatcherContext, body)
    }
    return this
}


public fun <V, E> Promise<V, E>.failUi(body: (error: E) -> Unit): Promise<V, E> = failUi(false, body)

public fun <V, E> Promise<V, E>.failUi(alwaysSchedule: Boolean, body: (error: E) -> Unit): Promise<V, E> {
    if (!alwaysSchedule && isDone() && JFXDispatcher.instance.currentIsUi()) {
        if (isFailure()) {
            try {
                body(getError())
            } catch(e: Exception) {
                context.callbackContext.errorHandler(e)
            }
        }
    } else {
        val dispatcherContext = CallbackContextCache forContext context
        fail(dispatcherContext, body)
    }
    return this
}


public fun <V, E> Promise<V, E>.alwaysUi(body: () -> Unit): Promise<V, E> = alwaysUi(false, body)

public fun <V, E> Promise<V, E>.alwaysUi(alwaysSchedule: Boolean, body: () -> Unit): Promise<V, E> {
    if (!alwaysSchedule && isDone() && JFXDispatcher.instance.currentIsUi()) {
        try {
            body()
        } catch(e: Exception) {
            context.callbackContext.errorHandler(e)
        }
    } else {
        val dispatcherContext = CallbackContextCache forContext context
        always(dispatcherContext, body)
    }
    return this
}


private class DelegatingDispatcherContext(private val base: DispatcherContext, override val dispatcher: Dispatcher) : DispatcherContext {
    override val errorHandler: (Exception) -> Unit
        get() = base.errorHandler
}


/* A lot of assumptions are made during the creation of this class.
 *
 * Assuming that during the lifetime of the JavaFX app the creation of different contexts is sparse. Therefor
 * the number of active contexts will be limited. At most a couple of context die during the lifetime of the
 * JavaFX app, thus the leftovers aren't in the way. Assuming that iteration is faster than using a concurrent
 * HashMap for this particular case since we mostly hit our target on first or second iteration.
 *
 * Not using ConcurrentLinkedQueue since iteration creates a new Iterator instance every single time. Then we would
 * rather just create a new DelegatingDispatcherContext every single call.
 *
 * Also, this cache does not guard against duplicates, and we don't really care either.
 *
 * Cleanup is done in a rather simple yet radical way: If we come across a cleared node we discard the whole cache.
 */
private object CallbackContextCache {
    private val head = AtomicReference<CacheNode>(null)

    fun forContext(context: Context): DispatcherContext {
        iterate {
            ctx, dpCtx ->
            if (ctx == context) return dpCtx
        }
        val dispatcherContext = DelegatingDispatcherContext(context.callbackContext, JFXDispatcher.instance)
        add(context, dispatcherContext)
        return dispatcherContext
    }

    private class CacheNode(context: Context, val dispatcherContext: DispatcherContext) {
        // Use a weak reference, as long as the context lives (e.g. Kovenant.context) we have strong reference.
        val next = AtomicReference<CacheNode>(null)
        private val contextRef = WeakReference(context)

        val context: Context? get() = contextRef.get()
    }

    // Let the Storm Troopers that call themselves Software Craftsmen go berserk
    // over this next piece. So, for Jedi eyes only. ;-)
    //
    // This does not only iterate but also clears the cache is we hit
    // a cleared reference.
    private inline fun iterate(fn: (Context, DispatcherContext) -> Unit) {
        val headNode = head.get()
        if (headNode != null) {
            var node = headNode
            while (true) {
                val ctx = node.context
                if (ctx == null) {
                    // one of the cache items is null
                    // discard the whole cache and rebuild
                    head.set(null)
                    break
                }

                fn(ctx, node.dispatcherContext)

                val next = node.next.get()
                if (next == null) break
                node = next
            }
        }
    }

    fun add(context: Context, dispatcherContext: DispatcherContext) {
        add(CacheNode(context, dispatcherContext))
    }

    private fun add(node: CacheNode) {
        while (true) {
            val headNode = head.get()
            if (headNode == null) {
                if (head.compareAndSet(null, node)) break
            } else {
                do {
                    val tail = tailNode(headNode)
                } while (!tail.next.compareAndSet(null, node))
                break
            }
        }
    }

    private fun tailNode(head: CacheNode): CacheNode {
        var tail = head
        while (true) {
            val next = tail.next.get()
            if (next == null) {
                return tail
            }
            tail = next
        }
    }
}