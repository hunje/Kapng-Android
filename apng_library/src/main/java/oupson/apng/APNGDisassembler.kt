package oupson.apng

import android.graphics.BitmapFactory
import oupson.apng.chunks.IHDR
import oupson.apng.chunks.fcTL
import oupson.apng.exceptions.BadApng
import oupson.apng.exceptions.BadCRC
import oupson.apng.exceptions.NotApngException
import oupson.apng.exceptions.NotPngException
import oupson.apng.utils.Utils
import oupson.apng.utils.Utils.Companion.isApng
import oupson.apng.utils.Utils.Companion.isPng
import oupson.apng.utils.Utils.Companion.parseLength
import oupson.apng.utils.Utils.Companion.pngSignature
import oupson.apng.utils.Utils.Companion.to4Bytes
import java.io.InputStream
import java.util.*
import java.util.zip.CRC32

// TODO REWRITE
class APNGDisassembler {
    private var png: ArrayList<Byte>? = null
    private var cover: ArrayList<Byte>? = null
    private var delay = -1f
    private var yOffset = -1
    private var xOffset = -1
    private var plte: ByteArray? = null
    private var tnrs: ByteArray? = null
    private var maxWidth = 0
    private var maxHeight = 0
    private var blendOp: Utils.Companion.BlendOp = Utils.Companion.BlendOp.APNG_BLEND_OP_SOURCE
    private var disposeOp: Utils.Companion.DisposeOp =
        Utils.Companion.DisposeOp.APNG_DISPOSE_OP_NONE
    private var ihdr = IHDR()
    private var isApng = false

    var apng: Apng = Apng()

    /**
     * Disassemble an Apng file
     * @param byteArray The Byte Array of the file
     * @return [Apng] The apng decoded
     */
    fun disassemble(byteArray: ByteArray): Apng {
        reset()
        if (isApng(byteArray)) {
            var cursor = 8
            while (cursor < byteArray.size) {
                val length = parseLength(byteArray.copyOfRange(cursor, cursor + 4))
                val chunk = byteArray.copyOfRange(cursor, cursor + length + 12)
                parseChunk(chunk)
                cursor += length + 12
            }
            return apng
        } else {
            throw NotApngException()
        }
    }

    /**
     * Disassemble an Apng file
     * @param input Input Stream
     * @return [Apng] The apng decoded
     */
    fun disassemble(input: InputStream): Apng {
        reset()
        val buffer = ByteArray(8)

        input.read(buffer)

        if (!isPng(buffer))
            throw NotPngException()

        var byteRead: Int

        val lengthChunk = ByteArray(4)
        do {
            byteRead = input.read(lengthChunk)

            if (byteRead == -1)
                break
            val length = parseLength(lengthChunk)

            val chunk = ByteArray(length + 8)
            byteRead = input.read(chunk)

            parseChunk(lengthChunk.plus(chunk))
        } while (byteRead != -1)

        return apng
    }

    /**
     * Generate a correct IHDR from the IHDR chunk of the APNG
     * @param ihdrOfApng The IHDR of the APNG
     * @param width The width of the frame
     * @param height The height of the frame
     * @return [ByteArray] The generated IHDR
     */
    private fun generateIhdr(ihdrOfApng: IHDR, width: Int, height: Int): ByteArray {
        val ihdr = ArrayList<Byte>()
        // We need a body var to know body length and generate crc
        val ihdrBody = ArrayList<Byte>()
        // Add chunk body length
        ihdr.addAll(to4Bytes(ihdrOfApng.body.size).asList())
        // Add IHDR
        ihdrBody.addAll(
            byteArrayOf(
                0x49.toByte(),
                0x48.toByte(),
                0x44.toByte(),
                0x52.toByte()
            ).asList()
        )
        // Add the max width and height
        ihdrBody.addAll(to4Bytes(width).asList())
        ihdrBody.addAll(to4Bytes(height).asList())
        // Add complicated stuff like depth color ...
        // If you want correct png you need same parameters. Good solution is to create new png.
        ihdrBody.addAll(ihdrOfApng.body.copyOfRange(8, 13).asList())
        // Generate CRC
        val crC32 = CRC32()
        crC32.update(ihdrBody.toByteArray(), 0, ihdrBody.size)
        ihdr.addAll(ihdrBody)
        ihdr.addAll(to4Bytes(crC32.value.toInt()).asList())
        return ihdr.toByteArray()
    }

