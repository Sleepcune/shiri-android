package com.hubra.app.media

import android.content.Context
import android.graphics.*
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.hubra.app.hubra
import com.hubra.core.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.time.Instant
import java.time.LocalDate
import java.security.MessageDigest
import com.hubra.core.*
import android.os.Build
import android.content.ContentValues
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import coil.imageLoader

class AttachmentManager(private val context: Context) {
    fun file(attachment: Attachment): File {
        require(attachment.relativePath.matches(Regex("attachments/[A-Za-z0-9_-]+\\.jpg"))) { "图片路径非法" }
        val root = context.filesDir.canonicalFile
        return File(root, attachment.relativePath).canonicalFile.also { require(it.path.startsWith(root.path+File.separator)) }
    }
    suspend fun importImage(uri: Uri, purpose: String = "other", date: String? = null): Attachment = withContext(Dispatchers.IO) { MediaAccess.serialized {
        require(purpose in setOf("meal", "course", "background", "other"))
        date?.let(LocalDate::parse)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds=true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,bounds) }
        require(bounds.outWidth>0 && bounds.outHeight>0) { "无法读取这张图片" }
        var sample=1
        while(bounds.outWidth/sample>2048 || bounds.outHeight/sample>8192 || (bounds.outWidth.toLong()/sample)*(bounds.outHeight/sample)>12_000_000)sample*=2
        val options=BitmapFactory.Options().apply { inSampleSize=sample }
        val original=context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,options) } ?: error("无法解码图片")
        val orientation=context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL) } ?: ExifInterface.ORIENTATION_NORMAL
        val matrix=Matrix().apply {
            when(orientation) {
                ExifInterface.ORIENTATION_ROTATE_90->postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180->postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270->postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL->postScale(-1f,1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL->postScale(1f,-1f)
                ExifInterface.ORIENTATION_TRANSPOSE->{postRotate(90f);postScale(-1f,1f)}
                ExifInterface.ORIENTATION_TRANSVERSE->{postRotate(270f);postScale(-1f,1f)}
            }
        }
        val bitmap=Bitmap.createBitmap(original,0,0,original.width,original.height,matrix,true)
        val id=UUID.randomUUID().toString(); var attachment=Attachment(id,"attachments/$id.jpg", purposes=listOf(purpose), importedAt=Instant.now().toString(), date=date)
        val output=file(attachment).apply { parentFile?.mkdirs() }
        try {
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG,90,it)) }
            attachment=attachment.copy(sizeBytes=output.length())
            fun hash(f:File)=f.inputStream().use { stream -> val digest=MessageDigest.getInstance("SHA-256"); val buf=ByteArray(8192); while(true){val n=stream.read(buf);if(n<0)break;digest.update(buf,0,n)};digest.digest().toList() }
            val digest=hash(output)
            val existing=context.hubra.repository.snapshot().attachments.firstOrNull { it.deletedAt==null && !it.deletionPending && file(it).let { f->f.isFile && f.length()==output.length() && hash(f)==digest } }
            if(existing!=null) {
                check(output.delete()); attachment=existing.copy(purposes=(existing.purposes+purpose).distinct(),date=date?:existing.date,importedAt=Instant.now().toString(),sizeBytes=file(existing).length())
                context.hubra.repository.update { state->state.copy(attachments=state.attachments.map { if(it.id==attachment.id)attachment else it }) }
            } else context.hubra.repository.update { it.copy(attachments=it.attachments+attachment) }
        }
        catch(e:Exception){
            withContext(NonCancellable) {
                // A cancellation may be delivered after Room committed; preserve its referenced file.
                if(context.hubra.repository.snapshot().attachments.none{file(it)==output && it.deletedAt==null})output.delete()
            }
            throw e
        }
        finally { bitmap.recycle(); if(bitmap!==original)original.recycle() }
        attachment
    } }

    suspend fun favorite(ids:Set<String>, favorite:Boolean) = MediaAccess.serialized {
        context.hubra.repository.update { s->s.copy(attachments=s.attachments.map { if(it.id in ids && it.deletedAt==null)it.copy(favorite=favorite)else it }) }
    }

    data class DeleteResult(val count:Int,val bytes:Long,val protected:Int,val failed:Int) {
        fun message()="已删除 $count 张，释放 ${bytes/1024} KB" + (if(protected>0)"；$protected 张正在使用或为课表背景，已保留" else "") + (if(failed>0)"；$failed 张失败，可重试" else "")
    }
    suspend fun delete(ids:Set<String>, automatic:Boolean=false):DeleteResult = withContext(Dispatchers.IO) { MediaAccess.serialized {
        val s=context.hubra.repository.snapshot(); val now=Instant.now().toString()
        if(automatic && !s.settings.photoCleanupEnabled)return@serialized DeleteResult(0,0,0,0)
        val all=s.attachments.filter { it.id in ids && it.deletedAt==null }
        if(all.isEmpty())return@serialized DeleteResult(0,0,0,0)
        var allowed=emptyList<Attachment>()
        context.hubra.repository.update { state->
            // Recheck references in the same transaction that marks pending. A newly selected
            // background cannot slip between a stale snapshot and filesystem deletion.
            val protectedIds=MediaAccess.activeIds+state.messages.filter{it.pending}.flatMap{it.attachmentIds}+state.settings.timetableAppearances.values.mapNotNull{it.backgroundAttachmentId}
            val eligible=if(automatic)AttachmentPolicy.expiredCandidates(state,Instant.now(),MediaAccess.activeIds).map{it.id}.toSet()else ids
            allowed=state.attachments.filter { it.id in ids && it.deletedAt==null && it.id !in protectedIds && it.id in eligible }
            val allowedIds=allowed.map{it.id}.toSet()
            state.copy(attachments=state.attachments.map { if(it.id in allowedIds)it.copy(deletionPending=true,deleteError=null)else it })
        }
        var count=0;var bytes=0L;var failed=0
        for(a in allowed) {
            val f=file(a); val size=if(f.exists())f.length()else 0L
            val success=!f.exists()||f.delete()
            // Commit tombstone after unlink; interruption leaves a pending record safely retried.
            context.hubra.repository.update { state->state.copy(attachments=state.attachments.map { if(it.id==a.id) {
                if(success)it.copy(deletedAt=now,deletionPending=false,deleteError=null)else it.copy(deletionPending=true,deleteError="文件删除失败，请重试")
            }else it }) }
            if(success){count++;bytes+=size}else failed++
        }
        if(count>0)context.imageLoader.memoryCache?.clear()
        DeleteResult(count,bytes,all.size-allowed.size,failed)
    } }

    suspend fun retryPendingDeletion():DeleteResult {
        val ids=context.hubra.repository.snapshot().attachments.filter{it.deletionPending && it.deletedAt==null}.map{it.id}.toSet()
        return delete(ids)
    }

    suspend fun cleanup(force:Boolean=false):DeleteResult = cleanupLock.withLock {
        val epoch=context.hubra.repository.requestEpoch
        val s=context.hubra.repository.snapshot();val now=Instant.now()
        if(!s.settings.photoCleanupEnabled)return@withLock DeleteResult(0,0,0,0)
        if(!force && s.settings.lastPhotoCleanupAt?.let { runCatching{Instant.parse(it).plusSeconds(86400)>now}.getOrDefault(false) }==true)return@withLock DeleteResult(0,0,0,0)
        val ids=AttachmentPolicy.expiredCandidates(s,now,MediaAccess.activeIds).map{it.id}.toSet()
        val result=delete(ids,true)
        context.hubra.repository.update { state->if(context.hubra.repository.requestEpoch!=epoch)state else state.copy(settings=state.settings.copy(lastPhotoCleanupAt=now.toString(),lastPhotoCleanupCount=result.count,lastPhotoCleanupBytes=result.bytes)) }
        result
    }

    data class Storage(val photos:Long,val cache:Long,val snapshots:Long)
    fun storage():Storage {
        fun bytes(dir:File)=dir.walkTopDown().filter{it.isFile}.sumOf{it.length()}
        return Storage(bytes(File(context.filesDir,"attachments")),bytes(context.cacheDir),bytes(File(context.filesDir,"snapshots")))
    }
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    suspend fun clearCache():Long=withContext(Dispatchers.IO) { MediaAccess.serialized {
        check(MediaAccess.activeIds.isEmpty()) { "图片正在处理，请稍后清理缓存" }
        val root=context.cacheDir.canonicalFile
        val before=root.walkTopDown().filter{it.isFile}.sumOf{it.length()}
        context.imageLoader.memoryCache?.clear()
        context.imageLoader.diskCache?.clear()
        root.walkBottomUp().filter { it!=root }.forEach { f->require(f.canonicalPath.startsWith(root.path+File.separator));f.delete() }
        // DiskCache.clear may already have removed files before the directory pass.
        (before-root.walkTopDown().filter{it.isFile}.sumOf{it.length()}).coerceAtLeast(0)
    } }
    suspend fun deleteSnapshots(files:List<File>):Long=withContext(Dispatchers.IO) { MediaAccess.serialized {
        val root=File(context.filesDir,"snapshots").canonicalFile;var bytes=0L
        files.forEach { f->require(f.canonicalFile.parentFile==root && f.extension=="hubra");val n=f.length();if(f.delete())bytes+=n }
        bytes
    } }
    suspend fun exportToTree(ids:Set<String>,tree:Uri):Int=withContext(Dispatchers.IO) { MediaAccess.serialized {
        val parent=android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,android.provider.DocumentsContract.getTreeDocumentId(tree))
        val s=context.hubra.repository.snapshot(); var count=0
        s.attachments.filter{it.id in ids && it.deletedAt==null}.forEach { a->
            val name=exportName(s,a)
            val uri=android.provider.DocumentsContract.createDocument(context.contentResolver,parent,"image/jpeg",name) ?: error("无法创建导出图片")
            try { context.contentResolver.openOutputStream(uri)?.use { out->file(a).inputStream().use{it.copyTo(out)} } ?: error("无法导出图片");count++ }
            catch(e:Exception){runCatching{android.provider.DocumentsContract.deleteDocument(context.contentResolver,uri)};throw e}
        };count
    } }
    suspend fun exportToGallery(ids:Set<String>):Int=withContext(Dispatchers.IO) { MediaAccess.serialized {
        require(Build.VERSION.SDK_INT>=29) { "此系统请使用选择目录导出" }
        val s=context.hubra.repository.snapshot();var count=0
        s.attachments.filter{it.id in ids && it.deletedAt==null}.forEach { a->
            val values=ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME,exportName(s,a));put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/拾日/${categoryName(s,a)}/${month(a)}");put(MediaStore.Images.Media.IS_PENDING,1) }
            val uri=context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)?:error("无法创建相册图片")
            try { context.contentResolver.openOutputStream(uri)?.use{out->file(a).inputStream().use{it.copyTo(out)}}?:error("无法导出图片");context.contentResolver.update(uri,ContentValues().apply{put(MediaStore.Images.Media.IS_PENDING,0)},null,null);count++ }
            catch(e:Exception){context.contentResolver.delete(uri,null,null);throw e}
        };count
    } }
    private fun categoryName(s:AppState,a:Attachment)=when {"meal" in AttachmentPolicy.classifications(s,a)->"餐食";"course" in AttachmentPolicy.classifications(s,a)->"课表";"background" in AttachmentPolicy.classifications(s,a)->"背景";else->"附图"}
    private fun localDate(a:Attachment)=a.date?:runCatching{Instant.parse(a.importedAt).atZone(CoreFunctions.zone).toLocalDate().toString()}.getOrDefault(a.importedAt.take(10))
    private fun month(a:Attachment)=localDate(a).take(7).ifBlank{"未标日期"}
    private fun exportName(s:AppState,a:Attachment)="${categoryName(s,a)}_${month(a)}_${localDate(a).ifBlank{"未标日期"}}_${a.id}.jpg"
    companion object { private val cleanupLock=Mutex() }
}
