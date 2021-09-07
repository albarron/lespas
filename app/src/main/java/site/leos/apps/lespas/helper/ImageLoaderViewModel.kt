package site.leos.apps.lespas.helper

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.graphics.*
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.Tools.getBitmapFromVector
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoRepository
import java.io.File
import java.io.IOException
import java.lang.Integer.max
import kotlin.math.min

class ImageLoaderViewModel(application: Application) : AndroidViewModel(application) {
    private val rootPath = Tools.getLocalRoot(application)
    private val contentResolver = application.contentResolver
    private val imageCache = ImageCache(((application.getSystemService(Context.ACTIVITY_SERVICE)) as ActivityManager).memoryClass / 8 * 1024 * 1024)
    private val errorBitmap = getBitmapFromVector(application, R.drawable.ic_baseline_broken_image_24)
    private val placeholderBitmap = getBitmapFromVector(application, R.drawable.ic_baseline_placeholder_24)
    private val jobMap = HashMap<Int, Job>()

    private val photoRepository = PhotoRepository(application)

    fun interface LoadCompleteListener{
        fun onLoadComplete()
    }

    private fun decodeBitmap(photo: Photo, type: String): Pair<Bitmap?, Drawable?> {
        var bitmap: Bitmap? = null
        var animatedDrawable: Drawable? = null
        var fileName = ""
        var uri = Uri.EMPTY

        if (photo.albumId == FROM_CAMERA_ROLL) uri = Uri.parse(photo.id)
        else {
            if (type == TYPE_SMALL_COVER || type == TYPE_COVER) {
                // Cover photo is created from Album record in runtime, therefore does not contain name and eTag property
                fileName = "${rootPath}/${photo.id}"
                if (!(File(fileName).exists())) {
                    fileName = "${rootPath}/${photoRepository.getPhotoName(photo.id)}"
                    if (!File(fileName).exists()) return Pair(errorBitmap, null)
                }
            } else fileName = "${rootPath}/${if (photo.eTag.isNotEmpty()) photo.id else photo.name}"
        }

        try {
            bitmap = when (type) {
                TYPE_GRID -> {
                    val size = if ((photo.height < 1600) || (photo.width < 1600)) 2 else 8
                    val rect: Rect = if (photo.height > photo.width) {
                        val top = (photo.height - photo.width) / 2
                        val bottom = top + photo.width
                        Rect(0, top, photo.width, bottom)
                    } else {
                        val left = (photo.width - photo.height) / 2
                        val right = left + photo.height
                        Rect(left, 0, right, photo.height)
                    }
                    with(photo.mimeType) {
                        when {
                            this.startsWith("video")-> {
                                getVideoThumbnail(photo, fileName)
                            }
                            this == "image/agif" || this == "image/gif" || this == "image/webp" || this == "image/awebp" -> {
                                if (photo.albumId == FROM_CAMERA_ROLL) BitmapFactory.decodeStream(contentResolver.openInputStream(uri), null, BitmapFactory.Options().apply { inSampleSize = size })
                                else {
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) ThumbnailUtils.createImageThumbnail(File(fileName), Size(300, 300), null)
                                    else BitmapFactory.decodeFile(fileName, BitmapFactory.Options().apply { inSampleSize = size })
                                }
                            }
                            this == "image/jpeg" || this == "image/png" -> {
                                if (photo.albumId == FROM_CAMERA_ROLL) getImageThumbnail(photo)
                                else
                                    BitmapRegionDecoder.newInstance(fileName, false).decodeRegion(rect, BitmapFactory.Options().apply {
                                        this.inSampleSize = size
                                        this.inPreferredConfig = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888
                                    })
                            }
                            else-> BitmapFactory.decodeFile(fileName, BitmapFactory.Options().apply { this.inSampleSize = size })
                        }
                    }
                }
                TYPE_FULL -> {
                    if (photo.mimeType.startsWith("video")) getVideoThumbnail(photo, fileName)
                    else {
                        var bmp =
                            if (photo.albumId == FROM_CAMERA_ROLL) {
                                // Photo from camera roll doesn't support image/awebp, image/agif
                                if ((photo.mimeType == "image/webp" || photo.mimeType == "image/gif") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                    animatedDrawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(contentResolver, uri))
                                    null
                                }
                                else BitmapFactory.decodeStream(contentResolver.openInputStream(uri))
                            }
                            else {
                                if ((photo.mimeType == "image/awebp" || photo.mimeType == "image/agif") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                    animatedDrawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(File(fileName)))
                                    null
                                }
                                else BitmapFactory.decodeFile(fileName)
                            }

                        // If image is too large
                        // TODO hardcoded size
                        bmp?.let {
                            if (bmp!!.allocationByteCount > 100000000) {
                                bmp!!.recycle()
                                val option = BitmapFactory.Options().apply { inSampleSize = 2 }
                                bmp = if (photo.albumId == FROM_CAMERA_ROLL) BitmapFactory.decodeStream(contentResolver.openInputStream(uri), null, option) else BitmapFactory.decodeFile(fileName, option)
                            }
                        }

                        // Rotate according to EXIF when this photo comes from camera roll
                        bmp?.let {
                            if (photo.albumId == FROM_CAMERA_ROLL && photo.shareId != 0) bmp = Bitmap.createBitmap(bmp!!, 0, 0, photo.width, photo.height, Matrix().apply { preRotate((photo.shareId).toFloat()) }, true)
                        }

                        bmp
                    }
                }
                TYPE_COVER, TYPE_SMALL_COVER -> {
/*
                    if (photo.mimeType.startsWith("video/")) { getVideoThumbnail(photo, fileName) }
                    else {
                        val size = if ((photo.height < 1600) || (photo.width < 1600)) 1 else if (type == TYPE_SMALL_COVER) 8 else 4
                        // cover baseline value passed in property shareId
                        val bottom = min(photo.shareId + (photo.width.toFloat() * 9 / 21).toInt(), photo.height)
                        val rect = Rect(0, photo.shareId, photo.width, bottom)
                        BitmapRegionDecoder.newInstance(fileName, false).decodeRegion(rect, BitmapFactory.Options().apply {
                            this.inSampleSize = size
                            this.inPreferredConfig = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888
                        }) ?: placeholderBitmap
                    }
*/
                    // cover baseline value passed in property shareId
                    val options = BitmapFactory.Options().apply {
                        this.inSampleSize = if ((photo.height < 1600) || (photo.width < 1600)) 1 else if (type == TYPE_SMALL_COVER) 8 else 4
                        this.inPreferredConfig = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888
                    }
                    try {
                        var bmp: Bitmap?
                        if (photo.albumId == FROM_CAMERA_ROLL) {
                            // cover orientation passed in property eTag
                            (try {photo.eTag.toFloat()} catch (e: Exception) { 0.0F }).also { orientation->

                                val rect = when(orientation) {
                                    0.0F-> Rect(0, photo.shareId, photo.width - 1, min(photo.shareId + (photo.width.toFloat() * 9 / 21).toInt(), photo.height - 1))
                                    90.0F-> Rect(photo.shareId, 0, min(photo.shareId + (photo.height.toFloat() * 9 / 21).toInt(), photo.width - 1), photo.height - 1)
                                    180.0F-> (photo.height - photo.shareId).let { Rect(0, max(it - (photo.width.toFloat() * 9 / 21).toInt(), 0), photo.width - 1, it) }
                                    else-> (photo.width - photo.shareId).let { Rect(max(it - (photo.height.toFloat() * 9 / 21).toInt(), 0), 0, it, photo.height - 1) }
                                }

                                // Decode region
                                bmp = BitmapRegionDecoder.newInstance(contentResolver.openInputStream(uri), false).decodeRegion(rect, options)

                                // Rotate if needed
                                if (orientation != 0.0F) bmp?.let { bmp = Bitmap.createBitmap(bmp!!, 0, 0, bmp!!.width, bmp!!.height, Matrix().apply { preRotate(orientation) }, true) }
                            }
                        } else {
                            val bottom = min(photo.shareId + (photo.width.toFloat() * 9 / 21).toInt(), photo.height - 1)
                            val rect = Rect(0, photo.shareId, photo.width - 1, bottom)
                            bmp = BitmapRegionDecoder.newInstance(fileName, false).decodeRegion(rect, options) ?: placeholderBitmap
                        }

                        bmp ?: placeholderBitmap
                    } catch (e: IOException) {
                        // Video only album has video file as cover, BitmapRegionDecoder will throw IOException with "Image format not supported" stack trace message
                        //e.printStackTrace()
                        getVideoThumbnail(photo, fileName)
                    }
                }
                else -> errorBitmap
            }
        } catch (e: Exception) { e.printStackTrace() }

        return Pair(bitmap, animatedDrawable)
    }

    fun invalid(photoId: String) {
        imageCache.snapshot().keys.forEach { key-> if (key.startsWith(photoId)) imageCache.remove(key) }
    }

    /*
    fun reloadPhoto(photo: Photo) {
        viewModelScope.launch(Dispatchers.IO) {
            imageCache.snapshot().keys.forEach { key->
                if (key.startsWith(photo.id)) {
                    imageCache.put(key, decodeBitmap(photo, key.substringAfter(photo.id).substringBefore('-')))
                    // TODO recycle old bitmap?
                }
            }
        }
    }

     */

    @SuppressLint("NewApi")
    @JvmOverloads
    fun loadPhoto(photo: Photo, view: ImageView, type: String, callBack: LoadCompleteListener? = null) {
        val jobKey = System.identityHashCode(view)

        val job = viewModelScope.launch(Dispatchers.IO) {
            var decodeResult: Pair<Bitmap?, Drawable?> = Pair(null, null)
            var bitmap: Bitmap?
            var key = "${photo.id}$type"

            // suffix 'baseline' in case same photo chosen
            if ((type == TYPE_COVER) || (type == TYPE_SMALL_COVER)) key = "$key-${photo.shareId}"

            try {
                // Show something first
                //view.setImageBitmap(placeholderBitmap)
                //view.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                bitmap = imageCache.get(key)
                // Give error another chance
                if (bitmap == null || bitmap == errorBitmap) {
                    if (type == TYPE_FULL && photo.albumId == FROM_CAMERA_ROLL && !Tools.isMediaPlayable(photo.mimeType)) {
                        // Load thumbnail for external storage file
                        getImageThumbnail(photo)?.let { if (isActive) { withContext(Dispatchers.Main) {
                            view.setImageBitmap(it)
                            callBack?.onLoadComplete()
                        }}}
                    }
                    decodeResult = decodeBitmap(photo, type)
                    bitmap = decodeResult.first
                    if (bitmap == null) bitmap = errorBitmap
                    else if (type != TYPE_FULL) imageCache.put(key, bitmap)
                }

                //Log.e(">>>", "${bitmap.allocationByteCount} aa ${imageCache.putCount()} ${imageCache.snapshot().size}")

                // If we are still active at this moment, set the imageview
                if (isActive) {
                    withContext(Dispatchers.Main) {
                        decodeResult.second?.let { view.setImageDrawable(it.apply { (this as AnimatedImageDrawable).start() }) } ?: run { view.setImageBitmap(bitmap) }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                withContext(Dispatchers.Main) { callBack?.onLoadComplete() }
            }
        }.apply {
            //invokeOnCompletion { jobMap.remove(jobKey) }
        }

        // Replacing previous job
        replacePrevious(jobKey, job)
    }

    fun cancelLoading(view: ImageView) { jobMap[System.identityHashCode(view)]?.cancel() }

    private fun replacePrevious(key: Int, newJob: Job) {
        jobMap[key]?.cancel()
        jobMap[key] = newJob
    }

    private fun getImageThumbnail(photo: Photo): Bitmap? =
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, Uri.parse(photo.id))) { decoder, _, _ -> decoder.setTargetSampleSize(if ((photo.height < 1600) || (photo.width < 1600)) 2 else 8)}
                // TODO: For photo captured in Sony Xperia machine, loadThumbnail will load very small size bitmap
                //contentResolver.loadThumbnail(Uri.parse(photo.id), Size(photo.width/8, photo.height/8), null)
            } else {
                MediaStore.Images.Thumbnails.getThumbnail(contentResolver, photo.id.substringAfterLast('/').toLong(), MediaStore.Images.Thumbnails.MINI_KIND, null).run {
                    if (photo.shareId != 0) Bitmap.createBitmap(this, 0, 0, this.width, this.height, Matrix().also { it.preRotate(photo.shareId.toFloat()) }, true)
                    else this
                }
            }
        } catch(e: Exception) {
            e.printStackTrace()
            null
        }

    private fun getVideoThumbnail(photo: Photo, fileName: String): Bitmap =
        try {
            if (photo.albumId == FROM_CAMERA_ROLL) {
                val photoId = photo.id.substringAfterLast('/').toLong()
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    try {
                        contentResolver.loadThumbnail(Uri.parse(photo.id), Size(photo.width, photo.height), null)
                    } catch (e: ArithmeticException) {
                        // Some Android Q Rom, like AEX for EMUI 9, throw this exception
                        e.printStackTrace()
                        MediaStore.Video.Thumbnails.getThumbnail(contentResolver, photoId, MediaStore.Video.Thumbnails.MINI_KIND, null)
                    }
                } else {
                    MediaStore.Video.Thumbnails.getThumbnail(contentResolver, photoId, MediaStore.Video.Thumbnails.MINI_KIND, null)
                }
            }
            else {
                var bitmap: Bitmap? = null
                val thumbnailFile = "$fileName.thumbnail"
                bitmap = BitmapFactory.decodeFile(thumbnailFile)
                if (bitmap == null) {
                    MediaMetadataRetriever().apply {
                        setDataSource(fileName)
                        // Get first frame
                        bitmap = getFrameAtTime(0L)
                        release()
                    }
                    bitmap?.compress(Bitmap.CompressFormat.JPEG, 90, File(thumbnailFile).outputStream())
                }
                bitmap ?: placeholderBitmap
            }
        } catch (e: Exception) {
            e.printStackTrace()
            placeholderBitmap
        }

    override fun onCleared() {
        super.onCleared()
        jobMap.forEach { if (it.value.isActive) it.value.cancel() }
    }

    class ImageCache (maxSize: Int) : LruCache<String, Bitmap>(maxSize) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    companion object {
        const val TYPE_GRID = "_view"
        const val TYPE_FULL = "_full"
        const val TYPE_COVER = "_cover"
        const val TYPE_SMALL_COVER = "_smallcover"

        const val FROM_CAMERA_ROLL = "!@#$%^&*()_+alkdfj4654"
    }
}