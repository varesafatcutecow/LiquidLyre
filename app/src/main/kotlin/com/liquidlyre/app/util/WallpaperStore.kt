package com.liquidlyre.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * 自定义底图存取（v1.5 修复）。
 *
 * 关键设计：选图时把 content URI 的字节【复制进 App 内部文件】，而不是只存 URI 字符串——
 * 1) 绕开 PickVisualMedia 的临时 URI 权限：重启后 URI 会失效，内部文件永久可读；
 * 2) 提供同步的 [hasCustom] / [loadBitmap]：首帧用「文件真相」决定画哪张图，
 *    不依赖 DataStore 的异步占位值，消除冷启动闪内置极光图的割裂。
 */
object WallpaperStore {
    private const val FILE_NAME = "custom_wallpaper.img"

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /** 同步判断是否存在自定义底图（首帧用，不看 DataStore） */
    fun hasCustom(context: Context): Boolean = file(context).exists()

    /** 把 URI 内容复制到内部文件。返回是否成功。 */
    fun save(context: Context, uri: Uri): Boolean = try {
        val input = context.contentResolver.openInputStream(uri) ?: return false
        input.use { src ->
            file(context).outputStream().use { dst -> src.copyTo(dst, 64 * 1024) }
        }
        true
    } catch (t: Throwable) {
        false
    }

    fun clear(context: Context) {
        file(context).delete()
    }

    /**
     * 从内部文件解码底图，按 maxDim 采样避免 OOM。
     * 返回 null 表示没有自定义底图（调用方应回退到内置极光）。
     */
    fun loadBitmap(context: Context, maxDim: Int = 1920): Bitmap? {
        val f = file(context)
        if (!f.exists()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            val sample = if (longest > maxDim) (longest + maxDim - 1) / maxDim else 1
            BitmapFactory.decodeFile(
                f.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
            )
        } catch (t: Throwable) {
            null
        }
    }
}
