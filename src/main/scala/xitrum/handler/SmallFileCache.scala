package xitrum.handler

import java.io.{File, RandomAccessFile}

import xitrum.{Cache, Config}
import xitrum.util.{Gzip, Mime, NotModified}

object SmallFileCache {
  private val TTL_IN_MINUTES = 10

  /** lastModified: See http://en.wikipedia.org/wiki/List_of_HTTP_header_fields */
  class GetResult

  case object FileNotFound                                                                                                      extends GetResult
  case class  Hit (val bytes: Array[Byte], val gzipped: Boolean, val lastModified: String, val mimeo: Option[String])           extends GetResult
  case class  Miss(val bytes: Array[Byte], val gzipped: Boolean, val lastModified: String, val mimeo: Option[String])           extends GetResult
  case class  FileTooBig(val file: RandomAccessFile, val lastModified: String, val mimeo: Option[String]) extends GetResult

  //                         body         gzipped  lastModified         MIME
  private type CachedFile = (Array[Byte], Boolean, String,       Option[String])

  /**
   * If abs points to a hidden file, this method returns FileNotFound.
   *
   * @param abs Absolute file path
   */
  def get(abs: String): GetResult = {
    Cache.getAs[CachedFile](abs) match {
      case Some(cachedFile) =>
        val (bytes, gzipped, lastModified, mimeo) = cachedFile
        Hit(bytes, gzipped, lastModified, mimeo)

      case None =>
        // For security do not return hidden file
        val file = new File(abs)
        if (!file.exists || file.isHidden) return FileNotFound

        var raf   = new RandomAccessFile(abs, "r")
        val lm    = NotModified.formatRfc2822(file.lastModified)
        val mimeo = Mime.get(abs)
        if (raf.length > Config.cacheSmallStaticFileMaxSizeInKB * 1024) return FileTooBig(raf, lm, mimeo)

        // Read whole file
        val len   = raf.length.toInt
        val bytes = new Array[Byte](len)
        var total = 0
        while (total < len) {
          val bytesRead = raf.read(bytes, total, len - total)
          total += bytesRead
        }
        raf.close

        // Compress big textual file
        val (bytes2, gzipped) =
          if (len > Config.compressBigTextualResponseMinSizeInKB * 1024 && mimeo.isDefined && Mime.isTextual(mimeo.get))
            (Gzip.compress(bytes), true)
          else
            (bytes, false)

        if (Config.isProductionMode) {
          val cachedFile = (bytes2, gzipped, lm, mimeo)
          Cache.putIfAbsentMinute(abs, cachedFile, TTL_IN_MINUTES)
        }

        Miss(bytes2, gzipped, lm, mimeo)
    }
  }
}