@file:Suppress("unused")
package tk.xszq.otomadbot.database

import com.github.kilianB.datastructures.tree.Result
import com.github.kilianB.hash.Hash
import com.github.kilianB.hashAlgorithms.DifferenceHash
import com.github.kilianB.hashAlgorithms.HashingAlgorithm
import com.github.kilianB.matcher.TypedImageMatcher
import com.github.kilianB.matcher.persistent.database.DatabaseImageMatcher
import com.github.kilianB.matcher.persistent.database.H2DatabaseImageMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tk.xszq.otomadbot.configMain
import tk.xszq.otomadbot.forceGetField
import tk.xszq.otomadbot.media.isImage
import tk.xszq.otomadbot.media.isValidGIF
import tk.xszq.otomadbot.media.isValidGIFBlocking
import tk.xszq.otomadbot.pathPrefix
import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Paths
import java.sql.Connection
import java.sql.ResultSet
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt

@Suppress("BlockingMethodInNonBlockingContext")
suspend fun DatabaseImageMatcher.add(image: File) = withContext(Dispatchers.IO) {
    addImage(image.absolutePath, image)
}
fun DatabaseImageMatcher.resolveTableName(hashAlgo: HashingAlgorithm): String {
    return (hashAlgo.javaClass.simpleName
            + if (hashAlgo.algorithmId() > 0) hashAlgo.algorithmId() else "m" + abs(hashAlgo.algorithmId()))
}
fun DatabaseImageMatcher.reconstructHashFromDatabase(hasher: HashingAlgorithm, bytes: ByteArray): Hash {
    val bArrayWithSign = ByteArray(bytes.size + 1)
    System.arraycopy(bytes, 0, bArrayWithSign, 1, bytes.size)
    val bInt = BigInteger(bArrayWithSign)
    return Hash(bInt, hasher.keyResolution, hasher.algorithmId())
}
fun DatabaseImageMatcher.getSimilarImages(targetHash: Hash, maxDistance: Int, hasher: HashingAlgorithm?): List<Result<String>> {
    val conn = forceGetField<Connection, DatabaseImageMatcher>("conn")
    val tableName: String = resolveTableName(hasher!!)
    val urls: MutableList<Result<String>> = ArrayList()
    conn.createStatement().use { stmt ->
        val rs: ResultSet = stmt.executeQuery("SELECT url,hash FROM $tableName")
        while (rs.next()) {
            val bytes = rs.getBytes(2)
            val h: Hash = reconstructHashFromDatabase(hasher, bytes)
            val distance = targetHash.hammingDistanceFast(h)
            val normalizedDistance = distance / targetHash.bitResolution.toDouble()
            if (distance <= maxDistance) {
                val url = rs.getString(1)
                urls.add(
                    Result(
                        url,
                        distance.toDouble(), normalizedDistance
                    )
                )
            }
        }
    }
    return urls
}
@Suppress("BlockingMethodInNonBlockingContext")
suspend fun DatabaseImageMatcher.exists(image: File): Boolean = withContext(Dispatchers.IO) {
    existsBlocking(image)
}
fun DatabaseImageMatcher.existsBlocking(image: File): Boolean {
    val steps = forceGetField<LinkedHashMap<HashingAlgorithm, TypedImageMatcher.AlgoSettings>,
            TypedImageMatcher>("steps")
    var exists = false
    for ((algo, settings) in steps) {
        val targetHash = algo.hash(image)
        val threshold: Int = if (settings.isNormalized) {
            val hashLength = targetHash.bitResolution
            (settings.threshold * hashLength).roundToInt()
        } else {
            settings.threshold.toInt()
        }
        if (getSimilarImages(targetHash, threshold, algo).isNotEmpty()) {
            exists = true
            break
        }
    }
    return exists
}
/**
 * Initialize H2 Database.
 * @param type Image category
 */
fun doCreateH2ImageDatabase(type: String) {
    H2DatabaseImageMatcher(
        configMain.h2.filename + ".$type", configMain.h2.username,
        configMain.h2.password
    ).use { db ->
        db.addHashingAlgorithm(DifferenceHash(64, DifferenceHash.Precision.Triple), .15)
    }
}
/**
 * Initialize H2 Database.
 * @param type Image category
 */
fun doInitH2Images(type: String, target: String = type) {
    H2DatabaseImageMatcher(
        configMain.h2.filename + "." + target, configMain.h2.username,
        configMain.h2.password
    ).use { db ->
        db.addHashingAlgorithm(DifferenceHash(64, DifferenceHash.Precision.Triple), .15)
        Files.walk(Paths.get("$pathPrefix/image/$type"), 2).filter { i -> Files.isRegularFile(i) }
            .forEach { path -> if (isImage(path) && !isValidGIFBlocking(path.toFile())) db.addImage(path.toFile()) }
    }
}
suspend fun doInsertIntoH2Database(type: String, image: File) {
    withContext(Dispatchers.IO) {
        H2DatabaseImageMatcher(
            configMain.h2.filename + ".$type", configMain.h2.username,
            configMain.h2.password
        ).use { db ->
            db.addHashingAlgorithm(DifferenceHash(64, DifferenceHash.Precision.Triple), .10)
            db.add(image)
        }
    }
}
/**
 * Match whether image is in this category.
 * @param type Category
 * @param target Target image
 * @return Whether it is or not
 */
suspend fun isTargetH2Image(type: String, target: File): Boolean {
    return withContext(Dispatchers.IO) {
        if (isValidGIF(target))
            false
        else
            H2DatabaseImageMatcher(
                configMain.h2.filename + ".$type", configMain.h2.username,
                configMain.h2.password
            ).use { db ->
                db.addHashingAlgorithm(DifferenceHash(64, DifferenceHash.Precision.Triple), .15)
                db.exists(target)
            }
    }
}