    /**
     * Parse the chunk
     * @param byteArray The chunk with length and crc
     */
    private fun parseChunk(byteArray: ByteArray) {
        val i = 4
        val chunkCRC = parseLength(byteArray.copyOfRange(byteArray.size - 4, byteArray.size))
        val crc = CRC32()
        crc.update(byteArray.copyOfRange(i, byteArray.size - 4))
        if (chunkCRC == crc.value.toInt()) {
            val name = byteArray.copyOfRange(i, i + 4)
            when {
                name.contentEquals(Utils.fcTL) -> {
                    if (png == null) {
                        cover?.let {
                            it.addAll(to4Bytes(0).asList())
                            // Add IEND
                            val iend = byteArrayOf(0x49, 0x45, 0x4E, 0x44)
                            // Generate crc for IEND
                            val crC32 = CRC32()
                            crC32.update(iend, 0, iend.size)
                            it.addAll(iend.asList())
                            it.addAll(to4Bytes(crC32.value.toInt()).asList())
                            apng.cover = BitmapFactory.decodeByteArray(it.toByteArray(), 0, it.size)
                        }
                        png = ArrayList()
                        val fcTL = fcTL()
                        fcTL.parse(byteArray)
                        delay = fcTL.delay
                        yOffset = fcTL.yOffset
                        xOffset = fcTL.xOffset
                        blendOp = fcTL.blendOp
                        disposeOp = fcTL.disposeOp
                        val width = fcTL.pngWidth
                        val height = fcTL.pngHeight

                        if (xOffset + width > maxWidth) {
                            throw BadApng("`yOffset` + `height` must be <= `IHDR` height")
                        } else if (yOffset + height > maxHeight) {
                            throw BadApng("`yOffset` + `height` must be <= `IHDR` height")
                        }

                        png?.addAll(pngSignature.asList())
                        png?.addAll(generateIhdr(ihdr, width, height).asList())
                        plte?.let {
                            png?.addAll(it.asList())
                        }
                        tnrs?.let {
                            png?.addAll(it.asList())
                        }
                    } else {
                        // Add IEND body length : 0
                        png?.addAll(to4Bytes(0).asList())
                        // Add IEND
                        val iend = byteArrayOf(0x49, 0x45, 0x4E, 0x44)
                        // Generate crc for IEND
                        val crC32 = CRC32()
                        crC32.update(iend, 0, iend.size)
                        png?.addAll(iend.asList())
                        png?.addAll(to4Bytes(crC32.value.toInt()).asList())
                        apng.frames.add(
                            Frame(
                                png!!.toByteArray(),
                                delay,
                                xOffset,
                                yOffset,
                                blendOp,
                                disposeOp,
                                maxWidth,
                                maxHeight
                            )
                        )
                        png = ArrayList()
                        val fcTL = fcTL()
                        fcTL.parse(byteArray)
                        delay = fcTL.delay
                        yOffset = fcTL.yOffset
                        xOffset = fcTL.xOffset
                        blendOp = fcTL.blendOp
                        disposeOp = fcTL.disposeOp
                        val width = fcTL.pngWidth
                        val height = fcTL.pngHeight
                        png?.addAll(pngSignature.asList())
                        png?.addAll(generateIhdr(ihdr, width, height).asList())
                        plte?.let {
                            png?.addAll(it.asList())
                        }
                        tnrs?.let {
                            png?.addAll(it.asList())
                        }
                    }
                }
                name.contentEquals(Utils.IEND) -> {
                    if (isApng) {
                        png?.addAll(to4Bytes(0).asList())
                        // Add IEND
                        val iend = byteArrayOf(0x49, 0x45, 0x4E, 0x44)
                        // Generate crc for IEND
                        val crC32 = CRC32()
                        crC32.update(iend, 0, iend.size)
                        png?.addAll(iend.asList())
                        png?.addAll(to4Bytes(crC32.value.toInt()).asList())
                        apng.frames.add(
                            Frame(
                                png!!.toByteArray(),
                                delay,
                                xOffset,
                                yOffset,
                                blendOp,
                                disposeOp,
                                maxWidth,
                                maxHeight
                            )
                        )
                    } else {
                        cover?.let {
                            it.addAll(to4Bytes(0).asList())
                            // Add IEND
                            val iend = byteArrayOf(0x49, 0x45, 0x4E, 0x44)
                            // Generate crc for IEND
                            val crC32 = CRC32()
                            crC32.update(iend, 0, iend.size)
                            it.addAll(iend.asList())
                            it.addAll(to4Bytes(crC32.value.toInt()).asList())
                            apng.cover = BitmapFactory.decodeByteArray(it.toByteArray(), 0, it.size)
                        }
                        apng.isApng = false
                    }
                }
                name.contentEquals(Utils.IDAT) -> {
                    if (png == null) {
                        if (cover == null) {
                            cover = ArrayList()
                            cover?.addAll(pngSignature.asList())
                            cover?.addAll(generateIhdr(ihdr, maxWidth, maxHeight).asList())
                        }
                        // Find the chunk length
                        val bodySize = parseLength(byteArray.copyOfRange(i - 4, i))
                        cover?.addAll(byteArray.copyOfRange(i - 4, i).asList())
                        val body = ArrayList<Byte>()
                        body.addAll(byteArrayOf(0x49, 0x44, 0x41, 0x54).asList())
                        // Get image bytes
                        body.addAll(byteArray.copyOfRange(i + 4, i + 4 + bodySize).asList())
                        val crC32 = CRC32()
                        crC32.update(body.toByteArray(), 0, body.size)
                        cover?.addAll(body)
                        cover?.addAll(to4Bytes(crC32.value.toInt()).asList())
                    } else {
                        // Find the chunk length
                        val bodySize = parseLength(byteArray.copyOfRange(i - 4, i))
                        png?.addAll(byteArray.copyOfRange(i - 4, i).asList())
                        val body = ArrayList<Byte>()
                        body.addAll(byteArrayOf(0x49, 0x44, 0x41, 0x54).asList())
                        // Get image bytes
                        body.addAll(byteArray.copyOfRange(i + 4, i + 4 + bodySize).asList())
                        val crC32 = CRC32()
                        crC32.update(body.toByteArray(), 0, body.size)
                        png?.addAll(body)
                        png?.addAll(to4Bytes(crC32.value.toInt()).asList())
                    }
                }
                name.contentEquals(Utils.fdAT) -> {
                    // Find the chunk length
                    val bodySize = parseLength(byteArray.copyOfRange(i - 4, i))
                    png?.addAll(to4Bytes(bodySize - 4).asList())
                    val body = ArrayList<Byte>()
                    body.addAll(byteArrayOf(0x49, 0x44, 0x41, 0x54).asList())
                    // Get image bytes
                    body.addAll(byteArray.copyOfRange(i + 8, i + 4 + bodySize).asList())
                    val crC32 = CRC32()
                    crC32.update(body.toByteArray(), 0, body.size)
                    png?.addAll(body)
                    png?.addAll(to4Bytes(crC32.value.toInt()).asList())
                }
                name.contentEquals(Utils.plte) -> {
                    plte = byteArray
                }
                name.contentEquals(Utils.tnrs) -> {
                    tnrs = byteArray
                }
                name.contentEquals(Utils.IHDR) -> {
                    ihdr.parse(byteArray)
                    maxWidth = ihdr.pngWidth
                    maxHeight = ihdr.pngHeight
                }
                name.contentEquals(Utils.acTL) -> {
                    isApng = true
                }
            }
        } else throw BadCRC()
    }

    /**
     * Reset all var before parsing APNG
     */
    private fun reset() {
        png = null
        cover = null
        delay = -1f
        yOffset = -1
        xOffset = -1
        plte = null
        tnrs = null
        maxWidth = 0
        maxHeight = 0
        ihdr = IHDR()
        apng = Apng()
        isApng = false
    }
}