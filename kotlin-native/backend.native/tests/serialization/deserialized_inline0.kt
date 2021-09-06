/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package serialization.deserialized_inline0

import kotlin.native.internal.*
import kotlin.test.*


fun inline_todo() {
    val frame = runtimeGetCurrentFrame()
    try {
        TODO("OK")
    } catch (e: Throwable) {
        assertEquals(frame, runtimeGetCurrentFrame())
        println(e.message)
    }
}

fun inline_maxof() {
    println(maxOf(10, 17))
    println(maxOf(17, 13))
    println(maxOf(17, 17))
}

fun inline_assertTrue() {
    //assertTrue(true)
}

@Test fun runTest() {
    inline_todo()
    inline_assertTrue()
    inline_maxof()
}

