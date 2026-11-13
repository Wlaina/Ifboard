/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Gboard-style image entries for the clipboard panel:
 * recent screenshots (MediaStore) and the image currently on the system clipboard.
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.TextUtils
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.webkit.MimeTypeMap
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.utils.DeviceUtil
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.styledColorCompat
import splitties.dimensions.dp
import timber.log.Timber
import java.io.File

data class ClipboardImage(val uri: Uri, val mime: String, val fromClipboard: Boolean, val timestamp: Long)

object ClipboardImages {

    private const val MAX_SCREENSHOTS = 20
    private const val MAX_AGE_SECONDS = 24L * 60 * 60
    private const val PREF_CLEARED_AT = "ifb_clip_img_cleared_at"
    private const val PREF_CLEARED_URI = "ifb_clip_img_cleared_uri"
    private const val PREF_DISMISSED_URIS = "ifb_clip_img_dismissed_uris"

    data class DismissState(
        val clearedAt: Long,
        val clearedUri: String?,
        val dismissedUris: Set<String> = emptySet()
    )

    internal data class DismissImageMeta(
        val uri: String,
        val fromClipboard: Boolean,
        val timestamp: Long
    )

    internal fun shouldFilterImage(
        uriString: String,
        timestamp: Long,
        isFromClipboard: Boolean,
        state: DismissState
    ): Boolean {
        if (!isFromClipboard && timestamp <= state.clearedAt) return true
        if (uriString == state.clearedUri) return true
        return uriString in state.dismissedUris
    }

    internal fun computeDismissOneState(current: DismissState, targetUri: String): DismissState {
        return current.copy(dismissedUris = current.dismissedUris + targetUri)
    }

    internal fun computeDismissAllState(
        current: DismissState,
        displayed: List<DismissImageMeta>,
        nowMs: Long
    ): DismissState {
        val maxTs = maxOf(
            nowMs,
            displayed.filter { !it.fromClipboard }.maxOfOrNull { it.timestamp } ?: 0L
        )
        // 清空全部图片时，非剪贴板截图已被 maxTs 统一屏蔽，仅需在集合中保留当前系统剪贴板图片 URI 以防膨胀
        val clipUris = displayed.filter { it.fromClipboard }.mapTo(LinkedHashSet()) { it.uri }
        current.clearedUri?.takeIf { displayed.any { d -> d.fromClipboard && d.uri == it } }
            ?.let { clipUris.add(it) }
        return DismissState(
            clearedAt = maxTs,
            clearedUri = clipUris.firstOrNull(),
            dismissedUris = clipUris
        )
    }

    private fun readDismissState(sp: SharedPreferences): DismissState {
        val clearedAt = sp.getLong(PREF_CLEARED_AT, 0L)
        val clearedUri = sp.getString(PREF_CLEARED_URI, null)
        // 复制 StringSet，避免 SharedPreferences 返回可变引用导致后续修改异常
        val dismissedUris = sp.getStringSet(PREF_DISMISSED_URIS, null)?.toSet().orEmpty()
        return DismissState(clearedAt, clearedUri, dismissedUris)
    }

    private fun writeDismissState(sp: SharedPreferences, state: DismissState) {
        sp.edit()
            .putLong(PREF_CLEARED_AT, state.clearedAt)
            .apply {
                if (state.clearedUri != null) putString(PREF_CLEARED_URI, state.clearedUri)
                else remove(PREF_CLEARED_URI)
                if (state.dismissedUris.isNotEmpty()) putStringSet(PREF_DISMISSED_URIS, state.dismissedUris.toSet())
                else remove(PREF_DISMISSED_URIS)
            }
            .apply()
    }

    fun dismissOne(ctx: Context, target: ClipboardImage): DismissState {
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        val prev = readDismissState(sp)
        val next = computeDismissOneState(prev, target.uri.toString())
        writeDismissState(sp, next)
        return prev
    }

    fun dismissAll(ctx: Context, displayed: List<ClipboardImage>): DismissState {
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        val prev = readDismissState(sp)
        val meta = displayed.map { DismissImageMeta(it.uri.toString(), it.fromClipboard, it.timestamp) }
        val next = computeDismissAllState(prev, meta, System.currentTimeMillis())
        writeDismissState(sp, next)
        return prev
    }

    fun restoreDismiss(ctx: Context, state: DismissState) {
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        writeDismissState(sp, state)
    }

