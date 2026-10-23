package com.hubra.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.hubra.app.hubra
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OcrService(private val context: Context) {
    suspend fun recognize(attachmentId: String): String = withContext(Dispatchers.Default) {
        val state=context.hubra.repository.snapshot()
        val attachment=state.attachments.firstOrNull { it.id==attachmentId } ?: error("图片不存在")
        val bitmap=BitmapFactory.decodeFile(AttachmentManager(context).file(attachment).path) ?: error("图片损坏")
        val recognizer=TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val segments=mutableListOf<String>(); var top=0
        try {
            while(top<bitmap.height) {
                val height=minOf(1600,bitmap.height-top)
                val chunk=Bitmap.createBitmap(bitmap,0,top,bitmap.width,height)
                try {
                    val text=suspendCancellableCoroutine<String> { continuation ->
                        recognizer.process(InputImage.fromBitmap(chunk,0)).addOnSuccessListener { if(continuation.isActive)continuation.resume(it.text) }.addOnFailureListener { if(continuation.isActive)continuation.resumeWithException(it) }
                    }
                    segments.add("[截图片段 ${segments.size+1}，纵向位置 $top]\n$text")
                } finally { if(chunk!==bitmap)chunk.recycle() }
                if(top+height>=bitmap.height)break
                top+=height-180
            }
        } finally { recognizer.close();bitmap.recycle() }
        val result=segments.joinToString("\n\n")
        context.hubra.repository.update { s->s.copy(attachments=s.attachments.map { if(it.id==attachmentId)it.copy(sourceText=result)else it }) }
        result
    }
}
