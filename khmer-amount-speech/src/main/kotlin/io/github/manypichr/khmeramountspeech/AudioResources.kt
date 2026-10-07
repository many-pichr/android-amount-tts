package io.github.manypichr.khmeramountspeech

import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.io.IOException

/** Where one clip's audio lives. */
public sealed interface AudioSource {
    /** A file inside the APK's assets, e.g. "khmeramountspeech/audio/km/numbers/1.mp3". */
    public data class Asset(val path: String) : AudioSource

    /** A file on disk, e.g. a recording downloaded at runtime. */
    public data class FileSource(val file: File) : AudioSource
}

/**
 * Finds each token's audio. Return null for unavailable segments.
 *
 * Use [AudioResources.bundled] for the clips shipped with this library, or
 * [AudioResources.assetFolder] / [AudioResources.directory] to use your own recordings.
 */
public fun interface AudioResolver {
    public fun resolve(token: AudioToken): AudioSource?
}

/**
 * Locates the audio clips bundled with the library. They live in the AAR's assets with the layout
 * `khmeramountspeech/audio/<lang>/<type>/<value>.mp3`.
 */
public object AudioResources {
    /** Assets folder that contains the bundled `audio` folder. */
    public const val ASSET_ROOT: String = "khmeramountspeech"

    /** Resolver for the clips bundled with this library. */
    @JvmStatic
    public fun bundled(context: Context): AudioResolver = assetResolver(context.applicationContext.assets, ASSET_ROOT, null)

    /**
     * Resolver for clips stored in your app's assets with the same layout
     * (`<root>/audio/<lang>/<type>/<value>.mp3`), e.g. professional studio recordings. Tokens
     * without a file there fall back to the bundled clips.
     */
    @JvmStatic
    public fun assetFolder(context: Context, root: String): AudioResolver {
        val assets = context.applicationContext.assets
        return assetResolver(assets, root.trim('/'), bundled(context))
    }

    /**
     * Resolver for clips in a folder on disk with the same layout
     * (`<root>/audio/<lang>/<type>/<value>.mp3`). Tokens without a file there fall back to the
     * bundled clips.
     */
    @JvmStatic
    public fun directory(context: Context, root: File): AudioResolver {
        val fallback = bundled(context)
        return AudioResolver { token ->
            val file = File(root, token.resourcePath + ".mp3")
            if (file.isFile) AudioSource.FileSource(file) else fallback.resolve(token)
        }
    }

    private fun assetResolver(assets: AssetManager, root: String, fallback: AudioResolver?): AudioResolver {
        // AssetManager has no cheap "exists"; list each folder once and remember its files.
        val listings = HashMap<String, Set<String>>()
        fun files(folder: String): Set<String> = synchronized(listings) {
            listings.getOrPut(folder) {
                try {
                    assets.list(folder)?.toSet() ?: emptySet()
                } catch (_: IOException) {
                    emptySet()
                }
            }
        }
        return AudioResolver { token ->
            val folder = "$root/audio/${token.language.code}/${token.type.folder}"
            val name = "${token.value}.mp3"
            if (name in files(folder)) AudioSource.Asset("$folder/$name") else fallback?.resolve(token)
        }
    }
}