    fun hasMediaPermission(ctx: Context): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
    }

    /** must be called off the main thread */
    fun load(ctx: Context): List<ClipboardImage> {
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        val dismissState = readDismissState(sp)
        val result = mutableListOf<ClipboardImage>()
        val seenUris = HashSet<String>()
        // 1. image on system clipboard (仅收录当前可读的图片 URI，避免跨端协同剪贴板等不可读 URI 渲染为空白灰块)
        runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = cm.primaryClip
            if (clip != null) {
                val now = System.currentTimeMillis()
                for (i in 0 until clip.itemCount) {
                    val uri = clip.getItemAt(i).uri ?: continue
                    val uriStr = uri.toString()
                    if (shouldFilterImage(uriStr, now, isFromClipboard = true, dismissState)) continue
                    if (!seenUris.add(uriStr)) continue
                    val mime = ctx.contentResolver.getType(uri)
                        ?: clip.description.let { d -> (0 until d.mimeTypeCount).map { d.getMimeType(it) } }
                            .firstOrNull { it.startsWith("image/") }
                        ?: continue
                    if (!mime.startsWith("image/")) continue
                    val readable = runCatching {
                        ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
                    }.getOrDefault(false)
                    if (readable) {
                        result.add(ClipboardImage(uri, mime, true, now))
                    }
                }
            }
        }.onFailure { Timber.w(it, "Failed to read clipboard image") }
        // 2. recent screenshots
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasMediaPermission(ctx)) runCatching {
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.DATE_ADDED
            )
            val since = System.currentTimeMillis() / 1000 - MAX_AGE_SECONDS
            val selection =
                "(${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?) AND ${MediaStore.Images.Media.DATE_ADDED} >= ?"
            val args = arrayOf("%Screenshots%", "%截屏%", since.toString())
            ctx.contentResolver.query(
                collection, projection, selection, args,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                var n = 0
                while (c.moveToNext() && n < MAX_SCREENSHOTS) {
                    val ts = c.getLong(dateCol) * 1000
                    val uri = Uri.withAppendedPath(collection, c.getLong(idCol).toString())
                    val uriStr = uri.toString()
                    if (shouldFilterImage(uriStr, ts, isFromClipboard = false, dismissState)) continue
                    if (!seenUris.add(uriStr)) continue
                    result.add(ClipboardImage(uri, c.getString(mimeCol) ?: "image/png", false, ts))
                    n++
                }
            }
        }.onFailure { Timber.w(it, "Failed to query screenshots") }
        return result
    }

    /** Copy [src] into our own FileProvider so the target app can be granted access. */
    private fun shareableUri(ctx: Context, src: Uri, mime: String): Uri {
        val dir = File(ctx.cacheDir, "clip_images").apply { mkdirs() }
        val expire = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        dir.listFiles()?.forEach { if (it.lastModified() < expire) it.delete() }
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "png"
        val file = File(dir, "image_${System.currentTimeMillis()}.$ext")
        ctx.contentResolver.openInputStream(src)!!.use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.clipimage", file)
    }

    /**
     * Send image to the current editor like Gboard does (commitContent);
     * if the editor does not accept images, put it on the system clipboard instead.
     */
    fun send(service: FcitxInputMethodService, scope: CoroutineScope, image: ClipboardImage, onDone: () -> Unit) {
        scope.launch {
            val uri = runCatching {
                withContext(Dispatchers.IO) { shareableUri(service, image.uri, image.mime) }
            }.getOrElse {
                Timber.w(it, "Failed to copy image")
                Toast.makeText(service, "无法读取这张图片", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val info = service.currentInputEditorInfo
            val ic = service.currentInputConnection
            val accepted = info != null && EditorInfoCompat.getContentMimeTypes(info)
                .any { ClipDescription.compareMimeTypes(image.mime, it) }
            val committed = accepted && ic != null && InputConnectionCompat.commitContent(
                ic, info,
                InputContentInfoCompat(uri, ClipDescription("image", arrayOf(image.mime)), null),
                InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null
            )
            if (!committed) {
                val cm = service.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(ClipData.newUri(service.contentResolver, "image", uri))
                Toast.makeText(service, "此处不支持直接发送图片，已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
            onDone()
        }
    }
}

class ClipboardImageAdapter(
    private val theme: Theme,
    private val radius: Float,
    private val scope: CoroutineScope,
    private val onClick: (ClipboardImage) -> Unit,
    private val onDelete: (ClipboardImage) -> Unit
) : RecyclerView.Adapter<ClipboardImageAdapter.ViewHolder>() {

    class ViewHolder(val image: ImageView) : RecyclerView.ViewHolder(image)

    var items: List<ClipboardImage> = emptyList()
        private set

    private val thumbCache = HashMap<Uri, Bitmap>()
    private var popupMenu: PopupMenu? = null

    fun submit(list: List<ClipboardImage>) {
        items = list
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    fun onDetached() {
        popupMenu?.dismiss()
        popupMenu = null
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = 0x1A6E

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val ctx = parent.context
        val view = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(120)
            )
            background = GradientDrawable().apply {
                cornerRadius = radius
                setColor(theme.clipboardEntryColor)
            }
            foreground = RippleDrawable(
                ColorStateList.valueOf(theme.keyPressHighlightColor), null,
                GradientDrawable().apply { cornerRadius = radius; setColor(Color.WHITE) }
            )
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            clipToOutline = true
            isClickable = true
            isLongClickable = true
        }
        return ViewHolder(view)
    }

    private fun showDeletePopup(view: View, item: ClipboardImage) {
        val ctx = view.context
        val popup = PopupMenu(ctx, view)
        val iconTint = ctx.styledColorCompat(android.R.attr.colorControlNormal)
        popup.menu.item(R.string.delete, R.drawable.ic_baseline_delete_24, iconTint) {
            onDelete(item)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !DeviceUtil.isSamsungOneUI && !DeviceUtil.isFlyme) {
            popup.setForceShowIcon(true)
        }
        popup.setOnDismissListener {
            if (it === popupMenu) popupMenu = null
        }
        popupMenu?.dismiss()
        popupMenu = popup
        popup.show()
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val view = holder.image
        view.tag = item.uri
        view.contentDescription = if (item.fromClipboard) "已复制的图片" else "屏幕截图"
        view.setOnClickListener { onClick(item) }
        view.setOnLongClickListener {
            showDeletePopup(view, item)
            true
        }
        val cached = thumbCache[item.uri]
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageDrawable(null)
        val ctx = view.context
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        runCatching {
                            ctx.contentResolver.loadThumbnail(item.uri, Size(480, 480), null)
                        }.getOrNull() ?: ctx.contentResolver.openInputStream(item.uri)?.use {
                            android.graphics.BitmapFactory.decodeStream(
                                it,
                                null,
                                android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                            )
                        }
                    } else {
                        ctx.contentResolver.openInputStream(item.uri)?.use {
                            android.graphics.BitmapFactory.decodeStream(
                                it,
                                null,
                                android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                            )
                        }
                    }
                }
                    .onFailure { Timber.w(it, "thumbnail failed") }
                    .getOrNull()
            } ?: return@launch
            thumbCache[item.uri] = bmp
            if (view.tag == item.uri) view.setImageBitmap(bmp)
        }
    }
}

