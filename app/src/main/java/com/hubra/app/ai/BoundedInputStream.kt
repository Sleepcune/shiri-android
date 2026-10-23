package com.hubra.app.ai

import java.io.FilterInputStream
import java.io.InputStream

/** Bound wire bytes before allocating JSON or an SSE line. */
class BoundedInputStream(input:InputStream, private val limit:Long) : FilterInputStream(input) {
    private var count=0L
    private fun record(n:Int) { if(n>0) { count+=n; require(count<=limit) { "AI 返回内容过长" } } }
    override fun read():Int = super.read().also { if(it>=0)record(1) }
    override fun read(b:ByteArray,off:Int,len:Int):Int = `in`.read(b,off,len).also(::record)
    override fun skip(n:Long):Long = `in`.skip(n).also { count+=it; require(count<=limit) { "AI 返回内容过长" } }
}