/** Full-width section title ("最近" / "已固定" / "截图") like Gboard's clipboard */
class SectionHeaderAdapter(
    private val theme: Theme,
    private val title: String,
    private val actionText: String? = null,
    private val actionIconRes: Int = 0,
    private val actionContentDescription: String? = null,
    private val onActionClick: (() -> Unit)? = null
) : RecyclerView.Adapter<SectionHeaderAdapter.VH>() {

    class VH(root: View, val tv: TextView) : RecyclerView.ViewHolder(root)

    var visible = false
        set(value) {
            if (field == value) return
            field = value
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

    override fun getItemCount() = if (visible) 1 else 0

    override fun getItemViewType(position: Int) = 0x5EC7

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val tv = TextView(ctx).apply {
            textSize = 13f
            setTextColor(theme.candidateCommentColor)
        }
        val actionCallback = onActionClick
        if (actionCallback == null) {
            tv.setPadding(ctx.dp(6), ctx.dp(8), ctx.dp(6), ctx.dp(4))
            tv.layoutParams = RecyclerView.LayoutParams(-1, -2)
            return VH(tv, tv)
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(6), ctx.dp(4), ctx.dp(2), ctx.dp(2))
            layoutParams = RecyclerView.LayoutParams(-1, -2)
        }
        tv.setPadding(0, ctx.dp(4), ctx.dp(4), ctx.dp(2))
        row.addView(tv, LinearLayout.LayoutParams(0, -2, 1f))
        val actionBtn = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = actionContentDescription ?: actionText
            setPadding(ctx.dp(6), ctx.dp(4), ctx.dp(6), ctx.dp(4))
            background = RippleDrawable(
                ColorStateList.valueOf(theme.keyPressHighlightColor),
                null,
                GradientDrawable().apply {
                    cornerRadius = ctx.dp(12).toFloat()
                    setColor(Color.WHITE)
                }
            )
            if (actionIconRes != 0) {
                addView(
                    ImageView(ctx).apply {
                        setImageResource(actionIconRes)
                        imageTintList = ColorStateList.valueOf(theme.candidateCommentColor)
                    },
                    LinearLayout.LayoutParams(ctx.dp(16), ctx.dp(16))
                )
            }
            if (!actionText.isNullOrEmpty()) {
                addView(
                    TextView(ctx).apply {
                        text = actionText
                        textSize = 12f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(theme.candidateCommentColor)
                    },
                    LinearLayout.LayoutParams(-2, -2).apply {
                        if (actionIconRes != 0) marginStart = ctx.dp(3)
                    }
                )
            }
            setOnClickListener { actionCallback() }
        }
        row.addView(actionBtn, LinearLayout.LayoutParams(-2, -2))
        return VH(row, tv)
    }

    override fun onViewAttachedToWindow(holder: VH) {
        super.onViewAttachedToWindow(holder)
        // 两列瀑布流模式下，分组标题占满整行宽度
        (holder.itemView.layoutParams as? StaggeredGridLayoutManager.LayoutParams)?.isFullSpan = true
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.tv.text = title
    }
}
